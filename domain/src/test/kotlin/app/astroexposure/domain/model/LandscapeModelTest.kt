package app.astroexposure.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Goldens: design §5.1 empirical magnitudes — blue-hour base table
 * 2 s / 8 s / 25 s at f/8 + ISO 100 for about 10 / 20 / 30 minutes after
 * sunset, EV-shifted to the chosen ISO/aperture; night and stacking
 * ranges as recorded in the design.
 */
class LandscapeModelTest {

    private fun input(
        methods: Set<LandscapeModel.ForegroundMethod>,
        minutes: Int? = 10,
        moon: Double? = null,
        blueIso: Int = 100,
        blueAperture: Double = 8.0,
        tracked: Boolean = false,
    ) = LandscapeModel.Input(
        sky = LandscapeModel.SkyFrameSnapshot(60.0, 800, 2.0, tracked),
        methods = methods,
        minutesAfterSunset = minutes,
        moonIllumination = moon,
        blueHourIso = blueIso,
        blueHourAperture = blueAperture,
    )

    @Test
    fun `blue hour base magnitudes at f8 ISO 100`() {
        fun shutterAt(minutes: Int): Double = LandscapeModel.evaluate(
            input(setOf(LandscapeModel.ForegroundMethod.BLUE_HOUR), minutes = minutes),
        ).foregroundFrames.single().shutterMinSeconds
        assertEquals(2.0, shutterAt(10), 1e-9)
        assertEquals(8.0, shutterAt(20), 1e-9)
        assertEquals(25.0, shutterAt(30), 1e-9)
    }

    @Test
    fun `blue hour EV shifts with chosen ISO and aperture`() {
        val frame = LandscapeModel.evaluate(
            input(setOf(LandscapeModel.ForegroundMethod.BLUE_HOUR), minutes = 10, blueIso = 200),
        ).foregroundFrames.single()
        assertEquals(1.0, frame.shutterMinSeconds, 1e-9) // one stop faster at ISO 200
        assertEquals(100..400, frame.isoRange)
        val opened = LandscapeModel.evaluate(
            input(setOf(LandscapeModel.ForegroundMethod.BLUE_HOUR), minutes = 10, blueAperture = 5.6),
        ).foregroundFrames.single()
        assertEquals(2.0 * (5.6 / 8.0) * (5.6 / 8.0), opened.shutterMinSeconds, 1e-9)
    }

    @Test
    fun `night long exposure ranges and moonlight hint`() {
        val frame = LandscapeModel.evaluate(
            input(setOf(LandscapeModel.ForegroundMethod.NIGHT_LONG_EXPOSURE), minutes = null, moon = 0.9),
        ).foregroundFrames.single()
        assertEquals(400..1600, frame.isoRange)
        assertEquals(30.0, frame.shutterMinSeconds, 1e-9)
        assertEquals(240.0, frame.shutterMaxSeconds, 1e-9)
        assertTrue(frame.moonlightHint)
        val dark = LandscapeModel.evaluate(
            input(setOf(LandscapeModel.ForegroundMethod.NIGHT_LONG_EXPOSURE), minutes = null, moon = 0.1),
        ).foregroundFrames.single()
        assertFalse(dark.moonlightHint)
    }

    @Test
    fun `high ISO stack carries frame count and sky shutter`() {
        val frame = LandscapeModel.evaluate(
            input(setOf(LandscapeModel.ForegroundMethod.HIGH_ISO_STACK), minutes = null),
        ).foregroundFrames.single()
        assertEquals(3200..6400, frame.isoRange)
        assertEquals(8..16, frame.frameCountRange)
        assertEquals(60.0, frame.shutterMinSeconds, 1e-9)
    }

    @Test
    fun `sky frame passes through and workflow is the fixed five steps`() {
        val result = LandscapeModel.evaluate(
            input(setOf(LandscapeModel.ForegroundMethod.BLUE_HOUR), tracked = true),
        )
        assertEquals(60.0, result.skyFrame.shutterSeconds, 1e-9)
        assertEquals(800, result.skyFrame.iso)
        assertTrue(result.skyFrame.compositeNote)
        assertEquals(5, result.workflowSteps.size)
        assertEquals(LandscapeModel.WorkflowStep.LOCK_COMPOSITION, result.workflowSteps.first())
        assertEquals(LandscapeModel.WorkflowStep.COMPOSITE_IN_POST, result.workflowSteps.last())
    }

    @Test
    fun `blue hour with null minutes produces no frame and reports the skip`() {
        // null minutes = "not in blue hour" (Input KDoc): the model must not
        // substitute a mid-table value (the old behaviour used 20 minutes).
        val result = LandscapeModel.evaluate(
            input(setOf(LandscapeModel.ForegroundMethod.BLUE_HOUR), minutes = null),
        )
        assertTrue(result.foregroundFrames.isEmpty())
        assertEquals(
            setOf(LandscapeModel.ForegroundMethod.BLUE_HOUR), result.skippedMethods,
        )
        // Other methods are unaffected by the missing blue-hour input.
        val mixed = LandscapeModel.evaluate(
            input(
                setOf(
                    LandscapeModel.ForegroundMethod.BLUE_HOUR,
                    LandscapeModel.ForegroundMethod.NIGHT_LONG_EXPOSURE,
                ),
                minutes = null,
            ),
        )
        assertEquals(1, mixed.foregroundFrames.size)
        assertEquals(
            LandscapeModel.ForegroundMethod.NIGHT_LONG_EXPOSURE,
            mixed.foregroundFrames.single().method,
        )
    }
}
