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
        return when {
            theta >= 337.5 || theta < 22.5 -> MoonPhaseName.NEW_MOON
            theta < 67.5 -> MoonPhaseName.WAXING_CRESCENT
            theta < 112.5 -> MoonPhaseName.FIRST_QUARTER
            theta < 157.5 -> MoonPhaseName.WAXING_GIBBOUS
            theta < 202.5 -> MoonPhaseName.FULL_MOON
            theta < 247.5 -> MoonPhaseName.WANING_GIBBOUS
            theta < 292.5 -> MoonPhaseName.LAST_QUARTER
            else -> MoonPhaseName.WANING_CRESCENT
        }
    }
}
