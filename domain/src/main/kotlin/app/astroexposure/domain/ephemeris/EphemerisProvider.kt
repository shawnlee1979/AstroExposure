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

    // --- Phase-2 additions (G.4: members added, none removed) ---

    /** Horizontal position (altitude + azimuth, degrees) of the Sun or Moon at [instant]. */
    fun horizontalPosition(location: GeoLocation, body: CelestialBody, instant: Instant): HorizontalCoordinates

    /**
     * Sun events for the civil [date] at [location]: sunrise, sunset and the
     * ends of civil (-6°) and nautical (-12°) dusk. Any field is null when the
     * event does not occur on that date (polar day/night).
     */
    fun sunEvents(location: GeoLocation, date: LocalDate): SunEvents

    /**
     * Passage of an arbitrary [target] during the night of [date], with the
     * same semantics as [galacticCenterWindow] but for any coordinates and a
     * default threshold of 30° (design §7.2 key points). Returns null when
     * the target never reaches [threshold] that night.
     */
    fun targetPass(
        location: GeoLocation,
        target: EquatorialCoordinates,
        date: LocalDate,
        threshold: Degrees = Degrees(30.0),
    ): TargetPass?

    /**
     * Eclipse events with peak in [[from], [to]) — event-level forecasts only
     * (design §5.4.2: no local contact times at this layer).
     */
    fun eclipsesBetween(from: Instant, to: Instant): List<EclipseEvent>

    // --- 0.4.1 addition (G.4: member added, none removed) ---

    /**
     * Moon phase angle θ at [instant]: the Moon's geocentric ecliptic
     * longitude minus the Sun's, in [0°, 360°) (0° = new moon,
     * 90° = first quarter, 180° = full moon, 270° = last quarter).
     * This is the input of the phase-name classification in
     * `MoonPhaseNames` (ui-design §7.4).
     */
    fun moonPhaseAngle(instant: Instant): Degrees
}

/** Bodies whose horizontal position the port can report. */
enum class CelestialBody { SUN, MOON }

/** Altitude and azimuth in degrees; azimuth measured from north, eastward. */
data class HorizontalCoordinates(val altitude: Degrees, val azimuth: Degrees)

data class SunEvents(
    val sunrise: Instant?,
    val sunset: Instant?,
    val civilDuskEnd: Instant?,
    val nauticalDuskEnd: Instant?,
)

/** A target's passage above a threshold during one night (design §7.2). */
data class TargetPass(
    /** When the target rises above the threshold; null if already above at dusk. */
    val risesAbove: Instant?,
    val culmination: Instant,
    val culminationAltitude: Degrees,
    /** When the target drops below the threshold; null if still above at dawn. */
    val setsBelow: Instant?,
)

/** Which body is eclipsed, at the event level of the catalogue (design §6.1). */
enum class EclipseKind { SOLAR, LUNAR }

/**
 * One eclipse event. [type] is the engine/catalogue classification string
 * (e.g. "Total", "Annular", "Partial", "Penumbral"); [obscuration] is the
 * peak obscured fraction of the eclipsed body's disc in [0, 1].
 */
data class EclipseEvent(
    val kind: EclipseKind,
    val type: String,
    val peak: Instant,
    val obscuration: Double,
)

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
