package app.astroexposure.domain.camera

import org.junit.Assert.assertEquals
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
}
