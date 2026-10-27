package app.astroexposure.domain.model

import app.astroexposure.domain.camera.BuiltInCameras
import app.astroexposure.domain.camera.Camera
import app.astroexposure.domain.sky.BaselineSource
import app.astroexposure.domain.sky.SkyBrightness
import app.astroexposure.domain.sky.TotalSky
import app.astroexposure.domain.strictness.Strictness
import app.astroexposure.domain.units.Seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Goldens: design §5.3 worked example — RN = 1.1 e-, C = 10 -> C*RN² = 12.1;
 * at R = 0.5 e-/px/s t_sub = 24.2 s; at R = 0.125 t_sub = 96.8 s; C = 5
 * halves the sub-exposure. Tolerances as specified for this model.
 */
class DeepSkyModelTest {

    private fun skyAt(mpsas: Double): TotalSky = TotalSky.compose(
        baselineArtificialMcdPerM2 = SkyBrightness.mpsasToTotalMcd(mpsas) - SkyBrightness.NATURAL_SKY_MCD_M2,
        baselineSource = BaselineSource.ATLAS,
        moonlightMcdPerM2 = 0.0,
    )

    private fun ready(input: DeepSkyModel.Input): DeepSkyModel.Outcome.Ready =
        DeepSkyModel.evaluate(input) as DeepSkyModel.Outcome.Ready

    @Test
    fun `swamp arithmetic goldens from design example`() {
        assertEquals(12.1, 10 * 1.1 * 1.1, 0.05)
        assertEquals(24.2, DeepSkyModel.computeSubExposureSeconds(1.1, 10, 0.5), 0.5)
        assertEquals(96.8, DeepSkyModel.computeSubExposureSeconds(1.1, 10, 0.125), 2.0)
        assertEquals(12.1, DeepSkyModel.computeSubExposureSeconds(1.1, 5, 0.5), 0.25)
    }

    @Test
    fun `A1 II recommends gain point ISO 640 with gain segment read noise`() {
        val r = ready(
            DeepSkyModel.Input(
                camera = BuiltInCameras.A1_II, aperture = 2.0, totalSky = skyAt(20.0),
                strictness = Strictness.STANDARD, trackingCapSeconds = null,
                targetClass = DeepSkyModel.TargetClass.BRIGHT_CLUSTER_NEBULA,
            ),
        )
        assertEquals(640, r.recommendedIso)
        assertFalse(r.unverifiedGainFlag)
        assertFalse(r.belowGainPenaltyFlag)
        assertEquals(1.1, r.readNoiseElectrons, 1e-9)
        assertEquals(12.1, r.targetBackgroundElectrons, 0.05)
        assertEquals(5, r.otherC)
        // Other-C comparison is the raw (uncapped) half of the C=10 value here.
        assertEquals(r.subExposureSeconds / 2, r.subExposureOtherCSeconds, 0.01)
        assertFalse(r.otherCComparisonUncapped)
        assertTrue(r.capReasons.isEmpty())
        assertEquals(1.5, r.totalHoursRepresentative, 1e-9)
    }

    @Test
    fun `ZV-E1 falls back to ISO 800 flagged unverified`() {
        val r = ready(
            DeepSkyModel.Input(
                camera = BuiltInCameras.ZV_E1, aperture = 2.0, totalSky = skyAt(20.0),
                strictness = Strictness.STANDARD, trackingCapSeconds = null,
                targetClass = DeepSkyModel.TargetClass.GENERAL_GALAXY_DARK_NEBULA,
            ),
        )
        assertEquals(800, r.recommendedIso)
        assertTrue(r.unverifiedGainFlag)
        assertEquals(1.0, r.readNoiseElectrons, 1e-9)
        assertEquals(4.0, r.totalHoursRepresentative, 1e-9)
    }

    @Test
    fun `chosen ISO below gain point pays the read noise penalty`() {
        val r = ready(
            DeepSkyModel.Input(
                camera = BuiltInCameras.A1_II, aperture = 2.0, totalSky = skyAt(20.0),
                strictness = Strictness.STANDARD, trackingCapSeconds = null,
                targetClass = DeepSkyModel.TargetClass.BRIGHT_CLUSTER_NEBULA,
                chosenIso = 400,
            ),
        )
        assertTrue(r.belowGainPenaltyFlag)
        assertEquals(3.5, r.readNoiseElectrons, 1e-9)
        assertEquals(122.5, r.targetBackgroundElectrons, 0.05)
    }

    @Test
    fun `tracking cap and single frame ceiling are recorded`() {
        val capped = ready(
            DeepSkyModel.Input(
                camera = BuiltInCameras.A1_II, aperture = 2.0, totalSky = skyAt(20.0),
                strictness = Strictness.STANDARD, trackingCapSeconds = Seconds(1.0),
                targetClass = DeepSkyModel.TargetClass.BRIGHT_CLUSTER_NEBULA,
            ),
        )
        assertEquals(1.0, capped.subExposureSeconds, 1e-9)
        assertTrue(DeepSkyModel.CapReason.TRACKING_LIMIT in capped.capReasons)
        // The other-C raw value (about 0.88 s here) does not exceed the
        // 1 s tracking cap, so it needs no uncapped-comparison label.
        assertFalse(capped.otherCComparisonUncapped)

        val comparisonAboveCap = ready(
            DeepSkyModel.Input(
                camera = BuiltInCameras.A1_II, aperture = 2.0, totalSky = skyAt(20.0),
                strictness = Strictness.STANDARD, trackingCapSeconds = Seconds(0.5),
                targetClass = DeepSkyModel.TargetClass.BRIGHT_CLUSTER_NEBULA,
            ),
        )
        assertEquals(0.5, comparisonAboveCap.subExposureSeconds, 1e-9)
        assertTrue(comparisonAboveCap.subExposureOtherCSeconds > 0.5)
        assertTrue(comparisonAboveCap.otherCComparisonUncapped)

        val ceiling = ready(
            DeepSkyModel.Input(
                camera = BuiltInCameras.A1_II, aperture = 16.0, totalSky = skyAt(22.0),
                strictness = Strictness.STANDARD, trackingCapSeconds = null,
                targetClass = DeepSkyModel.TargetClass.BRIGHT_CLUSTER_NEBULA,
                chosenIso = 100,
            ),
        )
        assertEquals(600.0, ceiling.subExposureSeconds, 1e-9)
        assertTrue(DeepSkyModel.CapReason.SINGLE_FRAME_CEILING in ceiling.capReasons)
        assertTrue(ceiling.subExposureOtherCSeconds > DeepSkyModel.SINGLE_FRAME_CAP_SECONDS)
        assertTrue(ceiling.otherCComparisonUncapped)
    }

    @Test
    fun `frame count and data volume follow the budget arithmetic`() {
        // Tracking cap of 1 s makes the sub-exposure exact: 1.5 h -> 5400 frames.
        val r = ready(
            DeepSkyModel.Input(
                camera = BuiltInCameras.A1_II, aperture = 2.0, totalSky = skyAt(20.0),
                strictness = Strictness.STANDARD, trackingCapSeconds = Seconds(1.0),
                targetClass = DeepSkyModel.TargetClass.BRIGHT_CLUSTER_NEBULA,
            ),
        )
        assertEquals(1.0, r.subExposureSeconds, 1e-9)
        assertEquals(5400, r.frameCount)
        assertEquals(5400 * 60.0 / 1024.0, r.dataGb!!, 1e-9)
    }

    @Test
    fun `missing read noise degrades without seconds`() {
        val bare = Camera("bare", "Bare", 36.0, 24.0, 6000, 4000)
        val outcome = DeepSkyModel.evaluate(
            DeepSkyModel.Input(
                camera = bare, aperture = 2.0, totalSky = skyAt(20.0),
                strictness = Strictness.STANDARD, trackingCapSeconds = null,
                targetClass = DeepSkyModel.TargetClass.BRIGHT_CLUSTER_NEBULA,
            ),
        )
        assertTrue(outcome is DeepSkyModel.Outcome.Degraded)
        val d = outcome as DeepSkyModel.Outcome.Degraded
        assertEquals(DeepSkyModel.DegradedReason.READ_NOISE_MISSING, d.reason)
        assertEquals(null, d.targetBackgroundElectrons)
    }

    @Test
    fun `missing sky baseline degrades but keeps the target electron count`() {
        val outcome = DeepSkyModel.evaluate(
            DeepSkyModel.Input(
                camera = BuiltInCameras.A1_II, aperture = 2.0, totalSky = TotalSky.noBaseline(0.0),
                strictness = Strictness.STANDARD, trackingCapSeconds = null,
                targetClass = DeepSkyModel.TargetClass.BRIGHT_CLUSTER_NEBULA,
            ),
        )
        assertTrue(outcome is DeepSkyModel.Outcome.Degraded)
        val d = outcome as DeepSkyModel.Outcome.Degraded
        assertEquals(DeepSkyModel.DegradedReason.SKY_BASELINE_MISSING, d.reason)
        assertEquals(12.1, d.targetBackgroundElectrons!!, 0.05)
    }

    @Test
    fun `relaxed tier uses C 5 as the primary value`() {
        val r = ready(
            DeepSkyModel.Input(
                camera = BuiltInCameras.A1_II, aperture = 2.0, totalSky = skyAt(20.0),
                strictness = Strictness.RELAXED, trackingCapSeconds = null,
                targetClass = DeepSkyModel.TargetClass.VERY_FAINT,
            ),
        )
        assertEquals(5, r.swampC)
        assertEquals(10, r.otherC)
        assertEquals(6.05, r.targetBackgroundElectrons, 0.05)
        assertEquals(6.0, r.totalHoursRepresentative, 1e-9)
    }

    @Test
    fun `QE defaulting is flagged and a recorded QE is honoured`() {
        fun inputFor(camera: Camera) = DeepSkyModel.Input(
            camera = camera, aperture = 2.0, totalSky = skyAt(20.0),
            strictness = Strictness.STANDARD, trackingCapSeconds = null,
            targetClass = DeepSkyModel.TargetClass.BRIGHT_CLUSTER_NEBULA,
        )
        // A1 II records no QE reference (design SS2.3 allows empty): the
        // documented default applies and the result says so.
        val defaulted = ready(inputFor(BuiltInCameras.A1_II))
        assertTrue(defaulted.qeDefaulted)
        val withQe = ready(inputFor(BuiltInCameras.A1_II.copy(quantumEfficiencyReference = 0.62)))
        assertFalse(withQe.qeDefaulted)
        // Rate scales linearly with QE: 0.62 / 0.5 = 1.24x.
        assertEquals(1.24, withQe.skyElectronRatePerPixelSec / defaulted.skyElectronRatePerPixelSec, 0.01)
    }
}
