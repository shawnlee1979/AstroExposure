package app.astroexposure.domain.geo

import app.astroexposure.domain.units.Degrees
import java.time.ZoneId

/**
 * An observing site. Longitude is degrees EAST of Greenwich (negative = west),
 * matching the convention of the ephemeris port. The civil [zone] is part of
 * the value because "the night of a date" is defined in local civil time;
 * callers pass it explicitly instead of the domain guessing it from longitude.
 */
data class GeoLocation(
    val latitude: Degrees,
    val longitude: Degrees,
    val heightMeters: Double = 0.0,
    val zone: ZoneId,
    /** Human-readable label shown in the UI (e.g. the built-in sample site). */
    val label: String? = null,
)
