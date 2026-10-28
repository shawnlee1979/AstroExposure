package app.astroexposure.domain.orientation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finder pure functions (target detail dialog redesign, ui-design
 * §7.5 as revised for 0.8.0): hand-computed cases for the signed azimuth wrap, the
 * clamped pitch difference, the flat-posture boundary, the two-axis
 * hysteresis (enter 3° / exit 5°) and every cue branch of
 * [finderReadout] in its documented priority order.
 */
class FinderMathTest {

    private fun sample(
        headingTrueDeg: Double,
        pitchDeg: Double,
        screenNormalTiltDeg: Double = 45.0,
    ) = DeviceOrientationProvider.OrientationSample(
        headingTrueDeg = headingTrueDeg,
        pitchDeg = pitchDeg,
        screenNormalTiltDeg = screenNormalTiltDeg,
        accuracy = DeviceOrientationProvider.OrientationAccuracy.HIGH,
        elapsedMillis = 0L,
    )

    // --- signedAzimuthDeltaDeg -------------------------------------------------

    @Test
    fun `azimuth delta wraps across north in both directions`() {
        assertEquals(20.0, signedAzimuthDeltaDeg(350.0, 10.0), 1e-9)
        assertEquals(-20.0, signedAzimuthDeltaDeg(10.0, 350.0), 1e-9)
    }

    @Test
    fun `azimuth delta half turn is plus 180 from either side`() {
        assertEquals(180.0, signedAzimuthDeltaDeg(0.0, 180.0), 1e-9)
        assertEquals(180.0, signedAzimuthDeltaDeg(0.0, -180.0), 1e-9)
    }

    @Test
    fun `azimuth delta normalises arbitrary real inputs`() {
        assertEquals(0.0, signedAzimuthDeltaDeg(0.0, 720.0), 1e-9)
        assertEquals(10.0, signedAzimuthDeltaDeg(720.0, 730.0), 1e-9)
        assertEquals(-10.0, signedAzimuthDeltaDeg(-720.0, -730.0), 1e-9)
        assertEquals(20.0, signedAzimuthDeltaDeg(-10.0, 10.0), 1e-9)
    }

    @Test
    fun `azimuth delta of equal directions is zero and sign says turn direction`() {
        assertEquals(0.0, signedAzimuthDeltaDeg(123.0, 123.0), 1e-9)
        assertTrue(signedAzimuthDeltaDeg(100.0, 130.0) > 0.0) // clockwise = right
        assertTrue(signedAzimuthDeltaDeg(100.0, 70.0) < 0.0)
    }

    // --- pitchDeltaDeg -----------------------------------------------------------

    @Test
    fun `pitch delta is target minus current with raise positive`() {
        assertEquals(20.0, pitchDeltaDeg(10.0, 30.0), 1e-9)
        assertEquals(-20.0, pitchDeltaDeg(30.0, 10.0), 1e-9)
        assertEquals(0.0, pitchDeltaDeg(42.0, 42.0), 1e-9)
    }

    @Test
    fun `pitch delta clamps both inputs to the elevation range`() {
        assertEquals(180.0, pitchDeltaDeg(-120.0, 120.0), 1e-9)
        assertEquals(-180.0, pitchDeltaDeg(120.0, -120.0), 1e-9)
        assertEquals(90.0, pitchDeltaDeg(0.0, 200.0), 1e-9)
        assertEquals(0.0, pitchDeltaDeg(95.0, 110.0), 1e-9) // both clamp to 90
    }

    // --- holdingPosture ------------------------------------------------------------

    @Test
    fun `holding posture boundary at twenty degrees`() {
        assertEquals(HoldingPosture.FLAT, holdingPosture(19.9))
        assertEquals(HoldingPosture.AIMING, holdingPosture(20.0))
        assertEquals(HoldingPosture.FLAT, holdingPosture(0.0))
        assertEquals(HoldingPosture.AIMING, holdingPosture(90.0))
    }

    @Test
    fun `holding posture reads out of range tilt as the nearest end`() {
        assertEquals(HoldingPosture.FLAT, holdingPosture(-30.0)) // clamps to 0
        assertEquals(HoldingPosture.AIMING, holdingPosture(135.0)) // clamps to 90
    }

    // --- advanceAlignment hysteresis ----------------------------------------------

    @Test
    fun `alignment enters at the three degree boundary and not above it`() {
        val start = AlignmentState()
        assertTrue(advanceAlignment(start, 3.0, null).azimuthAligned)
        assertTrue(advanceAlignment(start, -3.0, null).azimuthAligned)
        assertFalse(advanceAlignment(start, 3.1, null).azimuthAligned)
    }

    @Test
    fun `aligned axis holds through the hysteresis band and exits past five degrees`() {
        val aligned = AlignmentState(azimuthAligned = true)
        assertTrue(advanceAlignment(aligned, 4.9, null).azimuthAligned)
        assertTrue(advanceAlignment(aligned, 5.0, null).azimuthAligned) // exit is strictly > 5
        assertFalse(advanceAlignment(aligned, 5.1, null).azimuthAligned)
        assertFalse(advanceAlignment(aligned, -5.1, null).azimuthAligned)
    }

    @Test
    fun `hysteresis band keeps an unaligned axis unaligned`() {
        val start = AlignmentState()
        assertFalse(advanceAlignment(start, 4.9, null).azimuthAligned)
        assertFalse(advanceAlignment(start, -4.0, null).azimuthAligned)
    }

    @Test
    fun `the two axes advance independently`() {
        val start = AlignmentState()
        val azimuthOnly = advanceAlignment(start, 1.0, 30.0)
        assertTrue(azimuthOnly.azimuthAligned)
        assertEquals(false, azimuthOnly.altitudeAligned)
        val altitudeOnly = advanceAlignment(start, 30.0, -1.0)
        assertFalse(altitudeOnly.azimuthAligned)
        assertEquals(true, altitudeOnly.altitudeAligned)
        // One axis leaving does not drag the other out.
        val both = AlignmentState(azimuthAligned = true, altitudeAligned = true)
        val after = advanceAlignment(both, 30.0, 4.9)
        assertFalse(after.azimuthAligned)
        assertEquals(true, after.altitudeAligned)
    }

    @Test
    fun `null altitude delta marks the axis unevaluated and re-entry starts unaligned`() {
        val both = AlignmentState(azimuthAligned = true, altitudeAligned = true)
        val suspended = advanceAlignment(both, 1.0, null)
        assertNull(suspended.altitudeAligned)
        // Even a delta inside the old hold band cannot resurrect the axis:
        // a null axis restarts from unaligned, so 4 degrees does not enter.
        val resumed = advanceAlignment(suspended, 1.0, 4.0)
        assertEquals(false, resumed.altitudeAligned)
        assertEquals(true, advanceAlignment(suspended, 1.0, 2.0).altitudeAligned)
    }

    @Test
    fun `combined derives all four alignments and null altitude caps at azimuth only`() {
        assertEquals(FinderAlignment.UNALIGNED, AlignmentState(false, false).combined)
        assertEquals(FinderAlignment.UNALIGNED, AlignmentState(false, null).combined)
        assertEquals(FinderAlignment.AZIMUTH_ONLY, AlignmentState(true, false).combined)
        assertEquals(FinderAlignment.AZIMUTH_ONLY, AlignmentState(true, null).combined)
        assertEquals(FinderAlignment.ALTITUDE_ONLY, AlignmentState(false, true).combined)
        assertEquals(FinderAlignment.ALIGNED, AlignmentState(true, true).combined)
    }

    // --- finderReadout ---------------------------------------------------------------

    @Test
    fun `readout without a sample reports no reading on both axes`() {
        val state = AlignmentState(azimuthAligned = true, altitudeAligned = true)
        val readout = finderReadout(40.0, 100.0, null, state)
        assertEquals(AzimuthCue.NO_READING, readout.azimuthCue)
        assertEquals(AltitudeCue.NO_READING, readout.altitudeCue)
        assertNull(readout.azimuthDeltaDeg)
        assertNull(readout.altitudeDeltaDeg)
        assertEquals(HoldingPosture.AIMING, readout.posture) // placeholder
        assertEquals(FinderAlignment.ALIGNED, readout.alignment) // input state, unchanged
    }

    @Test
    fun `readout turns right for a positive azimuth delta and left for a negative one`() {
        val state = AlignmentState()
        val right = finderReadout(40.0, 130.0, sample(100.0, 40.0), state)
        assertEquals(AzimuthCue.TURN_RIGHT, right.azimuthCue)
        assertEquals(30.0, right.azimuthDeltaDeg!!, 1e-9)
        val left = finderReadout(40.0, 70.0, sample(100.0, 40.0), state)
        assertEquals(AzimuthCue.TURN_LEFT, left.azimuthCue)
        assertEquals(-30.0, left.azimuthDeltaDeg!!, 1e-9)
    }

    @Test
    fun `readout raises for a positive pitch delta and lowers for a negative one`() {
        val state = AlignmentState()
        val raise = finderReadout(40.0, 100.0, sample(100.0, 25.0), state)
        assertEquals(AltitudeCue.RAISE, raise.altitudeCue)
        assertEquals(15.0, raise.altitudeDeltaDeg!!, 1e-9)
        val lower = finderReadout(40.0, 100.0, sample(100.0, 55.0), state)
        assertEquals(AltitudeCue.LOWER, lower.altitudeCue)
        assertEquals(-15.0, lower.altitudeDeltaDeg!!, 1e-9)
    }

    @Test
    fun `readout reports both axes aligned from the state`() {
        val state = AlignmentState(azimuthAligned = true, altitudeAligned = true)
        val readout = finderReadout(40.0, 100.0, sample(98.0, 38.0), state)
        assertEquals(AzimuthCue.ALIGNED, readout.azimuthCue)
        assertEquals(AltitudeCue.ALIGNED, readout.altitudeCue)
        assertEquals(FinderAlignment.ALIGNED, readout.alignment)
        assertEquals(2.0, readout.azimuthDeltaDeg!!, 1e-9)
        assertEquals(2.0, readout.altitudeDeltaDeg!!, 1e-9)
    }

    @Test
    fun `readout treats a zero delta in an unaligned state as aligned`() {
        val readout = finderReadout(40.0, 100.0, sample(100.0, 40.0), AlignmentState())
        assertEquals(AzimuthCue.ALIGNED, readout.azimuthCue)
        assertEquals(AltitudeCue.ALIGNED, readout.altitudeCue)
    }

    @Test
    fun `target below horizon takes priority over flat posture and still reports the delta`() {
        // Flat phone (tilt 0) AND target below the horizon: the target
        // fact wins, and the altitude delta is still given a value.
        val readout = finderReadout(-8.0, 100.0, sample(100.0, 10.0, screenNormalTiltDeg = 0.0), AlignmentState())
        assertEquals(AltitudeCue.TARGET_BELOW_HORIZON, readout.altitudeCue)
        assertEquals(HoldingPosture.FLAT, readout.posture)
        assertEquals(-18.0, readout.altitudeDeltaDeg!!, 1e-9)
        assertEquals(AzimuthCue.ALIGNED, readout.azimuthCue) // azimuth axis unaffected
    }

    @Test
    fun `flat posture reports its cue and still reports the delta`() {
        val readout = finderReadout(40.0, 100.0, sample(90.0, 25.0, screenNormalTiltDeg = 10.0), AlignmentState())
        assertEquals(AltitudeCue.FLAT_POSTURE, readout.altitudeCue)
        assertEquals(HoldingPosture.FLAT, readout.posture)
        assertEquals(15.0, readout.altitudeDeltaDeg!!, 1e-9)
        assertEquals(AzimuthCue.TURN_RIGHT, readout.azimuthCue) // azimuth still works flat
    }
}
