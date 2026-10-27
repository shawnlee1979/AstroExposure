package app.astroexposure.domain.ephemeris

import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.units.Degrees
import java.time.Instant
import java.time.LocalDate

/**
 * Port for all astronomical ephemeris quantities (DEVELOPMENT-RULES.md B.1).
 * The interface speaks only domain types: angles in degrees, times as
 * [Instant]/[LocalDate]. The current implementation is the Astronomy Engine
 * adapter in :data; any replacement must pass the same contract tests.
 *
 * All functions are pure: the caller supplies every instant and location
 * explicitly (D.1/D.2 — no hidden clock or GPS reads).
 */
interface EphemerisProvider {

    /**
     * The astronomical night that begins on the evening of [date] in the
     * site's civil timezone: dusk = end of astronomical twilight on [date],
     * dawn = start of astronomical twilight on the following morning.
     * Returns null when there is no astronomical night (polar day).
     */
    fun astronomicalNight(location: GeoLocation, date: LocalDate): NightWindow?

    /** Moon illumination fraction in [0, 1] at [instant] (1 = full moon). */
    fun moonIllumination(instant: Instant): Double

    /**
     * Moonrise and moonset on the civil [date] at [location]. Either field is
     * null when the event does not occur on that date (the Moon does not rise
     * or set every day).
     */
    fun moonRiseSet(location: GeoLocation, date: LocalDate): MoonRiseSet

    /** Refraction-corrected altitude of [target] at [instant], degrees. */
    fun altitudeOf(location: GeoLocation, target: EquatorialCoordinates, instant: Instant): Degrees

    /**
     * The window during the night of [date] in which the galactic centre
     * (RA 17h45m, Dec -29.0°, design §4.1) is above [minAltitude].
     * Returns null when the centre never reaches [minAltitude] that night.
     */
    fun galacticCenterWindow(
        location: GeoLocation,
        date: LocalDate,
        minAltitude: Degrees = Degrees(20.0),
    ): GalacticCenterWindow?
}

/** Dusk (astronomical twilight end) and dawn (astronomical twilight begin). */
data class NightWindow(val dusk: Instant, val dawn: Instant)

data class MoonRiseSet(val moonrise: Instant?, val moonset: Instant?)

/** Equatorial coordinates: right ascension in HOURS, declination in degrees. */
data class EquatorialCoordinates(val raHours: Double, val declination: Degrees)

data class GalacticCenterWindow(
    /** When the centre rises above the threshold; null if already above at dusk. */
    val risesAbove: Instant?,
    val culmination: Instant,
    val culminationAltitude: Degrees,
    /** When the centre drops below the threshold; null if still above at dawn. */
    val setsBelow: Instant?,
)
