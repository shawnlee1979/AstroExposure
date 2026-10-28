package app.astroexposure.domain.camera

import kotlin.math.atan
import kotlin.math.tan

/**
 * Full-frame framing advice for a deep-sky target (ui-design §7.5,
 * 0.5.1 revision): given the target's major-axis angular size, which
 * focal lengths put that axis at a useful share of the 36.0 x 24.0 mm
 * frame's long side. Pure geometry on explicit inputs — the caller
 * supplies the catalogue size; nothing here reads a catalogue, a clock
 * or a device.
 *
 * Definitions (the single sanctioned ones):
 * - long-side field of view at focal length f: FOV(f) = 2·atan(18/f),
 *   with 18 mm the frame's half long side;
 * - share of the long side: share(f) = target major axis / FOV(f);
 * - the recommended range is the focal interval whose share runs from
 *   [SHARE_LOW] (1/3, the short-focal end) to [SHARE_HIGH] (2/3, the
 *   long-focal end), with the share-1/2 focal as the mid reference.
 *
 * Boundary rules (stated, never silently clamped):
 * - no catalogue size (null or non-positive): [Advice.NoSizeData] —
 *   the card says so instead of inventing a focal length;
 * - target so small that even the SHARE_LOW focal exceeds
 *   [MAX_FOCAL_MM]: [Advice.CappedAtMaxFocal] reports the share at
 *   [MAX_FOCAL_MM] instead of a five-digit focal length;
 * - target so large that even the SHARE_HIGH focal is below
 *   [MIN_FOCAL_MM]: [Advice.CappedAtMinFocal] reports the share at
 *   [MIN_FOCAL_MM] — the target overflows even the widest common lens;
 * - a range that only partially crosses a limit is clamped to the
 *   limit and the reference focal is coerced into the range, with its
 *   share recomputed — the numbers shown are always self-consistent.
 */
object FullFrameFraming {

    /** Full-frame long side, mm (36.0 x 24.0 mm). */
    const val FRAME_LONG_MM = 36.0

    /** Lowest focal length the advice will name, mm. */
    const val MIN_FOCAL_MM = 24.0

    /** Highest focal length the advice will name, mm. */
    const val MAX_FOCAL_MM = 2000.0

    /** Low end of the recommended share band: the target axis fills 1/3 of the long side. */
    const val SHARE_LOW = 1.0 / 3.0

    /** High end of the recommended share band: the target axis fills 2/3 of the long side. */
    const val SHARE_HIGH = 2.0 / 3.0

    /** Reference share inside the band: the target axis fills half of the long side. */
    const val SHARE_REFERENCE = 0.5

    sealed interface Advice {
        /** No catalogue angular size: no focal advice is given. */
        data object NoSizeData : Advice

        /**
         * A usable focal range [lowFocalMm]..[highFocalMm] (already
         * clamped to the focal limits when the ideal range crosses
         * one), plus the reference point inside it: [referenceFocalMm]
         * with its recomputed [referenceShare].
         */
        data class Range(
            val lowFocalMm: Double,
            val highFocalMm: Double,
            val referenceFocalMm: Double,
            val referenceShare: Double,
        ) : Advice

        /** Target too small for the band even at [MAX_FOCAL_MM]; [shareAtMaxFocal] is what it gets there. */
        data class CappedAtMaxFocal(val shareAtMaxFocal: Double) : Advice

        /** Target too large for the band even at [MIN_FOCAL_MM]; [shareAtMinFocal] is what it gets there. */
        data class CappedAtMinFocal(val shareAtMinFocal: Double) : Advice
    }

    /** Long-side field of view in degrees at [focalMm]. */
    fun longSideFovDeg(focalMm: Double): Double =
        Math.toDegrees(2.0 * atan((FRAME_LONG_MM / 2.0) / focalMm))

    /** Share of the long side taken by an axis of [sizeArcmin] at [focalMm]. */
    fun shareAtFocal(sizeArcmin: Double, focalMm: Double): Double =
        (sizeArcmin / 60.0) / longSideFovDeg(focalMm)

    /** Focal length at which an axis of [sizeArcmin] fills exactly [share] of the long side. */
    fun focalForShare(sizeArcmin: Double, share: Double): Double {
        val fovRad = Math.toRadians((sizeArcmin / 60.0) / share)
        return (FRAME_LONG_MM / 2.0) / tan(fovRad / 2.0)
    }

    /** The framing advice for one catalogue major-axis size (arcminutes), or its boundary form. */
    fun advice(sizeMajorArcmin: Double?): Advice {
        val size = sizeMajorArcmin
        if (size == null || !size.isFinite() || size <= 0.0) return Advice.NoSizeData
        // The low end of the focal range is the SHARE_LOW focal (wide
        // field, target small in frame); the high end is the SHARE_HIGH
        // focal. A bigger share always means a longer focal length.
        val lowIdeal = focalForShare(size, SHARE_LOW)
        val highIdeal = focalForShare(size, SHARE_HIGH)
        if (lowIdeal > MAX_FOCAL_MM) {
            return Advice.CappedAtMaxFocal(shareAtFocal(size, MAX_FOCAL_MM))
        }
        if (highIdeal < MIN_FOCAL_MM) {
            return Advice.CappedAtMinFocal(shareAtFocal(size, MIN_FOCAL_MM))
        }
        val low = maxOf(lowIdeal, MIN_FOCAL_MM)
        val high = minOf(highIdeal, MAX_FOCAL_MM)
        val reference = focalForShare(size, SHARE_REFERENCE).coerceIn(low, high)
        return Advice.Range(
            lowFocalMm = low,
            highFocalMm = high,
            referenceFocalMm = reference,
            referenceShare = shareAtFocal(size, reference),
        )
    }
}
