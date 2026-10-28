package app.astroexposure.domain.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden and boundary tests for the full-frame framing advice
 * (ui-design §7.5). Golden focal lengths are computed from the
 * sanctioned formulas FOV(f) = 2·atan(18/f) and share = size / FOV,
 * not copied from any rounded rule of thumb.
 */
class FullFrameFramingTest {

    @Test
    fun m33GoldenRange() {
        // M33's commonly listed major axis is about 73 arcminutes.
        // share 1/3 -> 564.92mm, share 1/2 -> 847.54mm, share 2/3 -> 1130.12mm.
        val advice = FullFrameFraming.advice(73.0)
        assertTrue(advice is FullFrameFraming.Advice.Range)
        advice as FullFrameFraming.Advice.Range
        assertEquals(564.92, advice.lowFocalMm, 0.05)
        assertEquals(1130.12, advice.highFocalMm, 0.05)
        assertEquals(847.54, advice.referenceFocalMm, 0.05)
        assertEquals(0.5, advice.referenceShare, 1e-9)
    }

    @Test
    fun shareAndFocalAreInverse() {
        for (size in listOf(5.0, 42.0, 73.0, 180.0, 600.0)) {
            for (share in listOf(0.2, 1.0 / 3.0, 0.5, 2.0 / 3.0, 0.9)) {
                val focal = FullFrameFraming.focalForShare(size, share)
                assertEquals(share, FullFrameFraming.shareAtFocal(size, focal), 1e-9)
            }
        }
    }

    @Test
    fun noSizeDataGivesNoAdvice() {
        assertEquals(FullFrameFraming.Advice.NoSizeData, FullFrameFraming.advice(null))
        assertEquals(FullFrameFraming.Advice.NoSizeData, FullFrameFraming.advice(0.0))
        assertEquals(FullFrameFraming.Advice.NoSizeData, FullFrameFraming.advice(-3.0))
        // Non-finite sizes are not measurements either: NaN must not
        // slip past the guard into the focal arithmetic.
        assertEquals(FullFrameFraming.Advice.NoSizeData, FullFrameFraming.advice(Double.NaN))
        assertEquals(
            FullFrameFraming.Advice.NoSizeData,
            FullFrameFraming.advice(Double.POSITIVE_INFINITY),
        )
    }

    @Test
    fun tinyTargetCapsAtMaxFocalWithHonestShare() {
        // 1 arcminute: even the 1/3-share focal is ~41,000mm, far past
        // the 2000mm cap; the advice reports the share at the cap
        // (about 1.6% of the long side) instead of the huge focal.
        val advice = FullFrameFraming.advice(1.0)
        assertTrue(advice is FullFrameFraming.Advice.CappedAtMaxFocal)
        advice as FullFrameFraming.Advice.CappedAtMaxFocal
        assertEquals(0.01616, advice.shareAtMaxFocal, 0.0001)
    }

    @Test
    fun hugeTargetCapsAtMinFocalWithHonestShare() {
        // 60 degrees across: even the 2/3-share focal (18mm) is below
        // the 24mm floor; at 24mm the target fills about 81% of the
        // long side.
        val advice = FullFrameFraming.advice(3600.0)
        assertTrue(advice is FullFrameFraming.Advice.CappedAtMinFocal)
        advice as FullFrameFraming.Advice.CappedAtMinFocal
        assertEquals(0.8137, advice.shareAtMinFocal, 0.001)
    }

    @Test
    fun partialRangeClampsToMaxFocalAndRecomputesReferenceShare() {
        // 30 arcminutes: the ideal range is 1375mm..2750mm; the top is
        // clamped to the 2000mm cap and the reference focal is coerced
        // into the range, its share recomputed (about 48.5%, not 1/2).
        val advice = FullFrameFraming.advice(30.0)
        assertTrue(advice is FullFrameFraming.Advice.Range)
        advice as FullFrameFraming.Advice.Range
        assertEquals(1375.02, advice.lowFocalMm, 0.05)
        assertEquals(FullFrameFraming.MAX_FOCAL_MM, advice.highFocalMm, 1e-9)
        assertEquals(FullFrameFraming.MAX_FOCAL_MM, advice.referenceFocalMm, 1e-9)
        assertEquals(0.48483, advice.referenceShare, 0.0005)
    }

    @Test
    fun rangeEndpointsGiveTheBandShares() {
        // For an unclamped range the endpoints are exactly the band
        // shares: low focal -> 1/3, high focal -> 2/3.
        val advice = FullFrameFraming.advice(120.0) as FullFrameFraming.Advice.Range
        assertEquals(
            FullFrameFraming.SHARE_LOW,
            FullFrameFraming.shareAtFocal(120.0, advice.lowFocalMm), 1e-9,
        )
        assertEquals(
            FullFrameFraming.SHARE_HIGH,
            FullFrameFraming.shareAtFocal(120.0, advice.highFocalMm), 1e-9,
        )
    }
}
