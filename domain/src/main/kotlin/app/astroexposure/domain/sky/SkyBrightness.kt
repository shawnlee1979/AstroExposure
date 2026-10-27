package app.astroexposure.domain.sky

import kotlin.math.log10
import kotlin.math.pow

/**
 * Sky-brightness unit conversions and the Bortle scale mapping — the single
 * implementation of these conversions in the codebase (D.4).
 *
 * The mpsas <-> luminance relation uses the standard zero point also used by
 * the Falchi atlas tooling: a luminance L (cd/m²) corresponds to
 *   mpsas = -2.5 * log10(L / 108000)
 * so the natural (unpolluted) sky of 0.1712 mcd/m² sits at about 22.0 mpsas.
 *
 * The Bortle <-> mpsas table below is an EMPIRICAL correspondence
 * ("经验换算"): the Bortle scale is a qualitative visual scale, and published
 * SQM ranges per class overlap. The thresholds and representative values
 * here follow the commonly published SQM ranges per Bortle class
 * (class 1 ≈ 21.9–22.0, 2 ≈ 21.5–21.9, 3 ≈ 21.3–21.5, 4 ≈ 20.5–21.3,
 * 5 ≈ 19.5–20.5, 6 ≈ 18.9–19.5, 7 ≈ 18.4–18.9, 8 ≈ 17.8–18.4,
 * 9 < 17.8 mpsas); treat results as ±1 class estimates (design §8.6).
 */
object SkyBrightness {

    /** Natural (zero artificial light) sky luminance, mcd/m² (Falchi atlas reference). */
    const val NATURAL_SKY_MCD_M2 = 0.1712

    /** Total luminance (mcd/m², natural + artificial) -> surface brightness (mag/arcsec²). */
    fun totalToMpsas(totalMcdPerM2: Double): Double {
        require(totalMcdPerM2 > 0.0) { "total luminance must be positive, was $totalMcdPerM2" }
        return -2.5 * log10((totalMcdPerM2 / 1000.0) / 108000.0)
    }

    /** Surface brightness (mag/arcsec²) -> total luminance (mcd/m²). */
    fun mpsasToTotalMcd(mpsas: Double): Double =
        108000.0 * 10.0.pow(-0.4 * mpsas) * 1000.0

    /** Artificial component only (mcd/m²) for a total luminance: total minus the natural floor. */
    fun artificialFromTotalMcd(totalMcdPerM2: Double): Double =
        (totalMcdPerM2 - NATURAL_SKY_MCD_M2).coerceAtLeast(0.0)

    /** Upper mpsas bound (inclusive) per Bortle class 1..9 — empirical, see class KDoc. */
    private val BORTLE_UPPER_MPSAS = doubleArrayOf(
        Double.POSITIVE_INFINITY, // class 1: anything at or above 21.90
        21.90, 21.50, 21.30, 20.50, 19.50, 18.90, 18.40, 17.80,
    )

    /** Lower mpsas bound (exclusive, except class 9) per Bortle class 1..9 — empirical. */
    private val BORTLE_LOWER_MPSAS = doubleArrayOf(
        21.90, 21.50, 21.30, 20.50, 19.50, 18.90, 18.40, 17.80, Double.NEGATIVE_INFINITY,
    )

    /** Bortle class estimate from a total-sky mpsas value (empirical mapping, ±1 class). */
    fun bortleFromMpsas(mpsas: Double): Int {
        for (b in 1..9) {
            if (mpsas > BORTLE_LOWER_MPSAS[b - 1] && mpsas <= BORTLE_UPPER_MPSAS[b - 1]) return b
        }
        return if (mpsas > 21.90) 1 else 9
    }

    /** Representative mpsas per Bortle class 1..9 — empirical midpoints of the ranges above. */
    fun representativeMpsasForBortle(bortle: Int): Double {
        require(bortle in 1..9) { "Bortle class must be in 1..9, was $bortle" }
        return when (bortle) {
            1 -> 21.99
            2 -> 21.70
            3 -> 21.40
            4 -> 20.90
            5 -> 20.00
            6 -> 19.20
            7 -> 18.65
            8 -> 18.10
            else -> 17.50
        }
    }

    /** Artificial luminance (mcd/m²) implied by a Bortle class via its representative mpsas. */
    fun artificialMcdForBortle(bortle: Int): Double =
        artificialFromTotalMcd(mpsasToTotalMcd(representativeMpsasForBortle(bortle)))
}
