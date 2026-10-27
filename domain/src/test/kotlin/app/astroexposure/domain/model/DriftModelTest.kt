package app.astroexposure.domain.model

import app.astroexposure.domain.camera.BuiltInCameras
import app.astroexposure.domain.strictness.Strictness
import app.astroexposure.domain.tracking.BuiltInTrackingProfiles
import app.astroexposure.domain.tracking.TrackingKind
import app.astroexposure.domain.tracking.TrackingProfile
import app.astroexposure.domain.units.ArcsecPerMinute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Goldens: design §5.2.2 worked table — 50 mm, D = 10 "/min (a stated
 * hypothetical, not a measurement): A1 II 103 s / 206 s at 1 px / 2 px,
 * ZV-E1 208 s / 416 s. Tolerance ±1 s covers the design's rounding.
 */
class DriftModelTest {

    private val measured = TrackingProfile(
        id = "test", name = "Test", kind = TrackingKind.TRACKED,
        typicalDrift = ArcsecPerMinute(10.0), worstDrift = ArcsecPerMinute(10.0),
    )

    private fun measuredSeconds(camera: app.astroexposure.domain.camera.Camera, strictness: Strictness, profile: TrackingProfile = measured): Double =
        (DriftModel.maxExposure(camera, 50.0, profile, strictness) as DriftModel.Outcome.Measured).maxExposure.value

    @Test
    fun `design table A1 II 50mm D10`() {
        assertEquals(103.0, measuredSeconds(BuiltInCameras.A1_II, Strictness.STANDARD), 1.0)
        assertEquals(206.0, measuredSeconds(BuiltInCameras.A1_II, Strictness.RELAXED), 1.0)
    }

    @Test
    fun `design table ZV-E1 50mm D10`() {
        assertEquals(208.0, measuredSeconds(BuiltInCameras.ZV_E1, Strictness.STANDARD), 1.0)
        assertEquals(416.0, measuredSeconds(BuiltInCameras.ZV_E1, Strictness.RELAXED), 1.0)
    }

    @Test
    fun `pixel scale matches design section 3_6`() {
        assertEquals(17.1, DriftModel.pixelScaleArcsecPerPixel(BuiltInCameras.A1_II, 50.0), 0.05)
        assertEquals(34.7, DriftModel.pixelScaleArcsecPerPixel(BuiltInCameras.ZV_E1, 50.0), 0.1) // design rounds to 0.1
    }

    @Test
    fun `strict tier uses worst drift, standard uses typical`() {
        val profile = TrackingProfile(
            id = "t2", name = "T2", kind = TrackingKind.TRACKED,
            typicalDrift = ArcsecPerMinute(10.0), worstDrift = ArcsecPerMinute(20.0),
        )
        val strict = DriftModel.maxExposure(BuiltInCameras.A1_II, 50.0, profile, Strictness.STRICT) as DriftModel.Outcome.Measured
        assertEquals(20.0, strict.driftUsed.value, 1e-9)
        assertEquals(1.0, strict.tolerancePx, 1e-9)
        val standard = DriftModel.maxExposure(BuiltInCameras.A1_II, 50.0, profile, Strictness.STANDARD) as DriftModel.Outcome.Measured
        assertEquals(10.0, standard.driftUsed.value, 1e-9)
        val relaxed = DriftModel.maxExposure(BuiltInCameras.A1_II, 50.0, profile, Strictness.RELAXED) as DriftModel.Outcome.Measured
        assertEquals(2.0, relaxed.tolerancePx, 1e-9)
    }

    @Test
    fun `unmeasured built-in profile yields Unmeasured with no seconds`() {
        val outcome = DriftModel.maxExposure(
            BuiltInCameras.A1_II, 50.0, BuiltInTrackingProfiles.POLARIS_SINGLE_STAR, Strictness.STANDARD,
        )
        assertTrue(outcome is DriftModel.Outcome.Unmeasured)
        assertEquals(
            DriftModel.UnmeasuredReason.DRIFT_NOT_MEASURED,
            (outcome as DriftModel.Outcome.Unmeasured).reason,
        )
    }

    @Test
    fun `untracked profile passed by mistake yields Unmeasured with reason`() {
        val outcome = DriftModel.maxExposure(
            BuiltInCameras.A1_II, 50.0, BuiltInTrackingProfiles.UNTRACKED, Strictness.STANDARD,
        )
        assertTrue(outcome is DriftModel.Outcome.Unmeasured)
        assertEquals(
            DriftModel.UnmeasuredReason.PROFILE_NOT_TRACKED,
            (outcome as DriftModel.Outcome.Unmeasured).reason,
        )
    }
}
