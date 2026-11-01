package app.astroexposure.ui

import app.astroexposure.domain.orientation.DeviceOrientationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The foreground-top field's pure input logic (ui-design §7.9,
 * 0.17.0): the typed buffer's filter, the submit's four-way
 * decision, the viewfinder hold's trailing mean, and the camera
 * permission flow's step mapping.
 */
class LandscapeTopInputTest {

    // --- The typed filter ------------------------------------------------

    @Test
    fun theFilterKeepsOnlyASignAndDigits() {
        assertEquals("6", filterTopInput("6"))
        assertEquals("-12", filterTopInput("-12"))
        assertEquals("-12", filterTopInput("−12"))
        assertEquals("12", filterTopInput("1a2"))
        assertEquals("12", filterTopInput("12-"))
        assertEquals("", filterTopInput("abc"))
        assertEquals("-", filterTopInput("-"))
    }

    @Test
    fun theFilterDropsTheDecimalPoint() {
        // Integer degrees end to end: a point can never reach the
        // submit, where it would force a silent rounding.
        assertEquals("65", filterTopInput("6.5"))
        assertEquals("65", filterTopInput("6．5"))
    }

    @Test
    fun theFilterCapsTheBufferAtFourCharacters() {
        assertEquals("-120", filterTopInput("-1200"))
        assertEquals("1234", filterTopInput("12345"))
    }

    // --- The submit decision ----------------------------------------------

    @Test
    fun emptyOrSignOnlySubmitsCancel() {
        assertEquals(TopSubmit.Cancel, submitTopInput(""))
        assertEquals(TopSubmit.Cancel, submitTopInput("-"))
    }

    @Test
    fun inDomainSubmitsWrite() {
        assertEquals(TopSubmit.Write(6), submitTopInput("6"))
        assertEquals(TopSubmit.Write(-20), submitTopInput("-20"))
        assertEquals(TopSubmit.Write(80), submitTopInput("80"))
        assertEquals(TopSubmit.Write(0), submitTopInput("0"))
    }

    @Test
    fun outOfDomainSubmitsRejectWithTheValue() {
        // Rejected in the open, never clamped into a different
        // number the user did not type.
        assertEquals(TopSubmit.Reject(95), submitTopInput("95"))
        assertEquals(TopSubmit.Reject(81), submitTopInput("81"))
        assertEquals(TopSubmit.Reject(-21), submitTopInput("-21"))
    }

    // --- The viewfinder hold mean -------------------------------------------

    private fun sample(elapsedMillis: Long, axisDeg: Double) =
        DeviceOrientationProvider.OrientationSample(
            headingTrueDeg = 0.0,
            pitchDeg = 0.0,
            screenNormalTiltDeg = 0.0,
            cameraAxisElevationDeg = axisDeg,
            accuracy = DeviceOrientationProvider.OrientationAccuracy.HIGH,
            elapsedMillis = elapsedMillis,
        )

    @Test
    fun theHoldMeanCoversOnlyTheTrailingWindow() {
        val samples = listOf(
            sample(0L, 10.0),
            sample(399L, 100.0),
            sample(400L, 20.0),
            sample(700L, 30.0),
            sample(1000L, 40.0),
        )
        // Latest stamp 1000, window [400, 1000]: the 399 sample is
        // outside by a millisecond and its 100° must not leak in.
        assertEquals(30.0, meanCameraAxisDeg(samples)!!, 1e-9)
    }

    @Test
    fun theHoldMeanOfOneSampleIsThatSample() {
        assertEquals(7.5, meanCameraAxisDeg(listOf(sample(50L, 7.5)))!!, 1e-9)
    }

    @Test
    fun theHoldMeanOfNoSamplesIsNull() {
        assertNull(meanCameraAxisDeg(emptyList()))
    }

    // --- The permission step mapping -----------------------------------------

    @Test
    fun thePermissionStepsFollowTheFacts() {
        assertEquals(
            CameraPermissionStep.GRANTED,
            cameraPermissionStep(granted = true, askedBefore = false, shouldShowRationale = false),
        )
        assertEquals(
            CameraPermissionStep.EXPLAIN_FIRST,
            cameraPermissionStep(granted = false, askedBefore = false, shouldShowRationale = false),
        )
        assertEquals(
            CameraPermissionStep.DENIED_ASKABLE,
            cameraPermissionStep(granted = false, askedBefore = true, shouldShowRationale = true),
        )
        assertEquals(
            CameraPermissionStep.DENIED_PERMANENT,
            cameraPermissionStep(granted = false, askedBefore = true, shouldShowRationale = false),
        )
    }

    @Test
    fun theDomainConstantsMatchTheSpec() {
        assertTrue(TOP_MIN_DEG == -20 && TOP_MAX_DEG == 80)
        assertEquals(600L, AXIS_HOLD_WINDOW_MILLIS)
    }
}
