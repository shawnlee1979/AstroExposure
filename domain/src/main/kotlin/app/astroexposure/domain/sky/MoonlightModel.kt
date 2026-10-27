package app.astroexposure.domain.sky

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.pow

/**
 * Moonlight contribution to sky brightness — a simplified implementation of
 * the Krisciunas & Schaefer (1991, PASP 103, 1033) V-band model, following
 * the formulation as reproduced in ING Technical Note 127:
 *
 *   B_moon = I0(phi) * f(rho) * 10^(-0.4*k*X_moon) * (1 - 10^(-0.4*k*X_target))
 *
 * - phi: lunar phase angle (degrees), recovered from the illumination
 *   fraction via cos(phi) = 2*illumination - 1.
 * - I0(phi) = 10^(-0.4 * (3.84 + 0.026*phi + 4e-9 * phi^4)): the Moon's
 *   illuminance outside the atmosphere (K&S phase function).
 * - f(rho) = f_R + f_M with rho the Moon–target separation in degrees:
 *   f_R(rho) = 10^5.36 * (1.06 + cos²(rho)) (Rayleigh),
 *   f_M(rho) = 10^(6.15 - rho/40) (Mie).
 * - k = 0.25 mag/airmass, a typical V-band extinction (K&S / ING TN127).
 * - Airmasses use the simplified X = 1/cos(z) form (design decision for
 *   this layer); the Moon below the horizon contributes exactly 0.
 *
 * The product above is in nanoLamberts (nL). Conversion: 1 Lambert is
 * (10000/pi) cd/m², so 1 nL = 1e-9 * 10000/pi cd/m² = 3.183e-6 cd/m²
 * = 0.003183 mcd/m².
 *
 * This is an order-of-magnitude estimate (design §5, general rule 2):
 * aerosol and humidity variations alone can shift it by about ±50%.
 */
object MoonlightModel {

    /** V-band extinction coefficient, mag per airmass (typical value, K&S 1991). */
    const val EXTINCTION_K = 0.25

    /** nanoLambert -> mcd/m² (derivation in the class KDoc). */
    const val NL_TO_MCD_M2 = 0.003183

    /** Zenith angle cap (degrees) keeping 1/cos(z) finite near the horizon. */
    private const val MAX_ZENITH_DEG = 89.5

    /** Lunar phase angle in degrees from the illumination fraction in [0, 1]. */
    fun phaseAngleDegrees(illumination: Double): Double {
        val cosPhi = (2.0 * illumination.coerceIn(0.0, 1.0) - 1.0).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosPhi))
    }

    /** I0(phi): lunar illuminance outside the atmosphere (K&S phase function). */
    fun phaseFunctionI0(illumination: Double): Double {
        val phi = phaseAngleDegrees(illumination)
        return 10.0.pow(-0.4 * (3.84 + 0.026 * phi + 4.0e-9 * phi.pow(4)))
    }

    /** f(rho) = f_R + f_M scattering function for separation [separationDeg]. */
    fun scatteringFunction(separationDeg: Double): Double {
        val rho = separationDeg.coerceIn(0.0, 180.0)
        val cosRho = cos(Math.toRadians(rho))
        val fR = 10.0.pow(5.36) * (1.06 + cosRho * cosRho)
        val fM = 10.0.pow(6.15 - rho / 40.0)
        return fR + fM
    }

    private fun airmass(altitudeDeg: Double): Double {
        val zenithDeg = (90.0 - altitudeDeg).coerceIn(0.0, MAX_ZENITH_DEG)
        return 1.0 / cos(Math.toRadians(zenithDeg))
    }

    /**
     * Moonlight sky brightness at the target, in mcd/m² (same luminance
     * footing as the light-pollution baseline, so the two compose linearly).
     * Returns 0 when the Moon is below the horizon or illumination is 0.
     */
    fun contributionMcdPerM2(
        illumination: Double,
        moonAltitudeDeg: Double,
        separationDeg: Double,
        targetAltitudeDeg: Double,
    ): Double {
        if (moonAltitudeDeg < 0.0 || illumination <= 0.0) return 0.0
        val extinctionMoon = 10.0.pow(-0.4 * EXTINCTION_K * airmass(moonAltitudeDeg))
        val targetFactor = 1.0 - 10.0.pow(-0.4 * EXTINCTION_K * airmass(targetAltitudeDeg.coerceAtLeast(0.0)))
        val brightnessNl = phaseFunctionI0(illumination) *
            scatteringFunction(separationDeg) * extinctionMoon * targetFactor
        return brightnessNl * NL_TO_MCD_M2
    }
}
