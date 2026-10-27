package app.astroexposure.domain.model

import app.astroexposure.domain.camera.BuiltInCameras
import app.astroexposure.domain.sky.BaselineSource
import app.astroexposure.domain.sky.SkyBrightness
import app.astroexposure.domain.sky.TotalSky
import app.astroexposure.domain.units.Seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The untracked ISO rule is the empirical rule recorded in
 * [WideFieldModel]'s KDoc (design §5.2.1): anchor ranges at 21.75 mpsas,
 * one stop down per magnitude of brighter sky, floor ISO 400.
 */
class WideFieldModelTest {

    private fun skyAt(mpsas: Double): TotalSky = TotalSky.compose(
        baselineArtificialMcdPerM2 = SkyBrightness.mpsasToTotalMcd(mpsas) - SkyBrightness.NATURAL_SKY_MCD_M2,
        baselineSource = BaselineSource.ATLAS,
        moonlightMcdPerM2 = 0.0,
    )

    @Test
    fun `dark sky anchor ranges match design`() {
        val a1 = WideFieldModel.untrackedIso(skyAt(21.75), BuiltInCameras.A1_II)
        assertEquals(3200, a1.low); assertEquals(6400, a1.high)
        val zv = WideFieldModel.untrackedIso(skyAt(21.75), BuiltInCameras.ZV_E1)
        assertEquals(3200, zv.low); assertEquals(12800, zv.high)
    }

    @Test
    fun `custom bodies split at the 6-3 um nearest-anchor boundary`() {
        // The supervisor's counterexample: a 5.5 um body is nearer the
        // A1 II anchor (4.16 um) than the ZV-E1 anchor (8.40 um), so it
        // must take the A1 II range; the old 5 um split gave it the
        // ZV-E1 range instead.
        val at55 = app.astroexposure.domain.camera.Camera("custom-5.5", "Custom 5.5", 33.0, 22.0, 6000, 4000)
        val range55 = WideFieldModel.untrackedIso(skyAt(21.75), at55)
        assertEquals(3200, range55.low); assertEquals(6400, range55.high)
        // At the split itself the body still takes the A1 II anchor.
        val at63 = app.astroexposure.domain.camera.Camera("custom-6.3", "Custom 6.3", 63.0, 42.0, 10000, 6667)
        val range63 = WideFieldModel.untrackedIso(skyAt(21.75), at63)
        assertEquals(3200, range63.low); assertEquals(6400, range63.high)
        // Just above the split the ZV-E1 anchor applies.
        val at631 = app.astroexposure.domain.camera.Camera("custom-6.31", "Custom 6.31", 63.1, 42.0, 10000, 6667)
        val range631 = WideFieldModel.untrackedIso(skyAt(21.75), at631)
        assertEquals(3200, range631.low); assertEquals(12800, range631.high)
    }

    @Test
    fun `two magnitudes brighter shifts the range down two stops`() {
        val a1 = WideFieldModel.untrackedIso(skyAt(19.75), BuiltInCameras.A1_II)
        assertEquals(800, a1.low); assertEquals(1600, a1.high)
    }

    @Test
    fun `very bright sky floors at ISO 400`() {
        val a1 = WideFieldModel.untrackedIso(skyAt(16.75), BuiltInCameras.A1_II)
        assertEquals(400, a1.low); assertEquals(400, a1.high)
    }

    @Test
    fun `tracked settings pick the largest tidy shutter within the cap`() {
        assertEquals(90.0, WideFieldModel.trackedSettings(BuiltInCameras.A1_II, Seconds(103.0)).shutter.value, 1e-9)
        assertEquals(30.0, WideFieldModel.trackedSettings(BuiltInCameras.A1_II, Seconds(45.0)).shutter.value, 1e-9)
        assertEquals(20.0, WideFieldModel.trackedSettings(BuiltInCameras.A1_II, Seconds(20.0)).shutter.value, 1e-9)
        assertEquals(120.0, WideFieldModel.trackedSettings(BuiltInCameras.A1_II, Seconds(206.0)).shutter.value, 1e-9)
    }

    @Test
    fun `tracked ISO adsorbs to the gain point or the flagged fallback`() {
        val a1 = WideFieldModel.trackedSettings(BuiltInCameras.A1_II, Seconds(60.0))
        assertEquals(640, a1.iso); assertFalse(a1.unverifiedGainFlag)
        val zv = WideFieldModel.trackedSettings(BuiltInCameras.ZV_E1, Seconds(60.0))
        assertEquals(800, zv.iso); assertTrue(zv.unverifiedGainFlag)
    }
}
