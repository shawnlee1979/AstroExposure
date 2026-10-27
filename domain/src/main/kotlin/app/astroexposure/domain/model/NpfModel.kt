package app.astroexposure.domain.model

import app.astroexposure.domain.camera.Camera
import app.astroexposure.domain.strictness.Strictness
import app.astroexposure.domain.units.Degrees
import app.astroexposure.domain.units.Seconds
import kotlin.math.abs
import kotlin.math.cos

/**
 * NPF rule, full form (design §5.2.1) — the only untracked wide-field model
 * and the single implementation of this formula in the codebase (D.4).
 *
 *   t_max = k x (16.856*N + 0.0997*f + 13.713*p) / (f x cos(dec))
 *
 * N = f-number, f = focal length (mm), p = pixel pitch (µm),
 * dec = target declination, k = strictness coefficient.
 * Formula source: Frédéric Michaud / Société Astronomique du Havre.
 */
object NpfModel {

    /** Beyond |dec| = 85° the cosine term explodes; design caps it there. */
    private const val DECLINATION_CAP_DEG = 85.0

    data class Input(
        val camera: Camera,
        val focalLengthMm: Double,
        val aperture: Double,
        val declination: Degrees,
        val strictness: Strictness,
    )

    data class Result(
        /** Longest shutter that keeps stars point-like under this strictness. */
        val maxShutter: Seconds,
        /** Rule-of-500 value, comparison only — never the recommendation. */
        val ruleOf500: Seconds,
        /** The k actually used (mirrors the strictness tier, shown on the card). */
        val kUsed: Int,
        /** True when |declination| exceeded the 85° cap and was clamped. */
        val declinationCapped: Boolean,
    )

    fun evaluate(input: Input): Result {
        val f = input.focalLengthMm
        val n = input.aperture
        val p = input.camera.pixelPitch.value
        require(f.isFinite() && f > 0.0) { "focal length must be positive and finite, was $f" }
        require(n.isFinite() && n > 0.0) { "aperture must be positive and finite, was $n" }
        require(p.isFinite() && p > 0.0) { "pixel pitch must be positive and finite, was $p" }
        require(input.declination.value.isFinite()) {
            "declination must be finite, was ${input.declination.value}"
        }
        val decDeg = input.declination.value
        val capped = abs(decDeg) > DECLINATION_CAP_DEG
        val effectiveDecDeg = decDeg.coerceIn(-DECLINATION_CAP_DEG, DECLINATION_CAP_DEG)
        val cosDec = cos(Math.toRadians(effectiveDecDeg))
        val k = input.strictness.npfK
        val t = k * (16.856 * n + 0.0997 * f + 13.713 * p) / (f * cosDec)
        return Result(
            maxShutter = Seconds(t),
            ruleOf500 = Seconds(500.0 / (f * input.camera.cropFactor)),
            kUsed = k,
            declinationCapped = capped,
        )
    }
}
