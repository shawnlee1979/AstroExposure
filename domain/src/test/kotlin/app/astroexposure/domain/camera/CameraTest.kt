package app.astroexposure.domain.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Goldens: design §3.1/§3.6 verified pixel pitches (4.16 µm and 8.40 µm). */
class CameraTest {

    @Test
    fun `A1 II pixel pitch derives to 4_16 micrometers`() {
        assertEquals(4.16, BuiltInCameras.A1_II.pixelPitch.value, 0.01)
    }

    @Test
    fun `ZV-E1 pixel pitch derives to 8_40 micrometers`() {
        assertEquals(8.40, BuiltInCameras.ZV_E1.pixelPitch.value, 0.01)
    }

    @Test
    fun `both built-ins are effectively full frame`() {
        assertEquals(1.0, BuiltInCameras.A1_II.cropFactor, 0.02)
        assertEquals(1.0, BuiltInCameras.ZV_E1.cropFactor, 0.02)
    }

    // Phase-2 fields: goldens are the design §3.3/§3.4/§3.5 recorded values.

    @Test
    fun `A1 II dual gain points are 640 mechanical and 500 electronic`() {
        assertEquals(640, BuiltInCameras.A1_II.dualGainIsoMechanical)
        assertEquals(500, BuiltInCameras.A1_II.dualGainIsoElectronic)
        assertEquals(640, BuiltInCameras.A1_II.preferredDualGainIso)
        assertTrue(BuiltInCameras.A1_II.photoGainVerified)
    }

    @Test
    fun `A1 II read noise full well and file size match design section 3`() {
        assertEquals(1.1, BuiltInCameras.A1_II.readNoiseAtGainElectrons!!, 1e-9)
        assertEquals(3.5, BuiltInCameras.A1_II.readNoiseLowSegmentElectrons!!, 1e-9)
        assertEquals(39898.0, BuiltInCameras.A1_II.fullWellElectrons!!, 1e-9)
        assertTrue(BuiltInCameras.A1_II.fullWellIsReferenceValue)
        assertEquals(60.0, BuiltInCameras.A1_II.rawFileSizeMb!!, 1e-9)
    }

    @Test
    fun `ZV-E1 photo gain point stays unverified with fallback ISO 800`() {
        assertNull(BuiltInCameras.ZV_E1.dualGainIsoMechanical)
        assertNull(BuiltInCameras.ZV_E1.dualGainIsoElectronic)
        assertNull(BuiltInCameras.ZV_E1.preferredDualGainIso)
        assertFalse(BuiltInCameras.ZV_E1.photoGainVerified)
        assertEquals(800, BuiltInCameras.ZV_E1.fallbackDeepSkyIso)
    }

    @Test
    fun `ZV-E1 read noise full well and file size match design section 3`() {
        assertEquals(1.0, BuiltInCameras.ZV_E1.readNoiseAtGainElectrons!!, 1e-9)
        assertEquals(3.5, BuiltInCameras.ZV_E1.readNoiseLowSegmentElectrons!!, 1e-9)
        assertEquals(187035.0, BuiltInCameras.ZV_E1.fullWellElectrons!!, 1e-9)
        assertTrue(BuiltInCameras.ZV_E1.fullWellIsReferenceValue)
        assertEquals(14.0, BuiltInCameras.ZV_E1.rawFileSizeMb!!, 1e-9)
    }

    @Test
    fun `user defined camera defaults leave phase-2 fields empty`() {
        val custom = Camera("custom", "Custom", 36.0, 24.0, 6000, 4000)
        assertNull(custom.dualGainIsoMechanical)
        assertNull(custom.readNoiseAtGainElectrons)
        assertNull(custom.fullWellElectrons)
        assertFalse(custom.fullWellIsReferenceValue)
        assertTrue(custom.photoGainVerified)
        assertNull(custom.rawFileSizeMb)
    }
}
