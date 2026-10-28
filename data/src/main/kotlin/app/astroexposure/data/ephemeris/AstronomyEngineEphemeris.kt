package app.astroexposure.data.ephemeris

import app.astroexposure.domain.ephemeris.CelestialBody
import app.astroexposure.domain.ephemeris.EclipseEvent
import app.astroexposure.domain.ephemeris.EclipseKind as DomainEclipseKind
import app.astroexposure.domain.ephemeris.EphemerisProvider
import app.astroexposure.domain.ephemeris.EquatorialCoordinates
import app.astroexposure.domain.ephemeris.GalacticCenterWindow
import app.astroexposure.domain.ephemeris.HorizontalCoordinates
import app.astroexposure.domain.ephemeris.MoonRiseSet
import app.astroexposure.domain.ephemeris.NightWindow
import app.astroexposure.domain.ephemeris.SunEvents
import app.astroexposure.domain.ephemeris.TargetPass
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.units.Degrees
import io.github.cosinekitty.astronomy.Aberration
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.Direction
import io.github.cosinekitty.astronomy.EclipseKind as EngineEclipseKind
import io.github.cosinekitty.astronomy.EquatorEpoch
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Refraction
import io.github.cosinekitty.astronomy.Time as AstroTime
import io.github.cosinekitty.astronomy.defineStar
import io.github.cosinekitty.astronomy.equator
import io.github.cosinekitty.astronomy.globalSolarEclipsesAfter
import io.github.cosinekitty.astronomy.horizon
import io.github.cosinekitty.astronomy.illumination
import io.github.cosinekitty.astronomy.lunarEclipsesAfter
import io.github.cosinekitty.astronomy.moonPhase
import io.github.cosinekitty.astronomy.searchAltitude
import io.github.cosinekitty.astronomy.searchHourAngle
import io.github.cosinekitty.astronomy.searchRiseSet
import java.time.Instant
import java.time.LocalDate

/**
 * [EphemerisProvider] implementation backed by the vendored Astronomy Engine
 * (see data/VENDOR.md). ALL contact with the third-party API happens in this
 * file: engine types are converted to domain types at this boundary and never
 * leak past it (DEVELOPMENT-RULES.md B.2).
 *
 * Engine time scale: `ut` = days since 2000-01-01 12:00 UTC, hence
 * ut = epochMilli / 86_400_000 - 10_957.5; the inverse is the engine's own
 * `toMillisecondsSince1970()`.
 */
class AstronomyEngineEphemeris : EphemerisProvider {

    private val starLock = Any()

    private fun AstroTime.toInstant(): Instant = Instant.ofEpochMilli(toMillisecondsSince1970())

    private fun instantToAstroTime(instant: Instant): AstroTime =
        AstroTime(instant.toEpochMilli() / 86_400_000.0 - 10_957.5)

    private fun observerOf(location: GeoLocation): Observer =
        Observer(location.latitude.value, location.longitude.value, location.heightMeters)

    private fun localStartOfDay(location: GeoLocation, date: LocalDate): AstroTime =
        instantToAstroTime(date.atStartOfDay(location.zone).toInstant())

    private fun localNoon(location: GeoLocation, date: LocalDate): AstroTime =
        instantToAstroTime(date.atTime(12, 0).atZone(location.zone).toInstant())

    override fun astronomicalNight(location: GeoLocation, date: LocalDate): NightWindow? {
        val observer = observerOf(location)
        // Search from local noon so the SET crossing found is this date's dusk.
        val dusk = searchAltitude(
            Body.Sun, observer, Direction.Set, localNoon(location, date), 2.0, ASTRONOMICAL_TWILIGHT_DEG,
        ) ?: return null
        val dawn = searchAltitude(
            Body.Sun, observer, Direction.Rise, dusk, 2.0, ASTRONOMICAL_TWILIGHT_DEG,
        ) ?: return null
        return NightWindow(dusk = dusk.toInstant(), dawn = dawn.toInstant())
    }

    override fun moonIllumination(instant: Instant): Double =
        illumination(Body.Moon, instantToAstroTime(instant)).phaseFraction.coerceIn(0.0, 1.0)

    override fun moonPhaseAngle(instant: Instant): Degrees =
        // The engine's moonPhase is exactly the port's θ: the Moon's
        // geocentric ecliptic longitude relative to the Sun's, [0, 360).
        Degrees(moonPhase(instantToAstroTime(instant)))

    override fun moonRiseSet(location: GeoLocation, date: LocalDate): MoonRiseSet {
        val observer = observerOf(location)
        val start = localStartOfDay(location, date)
        val rise = searchRiseSet(Body.Moon, observer, Direction.Rise, start, 1.0)
        val set = searchRiseSet(Body.Moon, observer, Direction.Set, start, 1.0)
        return MoonRiseSet(moonrise = rise?.toInstant(), moonset = set?.toInstant())
    }

    override fun altitudeOf(
        location: GeoLocation,
        target: EquatorialCoordinates,
        instant: Instant,
    ): Degrees = Degrees(horizontalOf(location, target, instant).altitude)

    override fun azimuthOf(
        location: GeoLocation,
        target: EquatorialCoordinates,
        instant: Instant,
    ): Degrees = Degrees(horizontalOf(location, target, instant).azimuth)

    /** Topocentric horizontal coordinates of an arbitrary target (shared by altitudeOf / azimuthOf). */
    private fun horizontalOf(
        location: GeoLocation,
        target: EquatorialCoordinates,
        instant: Instant,
    ): io.github.cosinekitty.astronomy.Topocentric {
        val observer = observerOf(location)
        val time = instantToAstroTime(instant)
        // defineStar writes engine-global state for Body.Star1; guard it and
        // immediately derive the of-date coordinates we actually use.
        val eq = synchronized(starLock) {
            defineStar(Body.Star1, target.raHours, target.declination.value, STAR_DISTANCE_LY)
            equator(Body.Star1, time, observer, EquatorEpoch.OfDate, Aberration.Corrected)
        }
        return horizon(time, observer, eq.ra, eq.dec, Refraction.Normal)
    }

    override fun horizontalPosition(
        location: GeoLocation,
        body: CelestialBody,
        instant: Instant,
    ): HorizontalCoordinates {
        val engineBody = when (body) {
            CelestialBody.SUN -> Body.Sun
            CelestialBody.MOON -> Body.Moon
        }
        val observer = observerOf(location)
        val time = instantToAstroTime(instant)
        val eq = equator(engineBody, time, observer, EquatorEpoch.OfDate, Aberration.Corrected)
        val topocentric = horizon(time, observer, eq.ra, eq.dec, Refraction.Normal)
        // Engine azimuth is measured from north, eastward (0=N, 90=E) — the
        // same convention the domain port documents, so it passes through.
        return HorizontalCoordinates(
            altitude = Degrees(topocentric.altitude),
            azimuth = Degrees(topocentric.azimuth),
        )
    }

    override fun sunEvents(location: GeoLocation, date: LocalDate): SunEvents {
        val observer = observerOf(location)
        val startOfDay = localStartOfDay(location, date)
        // Sunrise/sunset: the Sun's centre crossing -0.583 degrees (disc
        // radius + mean refraction convention used for rise/set times).
        val sunrise = searchAltitude(
            Body.Sun, observer, Direction.Rise, startOfDay, 1.0, SUN_DISC_ALTITUDE_DEG,
        )?.toInstant()
        val sunset = searchAltitude(
            Body.Sun, observer, Direction.Set, startOfDay, 1.0, SUN_DISC_ALTITUDE_DEG,
        )?.toInstant()
        // Dusk ends are searched from local noon so the SET crossing found is
        // this date's evening, mirroring astronomicalNight.
        val noon = localNoon(location, date)
        val civilDuskEnd = searchAltitude(
            Body.Sun, observer, Direction.Set, noon, 1.0, CIVIL_TWILIGHT_DEG,
        )?.toInstant()
        val nauticalDuskEnd = searchAltitude(
            Body.Sun, observer, Direction.Set, noon, 1.0, NAUTICAL_TWILIGHT_DEG,
        )?.toInstant()
        return SunEvents(
            sunrise = sunrise,
            sunset = sunset,
            civilDuskEnd = civilDuskEnd,
            nauticalDuskEnd = nauticalDuskEnd,
        )
    }

    override fun targetPass(
        location: GeoLocation,
        target: EquatorialCoordinates,
        date: LocalDate,
        threshold: Degrees,
    ): TargetPass? {
        val night = astronomicalNight(location, date) ?: return null
        val observer = observerOf(location)
        val dusk = instantToAstroTime(night.dusk)
        // The culmination that belongs to this pass is the LAST one at or
        // before dawn — searched backwards from dawn. Searching forward
        // from dusk instead returns the NEXT day's culmination whenever
        // the target culminated before dusk (the galactic centre at
        // mid-northern latitudes from late August into autumn), which
        // stretched its window band across the whole axis and reported
        // tomorrow's peak as tonight's.
        val culmination = synchronized(starLock) {
            defineStar(Body.Star1, target.raHours, target.declination.value, STAR_DISTANCE_LY)
            searchHourAngle(Body.Star1, observer, 0.0, instantToAstroTime(night.dawn), -1).time
        }
        val culminationAltitude = altitudeOf(location, target, culmination.toInstant())
        if (culminationAltitude.value < threshold.value) return null
        val alreadyAboveAtDusk = altitudeOf(location, target, night.dusk).value >= threshold.value
        if (culmination.toInstant().isBefore(night.dusk) && !alreadyAboveAtDusk) {
            // Culminated before dusk and already below the threshold by
            // dusk: the above-threshold part of this pass ended before
            // the night began (altitude only falls after culmination,
            // and the next culmination is after dawn).
            return null
        }
        val risesAbove = if (alreadyAboveAtDusk) {
            null
        } else {
            synchronized(starLock) {
                defineStar(Body.Star1, target.raHours, target.declination.value, STAR_DISTANCE_LY)
                searchAltitude(Body.Star1, observer, Direction.Rise, dusk, 1.0, threshold.value)
            }?.toInstant()
        }
        // For a pre-dusk culmination the set search starts at dusk (the
        // pass is already above the threshold then); otherwise it starts
        // at the culmination as before.
        val setsBelowRaw = synchronized(starLock) {
            defineStar(Body.Star1, target.raHours, target.declination.value, STAR_DISTANCE_LY)
            searchAltitude(
                Body.Star1, observer, Direction.Set,
                if (alreadyAboveAtDusk) dusk else culmination, 1.0, threshold.value,
            )
        }?.toInstant()
        val setsBelow = setsBelowRaw?.takeIf { !it.isAfter(night.dawn) }
        return TargetPass(
            risesAbove = risesAbove,
            culmination = culmination.toInstant(),
            culminationAltitude = culminationAltitude,
            setsBelow = setsBelow,
        )
    }

    override fun galacticCenterWindow(
        location: GeoLocation,
        date: LocalDate,
        minAltitude: Degrees,
    ): GalacticCenterWindow? {
        // The galactic centre is a fixed target (design section 4.1); its
        // window is exactly a target pass at the requested threshold.
        val pass = targetPass(location, GALACTIC_CENTER, date, minAltitude) ?: return null
        return GalacticCenterWindow(
            risesAbove = pass.risesAbove,
            culmination = pass.culmination,
            culminationAltitude = pass.culminationAltitude,
            setsBelow = pass.setsBelow,
        )
    }

    override fun eclipsesBetween(from: Instant, to: Instant): List<EclipseEvent> {
        if (!from.isBefore(to)) return emptyList()
        val start = instantToAstroTime(from)
        // The engine sequences are unbounded; takeWhile on the peak instant
        // bounds them to [from, to). Peaks are filtered to >= from because a
        // search started at `from` can only find later events, but the
        // half-open interval is stated explicitly here for clarity.
        val solar = globalSolarEclipsesAfter(start)
            .takeWhile { it.peak.toInstant().isBefore(to) }
            .map { info ->
                EclipseEvent(
                    kind = DomainEclipseKind.SOLAR,
                    type = eclipseTypeString(info.kind),
                    peak = info.peak.toInstant(),
                    // The engine leaves obscuration undefined (NaN) for
                    // partial solar eclipses, where no location-free peak
                    // obscuration exists; report 0.0 for that case rather
                    // than leaking NaN across the port.
                    obscuration = if (info.obscuration.isFinite()) {
                        info.obscuration.coerceIn(0.0, 1.0)
                    } else {
                        0.0
                    },
                )
            }
        val lunar = lunarEclipsesAfter(start)
            .takeWhile { it.peak.toInstant().isBefore(to) }
            .map { info ->
                EclipseEvent(
                    kind = DomainEclipseKind.LUNAR,
                    type = eclipseTypeString(info.kind),
                    peak = info.peak.toInstant(),
                    obscuration = info.obscuration.coerceIn(0.0, 1.0),
                )
            }
        return (solar + lunar)
            .filter { !it.peak.isBefore(from) && it.peak.isBefore(to) }
            .sortedBy { it.peak }
            .toList()
    }

    /** Engine eclipse classification -> the port's uppercase type string. */
    private fun eclipseTypeString(kind: EngineEclipseKind): String = when (kind) {
        EngineEclipseKind.Total -> "TOTAL"
        EngineEclipseKind.Annular -> "ANNULAR"
        EngineEclipseKind.Partial -> "PARTIAL"
        EngineEclipseKind.Penumbral -> "PENUMBRAL"
    }

    private companion object {
        /** Astronomical twilight: sun 18° below the horizon (design §4.1). */
        const val ASTRONOMICAL_TWILIGHT_DEG = -18.0

        /** Civil twilight: sun 6° below the horizon (design §4.1). */
        const val CIVIL_TWILIGHT_DEG = -6.0

        /** Nautical twilight: sun 12° below the horizon (design §4.1). */
        const val NAUTICAL_TWILIGHT_DEG = -12.0

        /** Sun-centre altitude at rise/set (disc radius + refraction convention). */
        const val SUN_DISC_ALTITUDE_DEG = -0.583

        /** Galactic centre: the shared domain constant (design §4.1). */
        val GALACTIC_CENTER = app.astroexposure.domain.ephemeris.GalacticCenter.COORDINATES

        /** Arbitrary catalogue distance for defined stars; only direction matters. */
        const val STAR_DISTANCE_LY = 100.0
    }
}
