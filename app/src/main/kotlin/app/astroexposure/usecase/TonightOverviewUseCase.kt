package app.astroexposure.usecase

import app.astroexposure.domain.catalog.ConstellationEntry
import app.astroexposure.domain.catalog.ConstellationCatalog
import app.astroexposure.domain.catalog.DeepSkyObject
import app.astroexposure.domain.catalog.EclipseCatalog
import app.astroexposure.domain.catalog.EclipseRecord
import app.astroexposure.domain.catalog.LightPollutionSource
import app.astroexposure.domain.catalog.LightPollutionVersion
import app.astroexposure.domain.catalog.MeteorShower
import app.astroexposure.domain.catalog.MeteorShowerCatalog
import app.astroexposure.domain.catalog.ObjectCatalog
import app.astroexposure.domain.catalog.WideFieldCatalog
import app.astroexposure.domain.catalog.WideFieldViewpoint
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
 * galactic-centre window, eclipses and showers within ±3 days, and the
 * cross-category tonight board (ui-design §7.8): deep-sky objects,
 * constellations and wide-field viewpoints under per-category gates and
 * quotas (at most 10 rows), plus meteor radiant lanes for showers near
 * their peak. All instants and the location are supplied by the caller;
 * nothing here reads a clock.
 */
class TonightOverviewUseCase(
    private val ephemeris: EphemerisProvider,
    private val objectCatalog: ObjectCatalog? = null,
    private val eclipseCatalog: EclipseCatalog? = null,
    private val showerCatalog: MeteorShowerCatalog? = null,
    private val lightPollution: LightPollutionSource? = null,
    private val constellationCatalog: ConstellationCatalog? = null,
    private val wideFieldCatalog: WideFieldCatalog? = null,
) {

    enum class MomentKind {
        SUNSET, CIVIL_DUSK_END, NAUTICAL_DUSK_END, ASTRO_DUSK,
        MOONRISE, MOONSET, GC_CULMINATION, RADIANT_CULMINATION, ASTRO_DAWN, SUNRISE,
    }

    data class Moment(val instant: Instant, val kind: MomentKind)

    data class LowMoonlightWindow(val start: Instant, val end: Instant)

    /** Board and lane categories (ui-design §7.8). METEOR exists for lanes and the filter only. */
    enum class SubjectCategory { DEEP_SKY, CONSTELLATION, WIDE_FIELD, METEOR }

    /**
     * One entry of the unified tonight subject model (ui-design §7.8):
     * every downstream quantity (shootable window, recommended
     * interval, culmination, Moon geometry) is computed at
     * [coordinates] — the calculation point — so an entry's angular
     * extent never changes the shared definitions, only the point
     * they are evaluated at. Ids are namespaced by category so the
     * four catalogues can never collide.
     */
    sealed interface TonightSubject {
        val id: String
        val category: SubjectCategory
        val coordinates: EquatorialCoordinates
        /** Angular extent, degrees; null for a radiant (a point). */
        val sizeMajorDeg: Double?
        val sizeMinorDeg: Double?

        data class DeepSky(val obj: DeepSkyObject) : TonightSubject {
            override val id: String get() = obj.id
            override val category: SubjectCategory get() = SubjectCategory.DEEP_SKY
            override val coordinates: EquatorialCoordinates
                get() = EquatorialCoordinates(obj.raHours, Degrees(obj.decDeg))
            override val sizeMajorDeg: Double? get() = obj.sizeMajorArcmin?.div(60.0)
            override val sizeMinorDeg: Double? get() = obj.sizeMinorArcmin?.div(60.0)
        }

        data class Constellation(val entry: ConstellationEntry) : TonightSubject {
            override val id: String get() = "constellation:${entry.code}"
            override val category: SubjectCategory get() = SubjectCategory.CONSTELLATION
            override val coordinates: EquatorialCoordinates
                get() = EquatorialCoordinates(entry.centerRaHours, Degrees(entry.centerDecDeg))
            override val sizeMajorDeg: Double get() = entry.sizeMajorDeg.toDouble()
            override val sizeMinorDeg: Double get() = entry.sizeMinorDeg.toDouble()
        }

        data class Viewpoint(val viewpoint: WideFieldViewpoint) : TonightSubject {
            override val id: String get() = "viewpoint:${viewpoint.id}"
            override val category: SubjectCategory get() = SubjectCategory.WIDE_FIELD
            override val coordinates: EquatorialCoordinates
                get() = EquatorialCoordinates(viewpoint.anchorRaHours, Degrees(viewpoint.anchorDecDeg))
            override val sizeMajorDeg: Double get() = viewpoint.sizeMajorDeg.toDouble()
            override val sizeMinorDeg: Double get() = viewpoint.sizeMinorDeg.toDouble()
        }

        /** A shower's radiant at the night's date (catalogue radiant plus drift), lanes only. */
        data class Radiant(val shower: MeteorShower, val radiant: EquatorialCoordinates) : TonightSubject {
            override val id: String get() = "shower:${shower.iauCode}"
            override val category: SubjectCategory get() = SubjectCategory.METEOR
            override val coordinates: EquatorialCoordinates get() = radiant
            override val sizeMajorDeg: Double? get() = null
            override val sizeMinorDeg: Double? get() = null
        }
    }

    /**
     * One row of the cross-category tonight board (ui-design §7.8):
     * the member's sampled culmination, its night-cut ≥20° window
     * total and span, and its recommended interval (uncut — the same
     * window the lane frames). Rows are ordered by recommended start.
     */
    data class ShortlistEntry(
        val subject: TonightSubject,
        val culmination: Instant,
        val culminationAltitudeDeg: Double,
        val shootableMinutes: Long,
        /** First ≥20° sample inside the night span; null when the window misses the night. */
        val windowStart: Instant?,
        /** Last ≥20° sample inside the night span; null together with [windowStart]. */
        val windowEnd: Instant?,
        /** True when the ≥20° window covers the whole presentation night span (sample tolerance). */
        val spansWholeNight: Boolean,
        val recommended: TimelineCursor.RecommendedWindow?,
    )

    /**
     * One lane on the timeline (ui-design §7.8): a board entry's — or
     * a qualifying radiant's — sampled pass over the axis, in the same
     * shape the cursor-state lanes use, so both states draw from one
     * structure. Radiant lanes carry no phases and no recommended
     * frame (the recommended interval does not apply to a radiant);
     * their culmination goes to the marker row instead.
     */
    data class LaneEntry(
        val subject: TonightSubject,
        val altitudeSamples: List<TimelineCursor.AltitudeSample>,
        val arcIntervals: List<NightTimeline.Interval>,
        val phases: TimelineCursor.ArcPhases?,
        val recommendedFrame: NightTimeline.Interval?,
        val culmination: Instant,
        val culminationAltitudeDeg: Double,
        /** Radiant lanes only: the shower's approximate peak date for the detail dialog. */
        val meteorPeakDate: LocalDate? = null,
        /** Radiant lanes only: the shower's peak ZHR. */
        val meteorZhr: Int? = null,
    )

    /**
     * Everything the layered timeline draws (ui-design §7.4):
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
        /** False on nights with no astronomical night: the window band rows are then absent. */
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
        val subject: TonightSubject,
        val altitudeDeg: Double,
        val moonlightMcdPerM2: Double,
        val arcIntervals: List<NightTimeline.Interval>,
        val culmination: Instant,
        val culminationAltitudeDeg: Double,
        val rising: Boolean,
        /**
         * The target's real altitude samples across the whole axis at
         * the timeline's 5-minute step (ui-design §7.5, 0.8.9): the
         * series the lane's altitude curve is drawn from — the same
         * samples [arcIntervals], [phases] and the culmination are
         * derived from, carried whole so the UI draws the curve from
         * data, never from a template shape.
         */
        val altitudeSamples: List<TimelineCursor.AltitudeSample> = emptyList(),
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
     * One target's detail at the cursor instant, evaluated on demand
     * for the timeline detail dialog (ui-design §7.5): computed for the
     * requested object id regardless of the shootable ranking, so the
     * dialog shows the target's honest state at the cursor. [phase] is
     * null when the target never reaches the threshold tonight;
     * [framing] is the full-frame focal advice from the catalogue
     * major-axis size (NoSizeData when the catalogue has no size for
     * it).
     */
    data class TargetDetail(
        val subject: TonightSubject,
        val altitudeDeg: Double,
        val azimuthDeg: Double,
        val phase: TimelineCursor.PassPhase?,
        val moonInterference: TimelineCursor.MoonInterference,
        val arcIntervals: List<NightTimeline.Interval>,
        /**
         * The ≥20° pass cut to the astronomical night for presentation
         * (ui-design §7.5, 0.8.4); null when the pass and the night do
         * not overlap. [phase] is classified from the uncut pass.
         */
        val phases: TimelineCursor.ArcPhases?,
        val framing: app.astroexposure.domain.camera.FullFrameFraming.Advice,
        /** True spherical Moon separation at the cursor, degrees (ui-design §7.5, 0.6.0). */
        val moonSeparationDeg: Double = 0.0,
        /** Whether the Moon is above the horizon at the cursor. */
        val moonUp: Boolean = false,
        /** Excluded / caution / clear classification of the Moon for this target. */
        val moonProximity: TimelineCursor.MoonProximity = TimelineCursor.MoonProximity.CLEAR,
        /**
         * The recommended shooting window cut to the astronomical
         * night (ui-design §7.5, 0.8.4); null without samples or when
         * the window and the night do not overlap.
         */
        val recommended: TimelineCursor.RecommendedWindow? = null,
        /** The cursor's position against [recommended]; null when there is no window. */
        val recommendedState: TimelineCursor.RecommendedState? = null,
        /** Minutes from the cursor until [recommended] opens; 0 when inside or past it. */
        val recommendedMinutesUntil: Long = 0,
        /**
         * When the target crosses the horizon upward tonight (the
         * ephemeris port's pass at a 0° threshold); null when it is
         * already up at the pass start or never rises tonight.
         */
        val horizonRise: Instant? = null,
        /** When the target crosses the horizon downward tonight; null when it stays up. */
        val horizonSet: Instant? = null,
        /**
         * The night's culmination from the target's own samples (the
         * peak sample — the same peak [phases] is built from when a
         * ≥20° window exists, so the two never disagree); null when
         * there are no samples. Unlike [phases] it is present for
         * low targets too, because the detail dialog's grid states
         * the culmination of even a poor pass honestly.
         */
        val culmination: Instant? = null,
        /** Altitude at [culmination], degrees; null together with it. */
        val culminationAltitudeDeg: Double? = null,
        /** Radiant details only: the shower's approximate peak date (the sixth grid row). */
        val meteorPeakDate: LocalDate? = null,
        /** Radiant details only: the shower's peak ZHR. */
        val meteorZhr: Int? = null,
    )

    /**
     * Everything the cursor state renders (ui-design §7.5): the status
     * row's four items (solar segment + Sun altitude, moonlight tier,
     * low-moonlight window membership), the bubble's Moon figures, and
     * the ranked shootable set whose members draw arcs. [targets] is
     * capped at [TimelineCursor.MAX_DISPLAYED_TARGETS] for display and
     * arcs; [totalShootable] counts every qualifying target. Since
     * 0.6.0 the moonlight exclusion (ui-design §7.5) is applied before
     * ranking, so neither list nor count includes a target the Moon
     * rules out at this instant; the whole-night shortlist is
     * unaffected. [targetsAvailable] is false when the object
     * catalogue failed to load — the same explicit degradation as the
     * whole-night list. A single target's detail is NOT part of this
     * evaluation: the detail dialog asks [evaluateTargetAtCursor].
     * [skyTowardGalacticCenter] is the galactic-centre sky sample at
     * the cursor — the same composition the status figures above are
     * classified from — carried so the location card's environment
     * section can follow the cursor with the very same sample
     * (ui-design §7.2, 0.8.6) instead of composing a second one.
     */
    data class CursorEvaluation(
        val instant: Instant,
        val segment: NightTimeline.SunBand,
        val sunAltitudeDeg: Double,
        val moonAltitudeDeg: Double,
        val moonIllumination: Double,
        val skyTowardGalacticCenter: SkyContext,
        val moonInterference: TimelineCursor.MoonInterference,
        val lowMoonStatus: TimelineCursor.LowMoonStatus,
        val targets: List<CursorTarget>,
        val totalShootable: Int,
        val targetsAvailable: Boolean,
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
        /**
         * The whole-night moonlight-interference tier (ui-design §7.2):
         * the tier of the dusk sky toward the galactic centre — the
         * same [skyAtDusk] sample the location card's environment
         * section shows, classified by the shared tier function; null
         * when there is no sky context.
         */
        val moonInterferenceAtDusk: TimelineCursor.MoonInterference? = null,
        val lowMoonlightWindows: List<LowMoonlightWindow>,
        val events: List<Event>,
        /** False when an event catalogue failed to load and events are incomplete. */
        val eventsAvailable: Boolean,
        val shortlist: List<ShortlistEntry>,
        /** False when any board catalogue failed to load (see tonightBoard()). */
        val shortlistAvailable: Boolean,
        /** Whole-night lanes, in board order (ui-design §7.8). */
        val lanes: List<LaneEntry>,
        /** Meteor radiant lanes, drawn after the board lanes (ui-design §7.8). */
        val radiantLanes: List<LaneEntry>,
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

        /**
         * The moonrise the location card's moonlight line names
         * (ui-design §7.2, 0.8.4): set when the dusk sky sample caught
         * the Moon below the horizon — the reason its contribution
         * reads zero — yet the Moon rises within the astronomical
         * night. Null in every other case.
         */
        val moonriseWithinNight: Instant?
            get() = moonriseNoteInstant(
                moonUpAtDusk = skyAtDusk?.moonUp,
                moonrise = moonrise,
                night = night,
            )
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
                app.astroexposure.domain.ephemeris.GalacticCenter.COORDINATES,
                manualBortle,
            )
        }

        val timeline = buildTimeline(location, date, sun, night, gc)
        val board = tonightBoard(location, date, timeline)
        val radiant = radiantLanes(location, date, timeline)

        val moments = buildList {
            sun.sunset?.let { add(Moment(it, MomentKind.SUNSET)) }
            sun.civilDuskEnd?.let { add(Moment(it, MomentKind.CIVIL_DUSK_END)) }
            sun.nauticalDuskEnd?.let { add(Moment(it, MomentKind.NAUTICAL_DUSK_END)) }
            night?.let { add(Moment(it.dusk, MomentKind.ASTRO_DUSK)) }
            riseSet.moonrise?.let { add(Moment(it, MomentKind.MOONRISE)) }
            riseSet.moonset?.let { add(Moment(it, MomentKind.MOONSET)) }
            gc?.let { add(Moment(it.culmination, MomentKind.GC_CULMINATION)) }
            radiant.forEach { add(Moment(it.culmination, MomentKind.RADIANT_CULMINATION)) }
            night?.let { add(Moment(it.dawn, MomentKind.ASTRO_DAWN)) }
            nextSun.sunrise?.let {
                add(Moment(it, MomentKind.SUNRISE))
            }
        }.sortedBy { it.instant }

        val (eventList, eventsOk) = events(date)
        return Result(
            night = night,
            sunEvents = sun,
            moonIllumination = illumination,
            moonrise = riseSet.moonrise,
            moonset = riseSet.moonset,
            galacticCenter = gc,
            skyAtDusk = sky,
            moonDegraded = sky?.totalSky?.moonDegraded ?: false,
            moonInterferenceAtDusk = sky?.let {
                TimelineCursor.moonInterference(
                    it.moonlightMcdPerM2, it.totalSky.baselineTotalMcdPerM2,
                )
            },
            lowMoonlightWindows = lowMoonlightWindows(location, night, sky),
            events = eventList,
            eventsAvailable = eventsOk,
            shortlist = board.entries,
            shortlistAvailable = board.available,
            lanes = board.lanes,
            radiantLanes = radiant,
            moments = moments,
            timeline = timeline,
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
    ): CursorEvaluation {
        val cursor = TimelineCursor.clampToAxis(
            TimelineCursor.truncatedToMinute(instant), timeline.axisStart, timeline.axisEnd,
        )
        val night = ephemeris.astronomicalNight(location, date)
        val sunAltitude = ephemeris.horizontalPosition(location, CelestialBody.SUN, cursor).altitude.value
        val moonPosition = ephemeris.horizontalPosition(location, CelestialBody.MOON, cursor)
        val gcTarget = app.astroexposure.domain.ephemeris.GalacticCenter.COORDINATES
        val gcSky = SkyAssembly.compose(ephemeris, lightPollution, location, cursor, gcTarget, manualBortle)
        val baselineTotal = gcSky.totalSky.baselineTotalMcdPerM2
        val lowMoonStatus = TimelineCursor.lowMoonStatus(
            cursor,
            lowMoonlightWindows(location, night, gcSky).map {
                NightTimeline.Interval(it.start, it.end)
            },
        )

        // The subject universe is cross-category since 0.9.0
        // (ui-design §7.8): deep-sky objects, constellation centres,
        // wide-field anchors and tonight's qualifying radiants all
        // join the same set rules. A configured catalogue that fails
        // to load degrades the whole set explicitly, exactly as the
        // single-catalogue version did.
        val load = loadBoardSubjects()
        if (!load.available) {
            return CursorEvaluation(
                instant = cursor,
                segment = NightTimeline.bandFor(sunAltitude),
                sunAltitudeDeg = sunAltitude,
                moonAltitudeDeg = moonPosition.altitude.value,
                moonIllumination = gcSky.moonIllumination,
                skyTowardGalacticCenter = gcSky,
                moonInterference = TimelineCursor.moonInterference(
                    gcSky.moonlightMcdPerM2, baselineTotal,
                ),
                lowMoonStatus = lowMoonStatus,
                targets = emptyList(),
                totalShootable = 0,
                targetsAvailable = false,
            )
        }
        val subjects = load.subjects +
            radiantLaneData(location, date, timeline).map { it.subject }

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
            val subject: TonightSubject,
            val coords: EquatorialCoordinates,
            val candidate: TimelineCursor.Candidate,
            val moonSeparationDeg: Double,
            val interference: TimelineCursor.MoonInterference,
            val moonExcluded: Boolean,
        )
        val moonAltitudeDeg = moonPosition.altitude.value
        val evaluated = subjects.map { subject ->
            val coords = subject.coordinates
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
                tier = TimelineCursor.moonInterference(
                    moonlight, targetSky.totalSky.baselineTotalMcdPerM2,
                )
            }
            EvaluatedTarget(
                subject = subject,
                coords = coords,
                candidate = TimelineCursor.Candidate(altitude, moonlight),
                moonSeparationDeg = separation,
                interference = tier,
                moonExcluded = TimelineCursor.moonExcluded(separation, moonAltitudeDeg, tier),
            )
        }
        val eligible = evaluated.filter { !it.moonExcluded }
        val ranked = TimelineCursor.rankShootable(eligible.map { it.candidate })
        // Arc samples are computed once per displayed target per call.
        val arcSamplesCache = mutableMapOf<String, List<TimelineCursor.AltitudeSample>>()
        fun arcSamplesFor(subject: TonightSubject, coords: EquatorialCoordinates):
            List<TimelineCursor.AltitudeSample> = arcSamplesCache.getOrPut(subject.id) {
                sampleArcAltitudes(location, timeline, coords)
            }
        val targets = ranked.take(TimelineCursor.MAX_DISPLAYED_TARGETS).map { index ->
            val entry = eligible[index]
            val subject = entry.subject
            val coords = entry.coords
            val candidate = entry.candidate
            val arcSamples = arcSamplesFor(subject, coords)
            val culmination = TimelineCursor.culminationOf(arcSamples)
            val phases = TimelineCursor.arcPhases(
                arcSamples, TimelineCursor.MIN_ALTITUDE_DEG, timeline.axisEnd,
            )
            // The framed span on the lane: the recommended window cut
            // to this target's ≥20° arc window (the bar never draws
            // the part of the window that lies outside the arc).
            val recommendedFrame = recommendedFrameFor(arcSamples, phases, timeline.axisEnd)
            CursorTarget(
                subject = subject,
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
                altitudeSamples = arcSamples,
                phases = phases,
                recommendedFrame = recommendedFrame,
            )
        }
        return CursorEvaluation(
            instant = cursor,
            segment = NightTimeline.bandFor(sunAltitude),
            sunAltitudeDeg = sunAltitude,
            moonAltitudeDeg = moonPosition.altitude.value,
            moonIllumination = gcSky.moonIllumination,
            skyTowardGalacticCenter = gcSky,
            moonInterference = TimelineCursor.moonInterference(
                gcSky.moonlightMcdPerM2, baselineTotal,
            ),
            lowMoonStatus = lowMoonStatus,
            targets = targets,
            totalShootable = ranked.size,
            targetsAvailable = true,
        )
    }

    /**
     * Detail for ONE catalogue target at a cursor instant, backing the
     * timeline detail dialog (ui-design §7.5): computed only when a
     * target lane is tapped, for the tapped object, independent of the
     * shootable ranking — the dialog shows the target's state at the
     * cursor whatever the ranking says. The instant is normalised like
     * the cursor's (truncated to the minute, clamped to the axis).
     * Returns null when there is no catalogue, it fails to load, or
     * the id is unknown. The recommended window comes from the target's
     * own full-axis samples, so it is available even on nights where
     * the target never reaches 20°. The shootable window and the
     * recommended window are presented cut to the astronomical night
     * (ui-design §7.5, 0.8.4): the raw runs span the whole axis and
     * would otherwise leak past dawn for near-circumpolar targets.
     */
    fun evaluateTargetAtCursor(
        location: GeoLocation,
        date: LocalDate,
        instant: Instant,
        timeline: Timeline,
        manualBortle: Int? = null,
        objectId: String,
    ): TargetDetail? {
        val cursor = TimelineCursor.clampToAxis(
            TimelineCursor.truncatedToMinute(instant), timeline.axisStart, timeline.axisEnd,
        )
        val subject = resolveSubject(objectId, date) ?: return null
        val coords = subject.coordinates
        val altitude = ephemeris.altitudeOf(location, coords, cursor).value
        val azimuth = ephemeris.azimuthOf(location, coords, cursor).value
        val moonPosition = ephemeris.horizontalPosition(location, CelestialBody.MOON, cursor)
        val moonAltitudeDeg = moonPosition.altitude.value
        val separation = TimelineCursor.angularSeparationDeg(
            app.astroexposure.domain.ephemeris.HorizontalCoordinates(
                Degrees(altitude), Degrees(azimuth),
            ),
            moonPosition,
        )
        val arcSamples = sampleArcAltitudes(location, timeline, coords)
        val rawPhases = TimelineCursor.arcPhases(
            arcSamples, TimelineCursor.MIN_ALTITUDE_DEG, timeline.axisEnd,
        )
        // The dialog presents the night's windows, not the axis's
        // (ui-design §7.5, 0.8.4): the ≥20° run and the recommended
        // window are cut to the astronomical night — see [nightSpan]
        // for the shared span definition. The lane phases
        // (evaluateCursor) and the phase classification below keep
        // the uncut pass.
        val nightSpan = nightSpan(location, date)
        val phases = rawPhases?.let { raw ->
            nightSpan?.let { span -> TimelineCursor.arcPhasesWithin(raw, span) } ?: raw
        }
        val targetSky = SkyAssembly.compose(
            ephemeris, lightPollution, location, cursor, coords, manualBortle,
        )
        val tier = TimelineCursor.moonInterference(
            targetSky.moonlightMcdPerM2, targetSky.totalSky.baselineTotalMcdPerM2,
        )
        // The recommended interval does not apply to a radiant
        // (ui-design §7.8): its detail keeps the shootable window
        // and the peak/ZHR row, never a recommended window.
        val recommended = if (subject is TonightSubject.Radiant) {
            null
        } else {
            TimelineCursor.recommendedWindow(arcSamples, timeline.axisEnd)
                ?.let { raw ->
                    nightSpan?.let { span -> TimelineCursor.recommendedWithin(raw, span) } ?: raw
                }
        }
        // Horizon crossings for the detail dialog's folded section
        // and its below-horizon fact line: the same evaluation path
        // asks the ephemeris port for tonight's pass at a 0°
        // threshold — no second ephemeris route is opened for them.
        val horizonPass = ephemeris.targetPass(location, coords, date, Degrees(0.0))
        val peakSample = arcSamples.maxByOrNull { it.altitudeDeg }
        return TargetDetail(
            subject = subject,
            altitudeDeg = altitude,
            azimuthDeg = azimuth,
            phase = rawPhases?.let { TimelineCursor.phaseAt(it, cursor) },
            moonInterference = tier,
            arcIntervals = TimelineCursor.aboveThresholdIntervals(
                arcSamples, TimelineCursor.MIN_ALTITUDE_DEG, timeline.axisEnd,
            ),
            phases = phases,
            // The framing derivation runs off the subject's major
            // axis for every category (ui-design §7.8): catalogue
            // arcminutes for a deep-sky object, the editorial extent
            // for a constellation or viewpoint — the same §7.5
            // function and the same answer it has always given.
            framing = app.astroexposure.domain.camera.FullFrameFraming.advice(
                subject.sizeMajorDeg?.times(60.0),
            ),
            moonSeparationDeg = separation,
            moonUp = moonAltitudeDeg > 0.0,
            moonProximity = TimelineCursor.moonProximity(separation, moonAltitudeDeg, tier),
            recommended = recommended,
            recommendedState = recommended?.let {
                TimelineCursor.recommendedState(it, cursor)
            },
            recommendedMinutesUntil = recommended?.let {
                TimelineCursor.recommendedMinutesUntil(it, cursor)
            } ?: 0L,
            horizonRise = horizonPass?.risesAbove,
            horizonSet = horizonPass?.setsBelow,
            culmination = peakSample?.instant,
            culminationAltitudeDeg = peakSample?.altitudeDeg,
            meteorPeakDate = (subject as? TonightSubject.Radiant)?.let {
                ShowerDates.approxDate(it.shower.peakLon, date)
            },
            meteorZhr = (subject as? TonightSubject.Radiant)?.shower?.peakZhr,
        )
    }

    /** One target's altitude series across the axis at the timeline's 5-minute step. */
    private fun sampleArcAltitudes(
        location: GeoLocation,
        timeline: Timeline,
        coords: EquatorialCoordinates,
    ): List<TimelineCursor.AltitudeSample> =
        NightTimeline.sampleInstants(timeline.axisStart, timeline.axisEnd).map { t ->
            TimelineCursor.AltitudeSample(t, ephemeris.altitudeOf(location, coords, t).value)
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
        val samples = NightTimeline.sampleInstants(axisStart, axisEnd).map { t ->
            NightTimeline.Sample(
                instant = t,
                sunAltitudeDeg = ephemeris.horizontalPosition(location, CelestialBody.SUN, t).altitude.value,
                moonAltitudeDeg = ephemeris.horizontalPosition(location, CelestialBody.MOON, t).altitude.value,
                moonIllumination = ephemeris.moonIllumination(t),
            )
        }
        // Degradation without an astronomical night (ui-design §7.4):
        // the window band rows and the galactic-centre interval are
        // absent, and the explicit no-night note shows — but the solar
        // band segments still derive from the samples, so the sun band
        // strip keeps its daytime / twilight spans and simply has no
        // astronomical-night segment (0.8.8; before, the whole strip
        // went blank, which the spec's degradation line never said).
        val hasNight = night != null
        return Timeline(
            axisStart = axisStart,
            axisEnd = axisEnd,
            samples = samples,
            bandSegments = NightTimeline.bandSegments(samples, axisStart, axisEnd),
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
     * Moon is down or its interference tier is not STRONG — contribution
     * below one third of the baseline (natural + artificial) sky, the
     * design §5 rule-2 definition expressed through the shared tier, so
     * the result is explainable sample by sample. A run becomes a window
     * when it spans at least an hour; a run's end is the first sample
     * after it, or dawn for a run still open at the last sample.
     */
    private fun lowMoonlightWindows(
        location: GeoLocation,
        night: NightWindow?,
        skyAtDusk: SkyContext?,
    ): List<LowMoonlightWindow> {
        if (night == null || skyAtDusk == null || lightPollution == null) return emptyList()
        val baselineTotal = skyAtDusk.totalSky.baselineTotalMcdPerM2
        val gcTarget = app.astroexposure.domain.ephemeris.GalacticCenter.COORDINATES
        val instants = generateSequence(night.dusk) { it.plus(Duration.ofMinutes(30)) }
            .takeWhile { !it.isAfter(night.dawn) }
            .toList()
        val lows = instants.map { t ->
            val ctx = SkyAssembly.compose(
                ephemeris, lightPollution, location, t, gcTarget, null,
            )
            !ctx.moonUp ||
                TimelineCursor.moonInterference(ctx.moonlightMcdPerM2, baselineTotal) !=
                TimelineCursor.MoonInterference.STRONG
        }
        val windows = mutableListOf<LowMoonlightWindow>()
        for (run in NightTimeline.contiguousRuns(lows) { it }) {
            val start = instants[run.first]
            val end = if (run.last + 1 < instants.size) instants[run.last + 1] else night.dawn
            if (Duration.between(start, end).toMinutes() >= 60) {
                windows += LowMoonlightWindow(start, end)
            }
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

    /** The loaded board-subject universe plus its honest availability. */
    private class SubjectLoad(val subjects: List<TonightSubject>, val available: Boolean)

    /**
     * Deep-sky objects, constellation entries and wide-field
     * viewpoints as unified subjects (ui-design §7.8). Explicit
     * degradation, not a silent catch: ANY configured catalogue that
     * fails to load (malformed JSON, unparseable field, unknown
     * record type, missing resource) empties the universe and reports
     * [SubjectLoad.available] = false, so callers can say
     * "unavailable" instead of pretending tonight simply has no
     * targets. An unconfigured catalogue simply contributes nothing.
     * The outcome is computed per call — no state survives between
     * calls on this (singleton) use case.
     */
    private fun loadBoardSubjects(): SubjectLoad {
        val subjects = mutableListOf<TonightSubject>()
        return try {
            objectCatalog?.let { catalog ->
                subjects += catalog.allObjects().map { TonightSubject.DeepSky(it) }
            }
            constellationCatalog?.let { catalog ->
                subjects += catalog.constellations().map { TonightSubject.Constellation(it) }
            }
            wideFieldCatalog?.let { catalog ->
                subjects += catalog.viewpoints().map { TonightSubject.Viewpoint(it) }
            }
            SubjectLoad(subjects, available = true)
        } catch (e: Exception) {
            SubjectLoad(emptyList(), available = false)
        }
    }

    /** Resolve one subject by its namespaced id; null when unknown or a catalogue fails. */
    private fun resolveSubject(id: String, date: LocalDate): TonightSubject? = try {
        when {
            id.startsWith("constellation:") -> constellationCatalog
                ?.constellations()
                ?.firstOrNull { it.code == id.removePrefix("constellation:") }
                ?.let { TonightSubject.Constellation(it) }
            id.startsWith("viewpoint:") -> wideFieldCatalog
                ?.viewpoints()
                ?.firstOrNull { it.id == id.removePrefix("viewpoint:") }
                ?.let { TonightSubject.Viewpoint(it) }
            id.startsWith("shower:") -> showerCatalog
                ?.showers()
                ?.firstOrNull { it.iauCode == id.removePrefix("shower:") }
                ?.let { radiantSubject(it, date) }
            else -> objectCatalog
                ?.allObjects()
                ?.firstOrNull { it.id == id }
                ?.let { TonightSubject.DeepSky(it) }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * A shower's radiant subject at [date]: the catalogue radiant
     * moved by the daily drift times the signed days from peak — the
     * meteor use case's existing convention, shared so the lane, the
     * cursor set and the detail dialog always point at one radiant.
     */
    private fun radiantSubject(shower: MeteorShower, date: LocalDate): TonightSubject.Radiant {
        val days = ShowerDates.daysFromPeak(shower, date)
        return TonightSubject.Radiant(
            shower = shower,
            radiant = EquatorialCoordinates(
                shower.radiantRaHours + shower.driftRaPerDay * days,
                Degrees(shower.radiantDecDeg + shower.driftDecPerDay * days),
            ),
        )
    }

    /**
     * The presentation night span windows are cut to (ui-design
     * §7.5, 0.8.4): the astronomical night when there is one; with no
     * astronomical night, sunset → next sunrise; with neither, null
     * and the raw windows stand.
     */
    private fun nightSpan(location: GeoLocation, date: LocalDate): NightTimeline.Interval? {
        val night = ephemeris.astronomicalNight(location, date)
        if (night != null) return NightTimeline.Interval(night.dusk, night.dawn)
        val sunset = ephemeris.sunEvents(location, date).sunset
        val nextSunrise = ephemeris.sunEvents(location, date.plusDays(1)).sunrise
        return if (sunset != null && nextSunrise != null) {
            NightTimeline.Interval(sunset, nextSunrise)
        } else {
            null
        }
    }

    /** Total minutes the ≥20° runs [intervals] spend inside [span] (the whole axis when null). */
    private fun windowMinutesWithin(
        intervals: List<NightTimeline.Interval>,
        span: NightTimeline.Interval?,
    ): Long = intervals.sumOf { interval ->
        val start = if (span == null) interval.start else maxOf(interval.start, span.start)
        val end = if (span == null) interval.end else minOf(interval.end, span.end)
        if (end.isAfter(start)) Duration.between(start, end).toMinutes() else 0L
    }

    /**
     * The lane's gold frame: the recommended window cut to this
     * target's ≥20° arc window (the bar never draws the part of the
     * window that lies outside the arc). One implementation shared by
     * the whole-night lanes and the cursor-state targets.
     */
    private fun recommendedFrameFor(
        samples: List<TimelineCursor.AltitudeSample>,
        phases: TimelineCursor.ArcPhases?,
        axisEnd: Instant,
    ): NightTimeline.Interval? =
        TimelineCursor.recommendedWindow(samples, axisEnd)?.let { recommended ->
            val window = phases?.window ?: return@let null
            val start = maxOf(recommended.interval.start, window.start)
            val end = minOf(recommended.interval.end, window.end)
            if (end.isAfter(start)) NightTimeline.Interval(start, end) else null
        }

    /** Everything sampled about one subject for board and lane construction. */
    private class SubjectFacts(
        val samples: List<TimelineCursor.AltitudeSample>,
        val culmination: TimelineCursor.AltitudeSample?,
        val arcIntervals: List<NightTimeline.Interval>,
        val phases: TimelineCursor.ArcPhases?,
        val windowMinutes: Long,
        val recommended: TimelineCursor.RecommendedWindow?,
    )

    private fun subjectFacts(
        location: GeoLocation,
        timeline: Timeline,
        span: NightTimeline.Interval?,
        subject: TonightSubject,
    ): SubjectFacts {
        val samples = sampleArcAltitudes(location, timeline, subject.coordinates)
        val intervals = TimelineCursor.aboveThresholdIntervals(
            samples, TimelineCursor.MIN_ALTITUDE_DEG, timeline.axisEnd,
        )
        return SubjectFacts(
            samples = samples,
            culmination = TimelineCursor.culminationOf(samples),
            arcIntervals = intervals,
            phases = TimelineCursor.arcPhases(
                samples, TimelineCursor.MIN_ALTITUDE_DEG, timeline.axisEnd,
            ),
            windowMinutes = windowMinutesWithin(intervals, span),
            recommended = TimelineCursor.recommendedWindow(samples, timeline.axisEnd),
        )
    }

    private class BoardAssembly(
        val entries: List<ShortlistEntry>,
        val lanes: List<LaneEntry>,
        val available: Boolean,
    )

    /**
     * The cross-category tonight board (ui-design §7.8). The deep-sky
     * gate is the pre-0.9.0 rule, unchanged (catalogue magnitude
     * ≤ 6.5, culmination ≥ 40° via the target-pass port), evaluated
     * brightest-first with the scan stopping at the quota so the
     * ephemeris cost stays bounded; constellation and viewpoint
     * candidates are gated on their sampled facts. Gates, quotas and
     * the final order are decided by the pure [assembleBoard]; this
     * function only gathers facts and builds the rows and lanes from
     * its verdict, so the two can never disagree.
     */
    private fun tonightBoard(
        location: GeoLocation,
        date: LocalDate,
        timeline: Timeline,
    ): BoardAssembly {
        val load = loadBoardSubjects()
        if (!load.available) return BoardAssembly(emptyList(), emptyList(), available = false)
        val span = nightSpan(location, date)
        val spanMinutes = span?.let { Duration.between(it.start, it.end).toMinutes() }
        val factsById = mutableMapOf<String, SubjectFacts>()
        fun facts(subject: TonightSubject): SubjectFacts =
            factsById.getOrPut(subject.id) { subjectFacts(location, timeline, span, subject) }

        val candidates = mutableListOf<BoardCandidate>()
        // Constellations and viewpoints: every entry is a candidate;
        // the pure assembly applies their gates.
        for (subject in load.subjects) {
            if (subject.category == SubjectCategory.DEEP_SKY) continue
            val f = facts(subject)
            candidates += BoardCandidate(
                subject = subject,
                culmination = f.culmination?.instant ?: timeline.axisStart,
                culminationAltitudeDeg = f.culmination?.altitudeDeg ?: 0.0,
                windowMinutes = f.windowMinutes,
                magnitude = null,
                recommendedStart = f.recommended?.interval?.start,
            )
        }
        // Deep-sky: brightest-first pass scan, stopping at the quota
        // (the pre-0.9.0 bounded scan with the smaller 0.9.0 quota).
        val deepSkySubjects = load.subjects
            .filterIsInstance<TonightSubject.DeepSky>()
            .filter { (it.obj.magnitude ?: Double.MAX_VALUE) <= MAX_MAGNITUDE }
            .sortedBy { it.obj.magnitude }
        var deepSkyTaken = 0
        for (subject in deepSkySubjects) {
            if (deepSkyTaken >= QUOTA_DEEP_SKY) break
            val pass = ephemeris.targetPass(
                location, subject.coordinates, date, Degrees(MIN_CULMINATION_DEG),
            ) ?: continue
            if (pass.culminationAltitude.value < MIN_CULMINATION_DEG) continue
            deepSkyTaken++
            val f = facts(subject)
            candidates += BoardCandidate(
                subject = subject,
                culmination = f.culmination?.instant ?: pass.culmination,
                culminationAltitudeDeg = f.culmination?.altitudeDeg
                    ?: pass.culminationAltitude.value,
                windowMinutes = f.windowMinutes,
                magnitude = subject.obj.magnitude,
                recommendedStart = f.recommended?.interval?.start,
            )
        }

        val selected = assembleBoard(candidates)
        val entries = selected.map { candidate ->
            val f = facts(candidate.subject)
            val cut = if (span == null) {
                f.arcIntervals
            } else {
                f.arcIntervals.mapNotNull { interval ->
                    TimelineCursor.intersectInterval(interval, span)
                }
            }
            ShortlistEntry(
                subject = candidate.subject,
                culmination = candidate.culmination,
                culminationAltitudeDeg = candidate.culminationAltitudeDeg,
                shootableMinutes = candidate.windowMinutes,
                windowStart = cut.firstOrNull()?.start,
                windowEnd = cut.lastOrNull()?.end,
                spansWholeNight = spanMinutes != null &&
                    candidate.windowMinutes >= spanMinutes - WHOLE_NIGHT_TOLERANCE_MINUTES,
                recommended = f.recommended,
            )
        }
        val lanes = entries.map { entry ->
            val f = facts(entry.subject)
            LaneEntry(
                subject = entry.subject,
                altitudeSamples = f.samples,
                arcIntervals = f.arcIntervals,
                phases = f.phases,
                recommendedFrame = recommendedFrameFor(f.samples, f.phases, timeline.axisEnd),
                culmination = entry.culmination,
                culminationAltitudeDeg = entry.culminationAltitudeDeg,
            )
        }
        return BoardAssembly(entries, lanes, available = true)
    }

    /** Sampled facts for one qualifying radiant (see [radiantLaneData]). */
    private class RadiantLaneData(
        val subject: TonightSubject.Radiant,
        val samples: List<TimelineCursor.AltitudeSample>,
        val arcIntervals: List<NightTimeline.Interval>,
        val culmination: TimelineCursor.AltitudeSample,
    )

    /**
     * Tonight's qualifying radiants (ui-design §7.8): a shower earns
     * a lane only when ALL three hold — tonight is inside its active
     * period, its peak night falls within ±3 days (the same
     * membership the events section uses — no second window), and its
     * radiant's ≥20° window inside the astronomical night totals at
     * least an hour. A shower catalogue that fails to load yields no
     * lanes; the events section reports that failure separately.
     */
    private fun radiantLaneData(
        location: GeoLocation,
        date: LocalDate,
        timeline: Timeline,
    ): List<RadiantLaneData> {
        val catalog = showerCatalog ?: return emptyList()
        val showers = try {
            catalog.showers()
        } catch (e: Exception) {
            return emptyList()
        }
        val span = nightSpan(location, date)
        return showers.mapNotNull { shower ->
            if (!showerActiveOn(shower, date)) return@mapNotNull null
            if (kotlin.math.abs(ShowerDates.daysFromPeak(shower, date)) > PEAK_WINDOW_DAYS) {
                return@mapNotNull null
            }
            val subject = radiantSubject(shower, date)
            val samples = sampleArcAltitudes(location, timeline, subject.coordinates)
            val peak = TimelineCursor.culminationOf(samples) ?: return@mapNotNull null
            val intervals = TimelineCursor.aboveThresholdIntervals(
                samples, TimelineCursor.MIN_ALTITUDE_DEG, timeline.axisEnd,
            )
            if (windowMinutesWithin(intervals, span) < RADIANT_MIN_WINDOW_MINUTES) {
                return@mapNotNull null
            }
            RadiantLaneData(subject, samples, intervals, peak)
        }
    }

    /** The radiant lanes themselves, drawn after the board lanes. */
    private fun radiantLanes(
        location: GeoLocation,
        date: LocalDate,
        timeline: Timeline,
    ): List<LaneEntry> = radiantLaneData(location, date, timeline).map { data ->
        LaneEntry(
            subject = data.subject,
            altitudeSamples = data.samples,
            arcIntervals = data.arcIntervals,
            phases = null,
            recommendedFrame = null,
            culmination = data.culmination.instant,
            culminationAltitudeDeg = data.culmination.altitudeDeg,
            meteorPeakDate = ShowerDates.approxDate(data.subject.shower.peakLon, date),
            meteorZhr = data.subject.shower.peakZhr,
        )
    }

    companion object {
        const val MAX_MAGNITUDE = 6.5
        const val MIN_CULMINATION_DEG = 40.0
        /** Per-category board quotas (ui-design §7.8); unused slots never transfer. */
        const val QUOTA_DEEP_SKY = 5
        const val QUOTA_WIDE_FIELD = 3
        const val QUOTA_CONSTELLATION = 2
        const val CONSTELLATION_MIN_CULMINATION_DEG = 40.0
        const val CONSTELLATION_MIN_WINDOW_MINUTES = 120L
        const val VIEWPOINT_MIN_CULMINATION_DEG = 25.0
        const val VIEWPOINT_MIN_WINDOW_MINUTES = 60L
        const val RADIANT_MIN_WINDOW_MINUTES = 60L
        /** The radiant peak membership window, shared with the events section. */
        const val PEAK_WINDOW_DAYS = 3.0
        /** Sample-step slack in the whole-night span test (two 5-minute steps). */
        const val WHOLE_NIGHT_TOLERANCE_MINUTES = 10L
    }

}

/**
 * The moonrise instant the location card names on its moonlight line
 * (ui-design §7.2, 0.8.4): [moonrise] itself when the dusk sample had
 * the Moon below the horizon ([moonUpAtDusk] false — a null means
 * there was no sky sample to judge from) and the rise falls inside
 * the astronomical night; null otherwise. Pure and separate from
 * [TonightOverviewUseCase.Result] so the trigger is unit-testable
 * without assembling a whole overview.
 */
internal fun moonriseNoteInstant(
    moonUpAtDusk: Boolean?,
    moonrise: Instant?,
    night: NightWindow?,
): Instant? {
    if (moonUpAtDusk != false) return null
    val rise = moonrise ?: return null
    val window = night ?: return null
    return if (rise.isAfter(window.dusk) && rise.isBefore(window.dawn)) rise else null
}

/**
 * Whether [date] falls inside [shower]'s active period. The
 * catalogue speaks solar longitude only; both bounds resolve through
 * the same closest-date conversion the peak uses ([ShowerDates]),
 * and a period wrapping the year boundary (end before start after
 * anchoring) counts both sides of it. Pure, so the radiant lane's
 * first entry condition is unit-testable without an ephemeris.
 */
internal fun showerActiveOn(shower: MeteorShower, date: LocalDate): Boolean {
    val start = ShowerDates.approxDate(shower.activityStartLon, date)
    val end = ShowerDates.approxDate(shower.activityEndLon, date)
    return if (!end.isBefore(start)) {
        !date.isBefore(start) && !date.isAfter(end)
    } else {
        !date.isBefore(start) || !date.isAfter(end)
    }
}

/**
 * The sampled facts the board assembly decides on (ui-design §7.8):
 * one candidate per subject, carrying only what the gates, quotas
 * and ordering read. Deep-sky candidates arrive pre-gated by the
 * caller's target-pass scan (their [magnitude] is the in-category
 * order key); constellation and viewpoint candidates are gated here.
 */
internal class BoardCandidate(
    val subject: TonightOverviewUseCase.TonightSubject,
    val culmination: Instant,
    val culminationAltitudeDeg: Double,
    val windowMinutes: Long,
    val magnitude: Double?,
    val recommendedStart: Instant?,
)

/**
 * The pure board assembly (ui-design §7.8): per-category gates and
 * quotas with NO cross-category transfer (an under-filled category
 * leaves its slots empty — the board may be shorter than 10), then
 * the board order: recommended-interval start ascending, ties by
 * culmination instant, then subject id (fully deterministic).
 * Relative-best entries order by their relative-best span's start
 * like any other — their recommended window is simply the one the
 * shared definition produced.
 */
internal fun assembleBoard(candidates: List<BoardCandidate>): List<BoardCandidate> {
    val deepSky = candidates
        .filter { it.subject.category == TonightOverviewUseCase.SubjectCategory.DEEP_SKY }
        .sortedBy { it.magnitude ?: Double.MAX_VALUE }
        .take(TonightOverviewUseCase.QUOTA_DEEP_SKY)
    val constellations = candidates
        .filter {
            it.subject.category == TonightOverviewUseCase.SubjectCategory.CONSTELLATION &&
                it.culminationAltitudeDeg >=
                TonightOverviewUseCase.CONSTELLATION_MIN_CULMINATION_DEG &&
                it.windowMinutes >= TonightOverviewUseCase.CONSTELLATION_MIN_WINDOW_MINUTES
        }
        .sortedByDescending { it.culminationAltitudeDeg }
        .take(TonightOverviewUseCase.QUOTA_CONSTELLATION)
    val viewpoints = candidates
        .filter {
            it.subject.category == TonightOverviewUseCase.SubjectCategory.WIDE_FIELD &&
                it.culminationAltitudeDeg >=
                TonightOverviewUseCase.VIEWPOINT_MIN_CULMINATION_DEG &&
                it.windowMinutes >= TonightOverviewUseCase.VIEWPOINT_MIN_WINDOW_MINUTES
        }
        .sortedByDescending { it.culminationAltitudeDeg }
        .take(TonightOverviewUseCase.QUOTA_WIDE_FIELD)
    return (deepSky + constellations + viewpoints).sortedWith(
        compareBy(
            { it.recommendedStart ?: it.culmination },
            { it.culmination },
            { it.subject.id },
        ),
    )
}
