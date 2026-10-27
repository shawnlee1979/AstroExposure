package app.astroexposure.usecase

import app.astroexposure.domain.catalog.DeepSkyObject
import app.astroexposure.domain.catalog.EclipseCatalog
import app.astroexposure.domain.catalog.EclipseRecord
import app.astroexposure.domain.catalog.LightPollutionSource
import app.astroexposure.domain.catalog.MeteorShower
import app.astroexposure.domain.catalog.MeteorShowerCatalog
import app.astroexposure.domain.catalog.ObjectCatalog
import app.astroexposure.domain.ephemeris.EphemerisProvider
import app.astroexposure.domain.ephemeris.EquatorialCoordinates
import app.astroexposure.domain.ephemeris.GalacticCenterWindow
import app.astroexposure.domain.ephemeris.NightWindow
import app.astroexposure.domain.ephemeris.SunEvents
import app.astroexposure.domain.ephemeris.TargetPass
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.sky.SkyBrightness
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
        val recommendations: List<RecommendedObject>,
        val moments: List<Moment>,
    )

    fun execute(location: GeoLocation, date: LocalDate, manualBortle: Int? = null): Result {
        val night = ephemeris.astronomicalNight(location, date)
        val sun = ephemeris.sunEvents(location, date)
        val riseSet = ephemeris.moonRiseSet(location, date)
        val dusk = night?.dusk ?: date.atTime(12, 0).atZone(location.zone).toInstant()
        val illumination = ephemeris.moonIllumination(dusk)
        val gc = ephemeris.galacticCenterWindow(location, date)

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
            ephemeris.sunEvents(location, date.plusDays(1)).sunrise?.let {
                add(Moment(it, MomentKind.SUNRISE))
            }
        }.sortedBy { it.instant }

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
            events = events(date),
            recommendations = recommendations(location, date),
            moments = moments,
        )
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

    /** Eclipses and shower peaks whose dates fall within ±3 days (design §6.4). */
    private fun events(date: LocalDate): List<Event> {
        val from = date.minusDays(3)
        val to = date.plusDays(3)
        val eclipses = eclipseCatalog
            ?.eclipses(from.year, to.year)
            ?.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
            ?.map { Event.Eclipse(it) }
            ?: emptyList()
        val showers = showerCatalog
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
        return (eclipses + showers).sortedBy {
            when (it) {
                is Event.Eclipse -> it.record.date
                is Event.Shower -> it.approxPeakDate
            }
        }
    }

    /**
     * Static shortlist (design §6.4): catalogue magnitude ≤ 6.5, tonight's
     * pass reaching ≥ 40° culmination, brightest first, at most 10. Passes
     * are evaluated brightest-first and the scan stops once 10 qualify, so
     * the ephemeris cost stays bounded.
     */
    private fun recommendations(location: GeoLocation, date: LocalDate): List<RecommendedObject> {
        val catalog = objectCatalog ?: return emptyList()
        val candidates = catalog.allObjects()
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
        return out
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
