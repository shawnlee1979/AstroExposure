package app.astroexposure.domain.lens

import app.astroexposure.domain.camera.FullFrameFraming
import kotlin.math.abs
import kotlin.math.log2

/**
 * Candidate lenses for the framing chart's pick row (ui-design
 * §7.10, 0.13.0): the library filtered to the lenses that can
 * actually reach the subject's suggested focal range, ordered by
 * closeness to the reference focal length. This replaces the
 * whole-library row, which listed every lens however irrelevant
 * to the subject at hand.
 *
 * The effective interval is NOT a new band. It is the geometric
 * focal segment whose long-side share runs over
 * [FullFrameFraming.SHARE_LOW]..[FullFrameFraming.SHARE_HIGH] —
 * the [FullFrameFraming.focalForShare] numbers behind the step-1
 * grid's suggested range and the framing card's own readout —
 * intersected with the focal slider's domain, which the caller
 * passes in (the slider owns its domain; the advice's own
 * presentation clamps, 24 / 2000 mm, do not leak into this
 * intersection). For an unclamped range the interval is the
 * suggested range itself, digit for digit.
 *
 * Inclusion: a prime is in when its focal length lies in the
 * interval; a zoom is in when its focal segment intersects the
 * interval. Touching an endpoint counts as in — the same
 * inclusive boundary the framing judgement uses ("on the frame
 * edge counts as in frame", §7.10).
 *
 * Ordering: by log2 distance to the reference focal length, in
 * stops — focal lengths compare by ratio, not by difference
 * (85 mm vs 135 mm around a 128 mm reference is 0.59 stops vs
 * 0.08). A zoom whose segment covers the reference has distance
 * 0; any other zoom measures to its nearer endpoint. Ties break
 * by focalMin, then focalMax, then name, then id, so the order
 * is total and stable for any library.
 */
object FramingLensCandidates {

    /** One candidate: the lens and its distance to the reference, in stops. */
    data class Candidate(
        val lens: Lens,
        val distanceStops: Double,
    )

    /**
     * The selection for one subject: the effective interval
     * (both bounds null when the geometric segment misses the
     * slider domain entirely — a capped advice form) and the
     * ordered candidates whose focal segment reaches it.
     */
    data class Selection(
        val intervalLowMm: Double?,
        val intervalHighMm: Double?,
        val candidates: List<Candidate>,
    )

    fun select(
        sizeMajorArcmin: Double,
        referenceFocalMm: Double,
        sliderMinMm: Double,
        sliderMaxMm: Double,
        lenses: List<Lens>,
    ): Selection {
        if (!sizeMajorArcmin.isFinite() || sizeMajorArcmin <= 0.0) {
            return Selection(null, null, emptyList())
        }
        val low = maxOf(
            FullFrameFraming.focalForShare(sizeMajorArcmin, FullFrameFraming.SHARE_LOW),
            sliderMinMm,
        )
        val high = minOf(
            FullFrameFraming.focalForShare(sizeMajorArcmin, FullFrameFraming.SHARE_HIGH),
            sliderMaxMm,
        )
        if (low > high) return Selection(null, null, emptyList())
        val candidates = lenses
            .filter { it.focalMinMm <= high && it.focalMaxMm >= low }
            .map { Candidate(it, distanceStops(it, referenceFocalMm)) }
            .sortedWith(
                compareBy(
                    { it.distanceStops },
                    { it.lens.focalMinMm },
                    { it.lens.focalMaxMm },
                    { it.lens.name },
                    { it.lens.id },
                ),
            )
        return Selection(low, high, candidates)
    }

    /** Log2 distance between a lens and the reference focal length, in stops. */
    fun distanceStops(lens: Lens, referenceFocalMm: Double): Double {
        if (lens.isPrime) return abs(log2(lens.focalMinMm / referenceFocalMm))
        if (referenceFocalMm >= lens.focalMinMm &&
            referenceFocalMm <= lens.focalMaxMm
        ) {
            return 0.0
        }
        return minOf(
            abs(log2(lens.focalMinMm / referenceFocalMm)),
            abs(log2(lens.focalMaxMm / referenceFocalMm)),
        )
    }

    /**
     * The focal length a pick lands on (§7.10, 0.13.0): a prime
     * lands on its own focal length; a zoom lands on the
     * reference clamped into its segment — a zoom picked for
     * covering the reference lands on the reference, not on its
     * wide end; an endpoint-only zoom lands on the nearer
     * endpoint.
     */
    fun pickFocalMm(lens: Lens, referenceFocalMm: Double): Double =
        if (lens.isPrime) {
            lens.focalMinMm
        } else {
            referenceFocalMm.coerceIn(lens.focalMinMm, lens.focalMaxMm)
        }
}
