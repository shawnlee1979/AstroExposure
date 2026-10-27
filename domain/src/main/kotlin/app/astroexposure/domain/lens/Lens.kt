package app.astroexposure.domain.lens

import kotlin.math.log2
import kotlin.math.pow

/**
 * A lens entry from the lens library (design §2.4). A prime has
 * [focalMinMm] == [focalMaxMm]; a zoom carries its range and the caller
 * passes the focal length actually in use to the models.
 */
data class Lens(
    val id: String,
    val name: String,
    /** Mount identifier, e.g. "E", "RF", "Z" (design §2.4 built-in lists). */
    val mount: String,
    val focalMinMm: Double,
    val focalMaxMm: Double,
    /** Maximum aperture f-number at the wide end. */
    val maxAperture: Double,
    /**
     * Maximum aperture at the telephoto end for variable-aperture zooms
     * (e.g. 5.6 for an f/3.5-5.6 zoom); null for constant-aperture lenses.
     */
    val maxApertureAtMaxFocal: Double? = null,
) {
    val isPrime: Boolean get() = focalMinMm == focalMaxMm

    /**
     * Maximum aperture available at [focalMm]. Constant-aperture lenses
     * always return [maxAperture]. Variable-aperture zooms interpolate
     * linearly in log2 space (aperture stops are logarithmic) over the
     * focal length's relative position in [focalMinMm, focalMaxMm];
     * out-of-range focal lengths clamp to the endpoints.
     */
    fun maxApertureAt(focalMm: Double): Double {
        val tele = maxApertureAtMaxFocal ?: return maxAperture
        if (focalMaxMm <= focalMinMm) return maxAperture
        val clamped = focalMm.coerceIn(focalMinMm, focalMaxMm)
        val fraction = (clamped - focalMinMm) / (focalMaxMm - focalMinMm)
        val logWide = log2(maxAperture)
        val logTele = log2(tele)
        return 2.0.pow(logWide + fraction * (logTele - logWide))
    }
}
