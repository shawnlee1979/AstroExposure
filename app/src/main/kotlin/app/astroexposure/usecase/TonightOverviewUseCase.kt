package app.astroexposure.usecase

import app.astroexposure.domain.catalog.DeepSkyObject
import app.astroexposure.domain.catalog.EclipseCatalog
import app.astroexposure.domain.catalog.EclipseRecord
import app.astroexposure.domain.catalog.LightPollutionSource
import app.astroexposure.domain.catalog.LightPollutionVersion
import app.astroexposure.domain.catalog.MeteorShower
import app.astroexposure.domain.catalog.MeteorShowerCatalog
import app.astroexposure.domain.catalog.ObjectCatalog
import app.astroexposure.domain.ephemeris.CelestialBody
import app.astroexposure.domain.ephemeris.EphemerisProvider
import app.astroexposure.domain.ephemeris.EquatorialCoordinates
import app.astroexposure.domain.ephemeris.GalacticCenterWindow
import app.astroexposure.domain.ephemeris.NightWindow
import app.astroexposure.domain.ephemeris.SunEvents
import app.astroexposure.domain.ephemeris.TargetPass
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.sky.MoonPhaseName
import app.astroexposure.domain.sky.MoonPhaseNames
import app.astroexposure.domain.sky.SkyBrightness
import app.astroexposure.domain.timeline.NightTimeline
import app.astroexposure.domain.timeline.TimelineCursor
import app.astroexposure.domain.units.Degrees
import app.astroexposure.support.ShowerDates
import app.astroexposure.support.SkyAssembly
import app.astroexposure.support.SkyContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Full "tonight overview" (design §6.4 simplified scope, §7.2 key-moments
 * list): twilight cards, moon + total-sky verdict, low-moonlight windows,
 * galactic-centre window, eclipses and showers within ±3 days, and a static
 * bright-object shortlist (catalogue magnitude ≤ 6.5, culmination ≥ 40° via
 * the target-pass port, brightest first, at most 10). All instants and the
 * location are supplied by the caller; nothing here reads a clock.
 */
class TonightOverviewUseCase(
    private val ephemeris: EphemerisProvider,
    private val objectCatalog: ObjectCatalog? = null,
    private val eclipseCatalog: EclipseCatalog? = null,
    private val showerCatalog: MeteorShowerCatalog? = null,
    private val lightPollution: LightPollutionSource? = null,
) {

    enum class MomentKind {
        SUNSET, CIVIL_DUSK_END, NAUTICAL_DUSK_END, ASTRO_DUSK,
        MOONRISE, MOONSET, GC_CULMINATION, ASTRO_DAWN, SUNRISE,
    }

    data class Moment(val instant: Instant, val kind: MomentKind)

    data class LowMoonlightWindow(val start: Instant, val end: Instant)

    data class RecommendedObject(val obj: DeepSkyObject, val pass: TargetPass)

    /**
     * Everything the static four-layer timeline draws (ui-design §7.4):
     * the axis span, the 5-minute Sun/Moon altitude samples, the solar
     * band segmentation and Moon-up spans derived from those samples in
     * the domain layer, and the galactic-centre window clamped to the
     * axis (open edges clamp to the axis ends).
     */
    data class Timeline(
        val axisStart: Instant,
        val axisEnd: Instant,
        val samples: List<NightTimeline.Sample>,
        val bandSegments: List<NightTimeline.BandSegment>,
        val moonAboveIntervals: List<NightTimeline.Interval>,
        val galacticCenterInterval: NightTimeline.Interval?,
        /** False on nights with no astronomical night: the axis then shows curves only. */
        val hasAstronomicalNight: Boolean,
    )

    /**
     * Site light pollution for the location card (ui-design §7.2): the
     * grid cell's artificial luminance and its Bortle estimate via the
     * domain's single conversion, plus the source's recorded data
     * version. [artificialMcdPerM2] is null where the grid has no data.
     * [versionInfo] is the same header in structured form; the card's
     * short display is composed from it (never the raw citation).
     */
    data class LightPollutionInfo(
        val artificialMcdPerM2: Double?,
        val bortleEstimate: Int?,
        val dataVersion: String,
        val versionInfo: LightPollutionVersion? = null,
    )

    /**
     * Sun detail behind the timeline's Sun marks (ui-design §7.4): the
     * selected night's sunset and the following sunrise, each with the
     * azimuth sampled from the ephemeris port at that instant. A field
     * is null when the event does not occur (polar day/night).
     */
    data class SunDetail(
        val sunset: Instant?,
        val sunsetAzimuthDeg: Double?,
        val sunrise: Instant?,
        val sunriseAzimuthDeg: Double?,
    )

    /**
     * Moon detail behind the timeline's Moon marks (ui-design §7.4):
     * rise/set instants with azimuths sampled at those instants, plus
     * the phase name (classified from the port's phase angle at dusk)
     * and the illumination fraction shown elsewhere on the page.
     */
    data class MoonDetail(
        val moonrise: Instant?,
        val moonriseAzimuthDeg: Double?,
        val moonset: Instant?,
        val moonsetAzimuthDeg: Double?,
        val phaseName: MoonPhaseName,
        val illumination: Double,
    )

    /**
     * One shootable target at the cursor instant (ui-design §7.5):
     * its altitude and moonlight contribution at the cursor (the two
     * ranking keys), the above-threshold arc intervals across the axis
     * with the sampled culmination, and whether it is still rising at
     * the cursor.
     */
    data class CursorTarget(
        val obj: DeepSkyObject,
        val altitudeDeg: Double,
        val moonlightMcdPerM2: Double,
        val arcIntervals: List<NightTimeline.Interval>,
        val culmination: Instant,
        val culminationAltitudeDeg: Double,
        val rising: Boolean,
        /** Rising / culmination / setting segmentation of the arc (ui-design §7.5, 0.5.1). */
        val phases: TimelineCursor.ArcPhases? = null,
        /**
         * The part of the recommended shooting window that lies inside
         * this target's ≥20° arc — the span the lane frames with a
         * bright border (ui-design §7.5, 0.6.0); null when the
         * recommended window and the arc do not meet.
         */
        val recommendedFrame: NightTimeline.Interval? = null,
    )

    /**
     * The one activated target of the cursor detail card (ui-design
     * §7.5, 0.5.1 revision): evaluated for the requested object id
     * regardless of the shootable ranking, so the card keeps showing
     * the same target — with its honest state — even when the cursor
     * moves to a moment where the target is below the threshold or has
     * set. [phase] is null when the target never reaches the threshold
     * tonight; [framing] is the full-frame focal advice from the
     * catalogue major-axis size (NoSizeData when the catalogue has no
     * size for it).
     */
    data class ActiveTarget(
        val obj: DeepSkyObject,
        val altitudeDeg: Double,
        val azimuthDeg: Double,
        val phase: TimelineCursor.PassPhase?,
        val moonInterference: TimelineCursor.MoonInterference,
        val arcIntervals: List<NightTimeline.Interval>,
        val phases: TimelineCursor.ArcPhases?,
        val framing: app.astroexposure.domain.camera.FullFrameFraming.Advice,
        /** True spherical Moon separation at the cursor, degrees (ui-design §7.5, 0.6.0). */
        val moonSeparationDeg: Double = 0.0,
        /** Whether the Moon is above the horizon at the cursor. */
        val moonUp: Boolean = false,
        /** Excluded / caution / clear classification of the Moon for this target. */
        val moonProximity: TimelineCursor.MoonProximity = TimelineCursor.MoonProximity.CLEAR,
        /** The night's recommended shooting window for this target; null without samples. */
        val recommended: TimelineCursor.RecommendedWindow? = null,
        /** The cursor's position against [recommended]; null when there is no window. */
        val recommendedState: TimelineCursor.RecommendedState? = null,
        /** Minutes from the cursor until [recommended] opens; 0 when inside or past it. */
        val recommendedMinutesUntil: Long = 0,
    )

    /**
     * Everything the cursor state renders (ui-design §7.5): the status
     * row's four items (solar segment + Sun altitude, moonlight tier,
     * low-moonlight window membership), the bubble's Moon figures, and
     * the ranked "shootable now" list. [targets] is capped at
     * [TimelineCursor.MAX_DISPLAYED_TARGETS] for display and arcs;
     * [totalShootable] counts every qualifying target. Since 0.6.0 the
     * moonlight exclusion (ui-design §7.5) is applied before ranking,
     * so neither list nor count includes a target the Moon rules out
     * at this instant; the whole-night shortlist is unaffected.
     * [targetsAvailable] is false when the object catalogue failed to
     * load — the same explicit degradation as the whole-night list.
     */
    data class CursorEvaluation(
        val instant: Instant,
        val segment: NightTimeline.SunBand,
        val sunAltitudeDeg: Double,
        val moonAltitudeDeg: Double,
        val moonIllumination: Double,
        val moonInterference: TimelineCursor.MoonInterference,
        val lowMoonStatus: TimelineCursor.LowMoonStatus,
        val targets: List<CursorTarget>,
        val totalShootable: Int,
        val targetsAvailable: Boolean,
        /** The activated target's detail state; null when none was requested or found. */
        val activeTarget: ActiveTarget? = null,
    )

    /** The three mutually exclusive states the events section can render. */
    enum class EventsDisplay { UNAVAILABLE, NONE, LIST }

    sealed interface Event {
        data class Eclipse(val record: EclipseRecord) : Event
        data class Shower(val shower: MeteorShower, val approxPeakDate: LocalDate, val daysFromPeak: Double) : Event
    }

    data class Result(
        val night: NightWindow?,
        val sunEvents: SunEvents,
        /** Illumination fraction 0..1, sampled at dusk (or at local noon when there is no night). */
        val moonIllumination: Double,
        val moonrise: Instant?,
        val moonset: Instant?,
        val galacticCenter: GalacticCenterWindow?,
        /** Total sky at dusk toward the galactic centre; null without a light-pollution source. */
        val skyAtDusk: SkyContext?,
        /** True when moonlight degrades deep-sky / galactic-centre work tonight (design §5 rule 2). */
        val moonDegraded: Boolean,
        val lowMoonlightWindows: List<LowMoonlightWindow>,
        val events: List<Event>,
        /** False when an event catalogue failed to load and events are incomplete. */
        val eventsAvailable: Boolean,
        val recommendations: List<RecommendedObject>,
        /** False when the object catalogue failed to load (see recommendations()). */
        val recommendationsAvailable: Boolean,
        val moments: List<Moment>,
        val timeline: Timeline,
        /** Null when no light-pollution source is configured or it failed to load. */
        val lightPollutionInfo: LightPollutionInfo? = null,
        /** Detail data for the timeline mark dialogs (ui-design §7.4). */
        val sunDetail: SunDetail,
        val moonDetail: MoonDetail,
    ) {
        /** Events-section state, kept out of the composable so it is unit-testable. */
        val eventsDisplay: EventsDisplay
            get() = when {
                !eventsAvailable -> EventsDisplay.UNAVAILABLE
                events.isEmpty() -> EventsDisplay.NONE
                else -> EventsDisplay.LIST
            }
    }

    fun execute(location: GeoLocation, date: LocalDate, manualBortle: Int? = null): Result {
        val night = ephemeris.astronomicalNight(location, date)
        val sun = ephemeris.sunEvents(location, date)
        val nextSun = ephemeris.sunEvents(location, date.plusDays(1))
        val riseSet = ephemeris.moonRiseSet(location, date)
        val dusk = night?.dusk ?: date.atTime(12, 0).atZone(location.zone).toInstant()
        val illumination = ephemeris.moonIllumination(dusk)
        val gc = ephemeris.galacticCenterWindow(location, date)

        // Mark-dialog details (ui-design §7.4): rise/set azimuths are
        // the port's horizontal position sampled at the event instant.
        fun azimuthAt(body: CelestialBody, instant: Instant?): Double? =
            instant?.let { ephemeris.horizontalPosition(location, body, it).azimuth.value }
        val sunDetail = SunDetail(
            sunset = sun.sunset,
            sunsetAzimuthDeg = azimuthAt(CelestialBody.SUN, sun.sunset),
            sunrise = nextSun.sunrise,
            sunriseAzimuthDeg = azimuthAt(CelestialBody.SUN, nextSun.sunrise),
        )
        val moonDetail = MoonDetail(
            moonrise = riseSet.moonrise,
            moonriseAzimuthDeg = azimuthAt(CelestialBody.MOON, riseSet.moonrise),
            moonset = riseSet.moonset,
            moonsetAzimuthDeg = azimuthAt(CelestialBody.MOON, riseSet.moonset),
            phaseName = MoonPhaseNames.of(ephemeris.moonPhaseAngle(dusk)),
            illumination = illumination,
        )

        val sky = lightPollution?.let {
            SkyAssembly.compose(
                ephemeris, it, location, dusk,
                EquatorialCoordinates(GC_RA_HOURS, Degrees(GC_DEC_DEG)),
                manualBortle,
            )
        }

        val moments = buildList {
            sun.sunset?.let { add(Moment(it, MomentKind.SUNSET)) }
            sun.civilDuskEnd?.let { add(Moment(it, MomentKind.CIVIL_DUSK_END)) }
            sun.nauticalDuskEnd?.let { add(Moment(it, MomentKind.NAUTICAL_DUSK_END)) }
            night?.let { add(Moment(it.dusk, MomentKind.ASTRO_DUSK)) }
            riseSet.moonrise?.let { add(Moment(it, MomentKind.MOONRISE)) }
            riseSet.moonset?.let { add(Moment(it, MomentKind.MOONSET)) }
            gc?.let { add(Moment(it.culmination, MomentKind.GC_CULMINATION)) }
            night?.let { add(Moment(it.dawn, MomentKind.ASTRO_DAWN)) }
            nextSun.sunrise?.let {
                add(Moment(it, MomentKind.SUNRISE))
            }
        }.sortedBy { it.instant }

        val (eventList, eventsOk) = events(date)
        val (shortlist, shortlistOk) = recommendations(location, date)
        return Result(
            night = night,
            sunEvents = sun,
            moonIllumination = illumination,
            moonrise = riseSet.moonrise,
            moonset = riseSet.moonset,
            galacticCenter = gc,
            skyAtDusk = sky,
            moonDegraded = sky?.totalSky?.moonDegraded ?: false,
            lowMoonlightWindows = lowMoonlightWindows(location, night, sky),
            events = eventList,
            eventsAvailable = eventsOk,
            recommendations = shortlist,
            recommendationsAvailable = shortlistOk,
            moments = moments,
            timeline = buildTimeline(location, date, sun, night, gc),
            lightPollutionInfo = lightPollutionInfo(location),
            sunDetail = sunDetail,
            moonDetail = moonDetail,
        )
    }

    /**
     * Evaluate one cursor instant (ui-design §7.5). The instant is
     * normalised here as well as in the UI — truncated to the minute
     * and clamped to the axis — so any caller gets the spec's minute
     * precision. Moonlight contributions follow the existing
     * [SkyAssembly] sampling definition (toward each target; toward
     * the galactic centre for the status row), never a second formula.
     * Per-target arcs are sampled at the timeline's 5-minute step and
     * computed only for the displayed targets, keeping the ephemeris
     * cost bounded by the display cap.
     */
    fun evaluateCursor(
        location: GeoLocation,
        date: LocalDate,
        instant: Instant,
        timeline: Timeline,
        manualBortle: Int? = null,
        activeObjectId: String? = null,
    ): CursorEvaluation {
        val cursor = TimelineCursor.clampToAxis(
            TimelineCursor.truncatedToMinute(instant), timeline.axisStart, timeline.axisEnd,
        )
        val night = ephemeris.astronomicalNight(location, date)
        val sunAltitude = ephemeris.horizontalPosition(location, CelestialBody.SUN, cursor).altitude.value
        val moonPosition = ephemeris.horizontalPosition(location, CelestialBody.MOON, cursor)
        val gcTarget = EquatorialCoordinates(GC_RA_HOURS, Degrees(GC_DEC_DEG))
        val gcSky = SkyAssembly.compose(ephemeris, lightPollution, location, cursor, gcTarget, manualBortle)
        val baselineTotal = SkyBrightness.NATURAL_SKY_MCD_M2 +
            (gcSky.totalSky.baselineArtificialMcdPerM2 ?: 0.0)
        val lowMoonStatus = TimelineCursor.lowMoonStatus(
            cursor,
            lowMoonlightWindows(location, night, gcSky).map {
                NightTimeline.Interval(it.start, it.end)
            },
        )

        val catalog = objectCatalog
        val all = if (catalog == null) {
            null
        } else {
            try {
                catalog.allObjects()
            } catch (e: Exception) {
                null
            }
        }
        if (all == null) {
            return CursorEvaluation(
                instant = cursor,
                segment = NightTimeline.bandFor(sunAltitude),
                sunAltitudeDeg = sunAltitude,
                moonAltitudeDeg = moonPosition.altitude.value,
                moonIllumination = gcSky.moonIllumination,
                moonInterference = TimelineCursor.moonInterference(
                    gcSky.moonlightMcdPerM2, baselineTotal,
                ),
                lowMoonStatus = lowMoonStatus,
                targets = emptyList(),
                totalShootable = 0,
                targetsAvailable = catalog == null,
            )
        }

        // Per-target evaluation at the cursor. The Moon separation is
        // the TRUE spherical angle between the target's and the Moon's
        // lines of sight (both from the port's horizontal coordinates);
        // the interference tier reuses the existing SkyAssembly
        // sampling definition toward the target, exactly as the
        // ranking does. The 0.6.0 exclusion (Moon up + separation
        // < 40° + tier STRONG) is applied BEFORE ranking and the
        // display cap, so an excluded target neither draws an arc nor
        // takes a top-10 slot.
        data class EvaluatedTarget(
            val obj: DeepSkyObject,
            val coords: EquatorialCoordinates,
            val candidate: TimelineCursor.Candidate,
            val moonSeparationDeg: Double,
            val interference: TimelineCursor.MoonInterference,
            val moonExcluded: Boolean,
        )
        val moonAltitudeDeg = moonPosition.altitude.value
        val evaluated = all.map { obj ->
            val coords = EquatorialCoordinates(obj.raHours, Degrees(obj.decDeg))
            val altitude = ephemeris.altitudeOf(location, coords, cursor).value
            val azimuth = ephemeris.azimuthOf(location, coords, cursor).value
            val separation = TimelineCursor.angularSeparationDeg(
                app.astroexposure.domain.ephemeris.HorizontalCoordinates(
                    Degrees(altitude), Degrees(azimuth),
                ),
                moonPosition,
            )
            var moonlight = 0.0
            var tier = TimelineCursor.MoonInterference.NONE
            if (altitude >= TimelineCursor.MIN_ALTITUDE_DEG) {
                val targetSky = SkyAssembly.compose(
                    ephemeris, lightPollution, location, cursor, coords, manualBortle,
                )
                moonlight = targetSky.moonlightMcdPerM2
                val targetBaseline = SkyBrightness.NATURAL_SKY_MCD_M2 +
                    (targetSky.totalSky.baselineArtificialMcdPerM2 ?: 0.0)
                tier = TimelineCursor.moonInterference(moonlight, targetBaseline)
            }
            EvaluatedTarget(
                obj = obj,
                coords = coords,
                candidate = TimelineCursor.Candidate(altitude, moonlight),
                moonSeparationDeg = separation,
                interference = tier,
                moonExcluded = TimelineCursor.moonExcluded(separation, moonAltitudeDeg, tier),
            )
        }
        val eligible = evaluated.filter { !it.moonExcluded }
        val ranked = TimelineCursor.rankShootable(eligible.map { it.candidate })
        val step = Duration.ofMinutes(NightTimeline.SAMPLE_STEP_MINUTES)
        // Arc samples are shared between the displayed targets and the
        // activated target (an activated target is usually displayed too).
        val arcSamplesCache = mutableMapOf<String, List<TimelineCursor.AltitudeSample>>()
        fun arcSamplesFor(obj: DeepSkyObject, coords: EquatorialCoordinates):
            List<TimelineCursor.AltitudeSample> = arcSamplesCache.getOrPut(obj.id) {
                val samples = mutableListOf<TimelineCursor.AltitudeSample>()
                var t = timeline.axisStart
                while (!t.isAfter(timeline.axisEnd)) {
                    samples += TimelineCursor.AltitudeSample(
                        t, ephemeris.altitudeOf(location, coords, t).value,
                    )
                    t = t.plus(step)
                }
                samples
            }
        val targets = ranked.take(TimelineCursor.MAX_DISPLAYED_TARGETS).map { index ->
            val entry = eligible[index]
            val obj = entry.obj
            val coords = entry.coords
            val candidate = entry.candidate
            val arcSamples = arcSamplesFor(obj, coords)
            val culmination = TimelineCursor.culminationOf(arcSamples)
            val phases = TimelineCursor.arcPhases(
                arcSamples, TimelineCursor.MIN_ALTITUDE_DEG, timeline.axisEnd,
            )
            // The framed span on the lane: the recommended window cut
            // to this target's ≥20° arc window (the bar never draws
            // the part of the window that lies outside the arc).
            val recommendedFrame = TimelineCursor.recommendedWindow(
                arcSamples, timeline.axisEnd,
            )?.let { recommended ->
                val window = phases?.window ?: return@let null
                val start = maxOf(recommended.interval.start, window.start)
                val end = minOf(recommended.interval.end, window.end)
                if (end.isAfter(start)) NightTimeline.Interval(start, end) else null
            }
            CursorTarget(
                obj = obj,
                altitudeDeg = candidate.altitudeDeg,
                moonlightMcdPerM2 = candidate.moonlightMcdPerM2,
                arcIntervals = TimelineCursor.aboveThresholdIntervals(
                    arcSamples, TimelineCursor.MIN_ALTITUDE_DEG, timeline.axisEnd,
                ),
                culmination = culmination?.instant ?: cursor,
                culminationAltitudeDeg = culmination?.altitudeDeg ?: candidate.altitudeDeg,
                rising = ephemeris.altitudeOf(
                    location, coords, cursor.plus(Duration.ofMinutes(15)),
                ).value > candidate.altitudeDeg,
                phases = phases,
                recommendedFrame = recommendedFrame,
            )
        }
        // The activated target (ui-design §7.5, 0.5.1): evaluated on its
        // own, independent of the ranking — the detail card must not
        // lose its target when the cursor moves somewhere the target
        // no longer qualifies.
        val activeTarget = activeObjectId?.let { id ->
            evaluated.firstOrNull { it.obj.id == id }?.let { entry ->
                val obj = entry.obj
                val coords = entry.coords
                val arcSamples = arcSamplesFor(obj, coords)
                val phases = TimelineCursor.arcPhases(
                    arcSamples, TimelineCursor.MIN_ALTITUDE_DEG, timeline.axisEnd,
                )
                val targetSky = SkyAssembly.compose(
                    ephemeris, lightPollution, location, cursor, coords, manualBortle,
                )
                val targetBaseline = SkyBrightness.NATURAL_SKY_MCD_M2 +
                    (targetSky.totalSky.baselineArtificialMcdPerM2 ?: 0.0)
                val activeTier = TimelineCursor.moonInterference(
                    targetSky.moonlightMcdPerM2, targetBaseline,
                )
                // The recommended window comes from the target's own
                // full-axis samples, so the card can show it even on
                // nights where the target never reaches 20°.
                val recommended = TimelineCursor.recommendedWindow(
                    arcSamples, timeline.axisEnd,
                )
                ActiveTarget(
                    obj = obj,
                    altitudeDeg = ephemeris.altitudeOf(location, coords, cursor).value,
                    azimuthDeg = ephemeris.azimuthOf(location, coords, cursor).value,
                    phase = phases?.let { TimelineCursor.phaseAt(it, cursor) },
                    moonInterference = activeTier,
                    arcIntervals = TimelineCursor.aboveThresholdIntervals(
                        arcSamples, TimelineCursor.MIN_ALTITUDE_DEG, timeline.axisEnd,
                    ),
                    phases = phases,
                    framing = app.astroexposure.domain.camera.FullFrameFraming.advice(
                        obj.sizeMajorArcmin,
                    ),
                    // The pinned target keeps its card even when the
                    // cursor moves into the exclusion zone (ui-design
                    // §7.5, 0.6.0): the proximity classification —
                    // computed with the same tier the card displays —
                    // tells the card to flag it instead of dropping it.
                    moonSeparationDeg = entry.moonSeparationDeg,
                    moonUp = moonAltitudeDeg > 0.0,
                    moonProximity = TimelineCursor.moonProximity(
                        entry.moonSeparationDeg, moonAltitudeDeg, activeTier,
                    ),
                    recommended = recommended,
                    recommendedState = recommended?.let {
                        TimelineCursor.recommendedState(it, cursor)
                    },
                    recommendedMinutesUntil = recommended?.let {
                        TimelineCursor.recommendedMinutesUntil(it, cursor)
                    } ?: 0L,
                )
            }
        }
        return CursorEvaluation(
            instant = cursor,
            segment = NightTimeline.bandFor(sunAltitude),
            sunAltitudeDeg = sunAltitude,
            moonAltitudeDeg = moonPosition.altitude.value,
            moonIllumination = gcSky.moonIllumination,
            moonInterference = TimelineCursor.moonInterference(
                gcSky.moonlightMcdPerM2, baselineTotal,
            ),
            lowMoonStatus = lowMoonStatus,
            targets = targets,
            totalShootable = ranked.size,
            targetsAvailable = true,
            activeTarget = activeTarget,
        )
    }

    /**
     * Timeline axis and samples (ui-design §7.4). The normal span is
     * sunset −1 h to next-morning sunrise +1 h. When rise/set events do
     * not occur (polar day/night) the axis falls back to the astronomical
     * night widened by three hours on each side — a display span only,
     * never presented as rise/set precision — and when there is no
     * night either, to the civil day containing the evening (local noon
     * to local noon), so the curves still render without inventing
     * events.
     */
    private fun buildTimeline(
        location: GeoLocation,
        date: LocalDate,
        sun: SunEvents,
        night: NightWindow?,
        gc: GalacticCenterWindow?,
    ): Timeline {
        val nextSunrise = ephemeris.sunEvents(location, date.plusDays(1)).sunrise
        val sunset = sun.sunset
        val (axisStart, axisEnd) = when {
            sunset != null && nextSunrise != null ->
                sunset.minus(Duration.ofHours(1)) to nextSunrise.plus(Duration.ofHours(1))
            night != null ->
                night.dusk.minus(Duration.ofHours(3)) to night.dawn.plus(Duration.ofHours(3))
            else ->
                date.atTime(12, 0).atZone(location.zone).toInstant() to
                    date.plusDays(1).atTime(12, 0).atZone(location.zone).toInstant()
        }
        val step = Duration.ofMinutes(NightTimeline.SAMPLE_STEP_MINUTES)
        val samples = mutableListOf<NightTimeline.Sample>()
        var t = axisStart
        while (!t.isAfter(axisEnd)) {
            samples += NightTimeline.Sample(
                instant = t,
                sunAltitudeDeg = ephemeris.horizontalPosition(location, CelestialBody.SUN, t).altitude.value,
                moonAltitudeDeg = ephemeris.horizontalPosition(location, CelestialBody.MOON, t).altitude.value,
                moonIllumination = ephemeris.moonIllumination(t),
            )
            t = t.plus(step)
        }
        // Without an astronomical night the timeline degrades to the
        // Sun/Moon curves plus the explicit no-night note (ui-design
        // §7.4): the solar background bands and the galactic-centre
        // interval band are absent, not merely unpainted.
        val hasNight = night != null
        return Timeline(
            axisStart = axisStart,
            axisEnd = axisEnd,
            samples = samples,
            bandSegments = if (hasNight) {
                NightTimeline.bandSegments(samples, axisStart, axisEnd)
            } else {
                emptyList()
            },
            moonAboveIntervals = NightTimeline.moonAboveIntervals(samples, axisStart, axisEnd),
            galacticCenterInterval = if (hasNight) {
                gc?.let {
                    NightTimeline.clampToAxis(it.risesAbove, it.setsBelow, axisStart, axisEnd)
                }
            } else {
                null
            },
            hasAstronomicalNight = hasNight,
        )
    }

    /**
     * Location-card light pollution. A source that fails to load is an
     * explicit no-data outcome (null), never a guessed value: the card
     * then offers the manual Bortle fallback (design §2.8).
     */
    private fun lightPollutionInfo(location: GeoLocation): LightPollutionInfo? {
        val source = lightPollution ?: return null
        return try {
            val artificial = source.artificialBrightnessMcdPerM2(location)
            LightPollutionInfo(
                artificialMcdPerM2 = artificial,
                bortleEstimate = artificial?.let {
                    SkyBrightness.bortleFromMpsas(
                        SkyBrightness.totalToMpsas(SkyBrightness.NATURAL_SKY_MCD_M2 + it),
                    )
                },
                dataVersion = source.dataVersion,
                versionInfo = source.versionInfo,
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Low-moonlight windows: half-hour samples across the night where the
     * Moon is down or its contribution stays below one third of the
     * baseline (natural + artificial) sky — the design §5 rule-2 definition,
     * applied mechanically so the result is explainable sample by sample.
     */
    private fun lowMoonlightWindows(
        location: GeoLocation,
        night: NightWindow?,
        skyAtDusk: SkyContext?,
    ): List<LowMoonlightWindow> {
        if (night == null || skyAtDusk == null || lightPollution == null) return emptyList()
        val baselineTotal = SkyBrightness.NATURAL_SKY_MCD_M2 +
            (skyAtDusk.totalSky.baselineArtificialMcdPerM2 ?: 0.0)
        val threshold = baselineTotal / 3.0
        val gcTarget = EquatorialCoordinates(GC_RA_HOURS, Degrees(GC_DEC_DEG))
        val windows = mutableListOf<LowMoonlightWindow>()
        var runStart: Instant? = null
        var t = night.dusk
        while (!t.isAfter(night.dawn)) {
            val ctx = SkyAssembly.compose(
                ephemeris, lightPollution, location, t, gcTarget, null,
            )
            val low = !ctx.moonUp || ctx.moonlightMcdPerM2 < threshold
            if (low && runStart == null) runStart = t
            if (!low && runStart != null) {
                if (Duration.between(runStart, t).toMinutes() >= 60) {
                    windows += LowMoonlightWindow(runStart, t)
                }
                runStart = null
            }
            t = t.plus(Duration.ofMinutes(30))
        }
        if (runStart != null && Duration.between(runStart, night.dawn).toMinutes() >= 60) {
            windows += LowMoonlightWindow(runStart, night.dawn)
        }
        return windows
    }

    /**
     * Eclipses and shower peaks whose dates fall within ±3 days
     * (design §6.4). Same degradation policy as the object shortlist: a
     * catalogue that fails to load (corrupt document, unparseable date,
     * missing resource — any load failure) contributes no events and
     * flips the availability flag; the overview itself never fails over
     * an auxiliary catalogue.
     */
    private fun events(date: LocalDate): Pair<List<Event>, Boolean> {
        val from = date.minusDays(3)
        val to = date.plusDays(3)
        var available = true
        val eclipses = try {
            eclipseCatalog
                ?.eclipses(from.year, to.year)
                ?.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
                ?.map { Event.Eclipse(it) }
                ?: emptyList()
        } catch (e: Exception) {
            available = false
            emptyList()
        }
        val showers = try {
            showerCatalog
                ?.showers()
                ?.mapNotNull { shower ->
                    val days = ShowerDates.daysFromPeak(shower, date)
                    if (kotlin.math.abs(days) <= 3.0) {
                        Event.Shower(shower, ShowerDates.approxDate(shower.peakLon, date), days)
                    } else {
                        null
                    }
                }
                ?: emptyList()
        } catch (e: Exception) {
            available = false
            emptyList()
        }
        val all = (eclipses + showers).sortedBy {
            when (it) {
                is Event.Eclipse -> it.record.date
                is Event.Shower -> it.approxPeakDate
            }
        }
        return all to available
    }

    /**
     * Static shortlist (design §6.4): catalogue magnitude ≤ 6.5, tonight's
     * pass reaching ≥ 40° culmination, brightest first, at most 10. Passes
     * are evaluated brightest-first and the scan stops once 10 qualify, so
     * the ephemeris cost stays bounded.
     */
    private fun recommendations(
        location: GeoLocation,
        date: LocalDate,
    ): Pair<List<RecommendedObject>, Boolean> {
        val catalog = objectCatalog ?: return emptyList<RecommendedObject>() to true
        // Explicit degradation, not a silent catch: ANY catalogue load
        // failure (malformed JSON, unparseable field, unknown record
        // type, missing resource) degrades the shortlist to empty and
        // reports [Result.recommendationsAvailable] = false, so the UI
        // can say "recommendations unavailable" instead of pretending
        // tonight simply has no targets. The outcome is computed per
        // call and carried in the return value — no state survives
        // between execute() calls on this (singleton) use case.
        val all = try {
            catalog.allObjects()
        } catch (e: Exception) {
            return emptyList<RecommendedObject>() to false
        }
        val candidates = all
            .filter { (it.magnitude ?: Double.MAX_VALUE) <= MAX_MAGNITUDE }
            .sortedBy { it.magnitude }
        val out = mutableListOf<RecommendedObject>()
        for (obj in candidates) {
            if (out.size >= MAX_RECOMMENDATIONS) break
            val pass = ephemeris.targetPass(
                location,
                EquatorialCoordinates(obj.raHours, Degrees(obj.decDeg)),
                date,
                Degrees(MIN_CULMINATION_DEG),
            ) ?: continue
            if (pass.culminationAltitude.value >= MIN_CULMINATION_DEG) {
                out += RecommendedObject(obj, pass)
            }
        }
        return out to true
    }

    companion object {
        /** Galactic centre coordinates (design §4.1), same values as the ephemeris adapter. */
        const val GC_RA_HOURS = 17.75
        const val GC_DEC_DEG = -29.0
        const val MAX_MAGNITUDE = 6.5
        const val MIN_CULMINATION_DEG = 40.0
        const val MAX_RECOMMENDATIONS = 10
    }
}
