package app.astroexposure.domain.sky

import app.astroexposure.domain.units.Degrees

/**
 * Moon phase names by phase angle (ui-design §7.4, the stored
 * definition for this classification): θ is the Moon's geocentric
 * ecliptic longitude minus the Sun's, in [0°, 360°), as exposed by
 * `EphemerisProvider.moonPhaseAngle`. The circle is split into eight
 * sectors of 45°, one per name, mutually exclusive with no gaps; each
 * sector includes its lower bound and excludes its upper bound:
 *
 *   New Moon        [337.5, 360) ∪ [0, 22.5)
 *   Waxing Crescent [22.5, 67.5)
 *   First Quarter   [67.5, 112.5)
 *   Waxing Gibbous  [112.5, 157.5)
 *   Full Moon       [157.5, 202.5)
 *   Waning Gibbous  [202.5, 247.5)
 *   Last Quarter    [247.5, 292.5)
 *   Waning Crescent [292.5, 337.5)
 *
 * This θ is the Sun–Moon longitude difference; it is NOT the
 * Krisciunas & Schaefer phase angle in [MoonlightModel] (that one is
 * derived from the illumination fraction and spans only [0°, 180°]).
 */
enum class MoonPhaseName {
    NEW_MOON, WAXING_CRESCENT, FIRST_QUARTER, WAXING_GIBBOUS,
    FULL_MOON, WANING_GIBBOUS, LAST_QUARTER, WANING_CRESCENT,
}

object MoonPhaseNames {

    /** Sector width: 360° / 8 names (definition above). */
    const val SECTOR_DEG = 45.0

    /** Half a sector: the New Moon sector is centred on θ = 0°. */
    const val HALF_SECTOR_DEG = 22.5

    /** The phase name for a phase angle from the ephemeris port. */
    fun of(phaseAngle: Degrees): MoonPhaseName = ofDegrees(phaseAngle.value)

    /** The phase name for [thetaDeg] in degrees; any real value is normalised into [0, 360). */
    fun ofDegrees(thetaDeg: Double): MoonPhaseName {
        val theta = ((thetaDeg % 360.0) + 360.0) % 360.0
        // Sectors are [SECTOR_DEG] wide, centred on the cardinal names
        // (the New Moon sector straddles θ = 0°), and the enum is
        // declared in phase order — so the sector index counts whole
        // sectors from the half-sector offset. Every sector boundary is
        // a multiple of [HALF_SECTOR_DEG], an exact binary fraction, so
        // the division lands exactly on the boundary values.
        val index = ((theta + HALF_SECTOR_DEG) / SECTOR_DEG).toInt() % MoonPhaseName.entries.size
        return MoonPhaseName.entries[index]
    }
}
