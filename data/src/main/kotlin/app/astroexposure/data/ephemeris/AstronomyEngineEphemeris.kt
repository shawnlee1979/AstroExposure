package app.astroexposure.data.ephemeris

import app.astroexposure.domain.ephemeris.EphemerisProvider
import app.astroexposure.domain.ephemeris.EquatorialCoordinates
import app.astroexposure.domain.ephemeris.GalacticCenterWindow
import app.astroexposure.domain.ephemeris.MoonRiseSet
import app.astroexposure.domain.ephemeris.NightWindow
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.units.Degrees
import io.github.cosinekitty.astronomy.Aberration
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.Direction
import io.github.cosinekitty.astronomy.EquatorEpoch
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Refraction
import io.github.cosinekitty.astronomy.Time as AstroTime
import io.github.cosinekitty.astronomy.defineStar
import io.github.cosinekitty.astronomy.equator
import io.github.cosinekitty.astronomy.horizon
import io.github.cosinekitty.astronomy.illumination
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
    ): Degrees {
        val observer = observerOf(location)
        val time = instantToAstroTime(instant)
        // defineStar writes engine-global state for Body.Star1; guard it and
        // immediately derive the of-date coordinates we actually use.
        val eq = synchronized(starLock) {
            defineStar(Body.Star1, target.raHours, target.declination.value, STAR_DISTANCE_LY)
            equator(Body.Star1, time, observer, EquatorEpoch.OfDate, Aberration.Corrected)
        }
        val topocentric = horizon(time, observer, eq.ra, eq.dec, Refraction.Normal)
        return Degrees(topocentric.altitude)
    }

    override fun galacticCenterWindow(
        location: GeoLocation,
        date: LocalDate,
        minAltitude: Degrees,
    ): GalacticCenterWindow? {
        val night = astronomicalNight(location, date) ?: return null
        val observer = observerOf(location)
        val dusk = instantToAstroTime(night.dusk)
        val culmination = synchronized(starLock) {
            defineStar(Body.Star1, GC_RA_HOURS, GC_DEC_DEG, STAR_DISTANCE_LY)
            searchHourAngle(Body.Star1, observer, 0.0, dusk, +1).time
        }
        val culminationAltitude = altitudeOf(location, GALACTIC_CENTER, culmination.toInstant())
        if (culminationAltitude.value < minAltitude.value) return null
        val alreadyAboveAtDusk = altitudeOf(location, GALACTIC_CENTER, night.dusk).value >= minAltitude.value
        val risesAbove = if (alreadyAboveAtDusk) {
            null
        } else {
            synchronized(starLock) {
                defineStar(Body.Star1, GC_RA_HOURS, GC_DEC_DEG, STAR_DISTANCE_LY)
                searchAltitude(Body.Star1, observer, Direction.Rise, dusk, 1.0, minAltitude.value)
            }?.toInstant()
        }
        val setsBelowRaw = synchronized(starLock) {
            defineStar(Body.Star1, GC_RA_HOURS, GC_DEC_DEG, STAR_DISTANCE_LY)
            searchAltitude(Body.Star1, observer, Direction.Set, culmination, 1.0, minAltitude.value)
        }?.toInstant()
        val setsBelow = setsBelowRaw?.takeIf { !it.isAfter(night.dawn) }
        return GalacticCenterWindow(
            risesAbove = risesAbove,
            culmination = culmination.toInstant(),
            culminationAltitude = culminationAltitude,
            setsBelow = setsBelow,
        )
    }

    private companion object {
        /** Astronomical twilight: sun 18° below the horizon (design §4.1). */
        const val ASTRONOMICAL_TWILIGHT_DEG = -18.0

        /** Galactic centre coordinates (design §4.1): RA 17h45m, Dec -29.0°. */
        const val GC_RA_HOURS = 17.75
        const val GC_DEC_DEG = -29.0
        val GALACTIC_CENTER = EquatorialCoordinates(GC_RA_HOURS, Degrees(GC_DEC_DEG))

        /** Arbitrary catalogue distance for defined stars; only direction matters. */
        const val STAR_DISTANCE_LY = 100.0
    }
}
