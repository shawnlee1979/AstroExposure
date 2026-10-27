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
