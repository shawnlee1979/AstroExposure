package app.astroexposure.domain.model

import app.astroexposure.domain.camera.BuiltInCameras
import app.astroexposure.domain.strictness.Strictness
import app.astroexposure.domain.units.Degrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Goldens: design §5.2.1 worked table — 50 mm, galactic centre declination
 * -29°, k = 1 (STRICT). Design values are rounded to 0.1 s; tolerance 0.06 s
 * covers that rounding.
 */
class NpfModelTest {

    private fun npf(camera: app.astroexposure.domain.camera.Camera, aperture: Double) =
        NpfModel.evaluate(
            NpfModel.Input(
                camera = camera,
                focalLengthMm = 50.0,
                aperture = aperture,
                declination = Degrees(-29.0),
                strictness = Strictness.STRICT,
            ),
        ).maxShutter.value

    @Test
    fun `design table A1 II 50mm dec -29 k1`() {
        assertEquals(2.0, npf(BuiltInCameras.A1_II, 1.4), 0.06)
        assertEquals(2.2, npf(BuiltInCameras.A1_II, 2.0), 0.06)
        assertEquals(2.5, npf(BuiltInCameras.A1_II, 2.8), 0.06)
    }

    @Test
    fun `design table ZV-E1 50mm dec -29 k1`() {
        assertEquals(3.3, npf(BuiltInCameras.ZV_E1, 1.4), 0.06)
        assertEquals(3.5, npf(BuiltInCameras.ZV_E1, 2.0), 0.06)
        assertEquals(3.8, npf(BuiltInCameras.ZV_E1, 2.8), 0.06)
    }

    @Test
    fun `non-positive focal length or aperture is rejected`() {
        for (focal in listOf(0.0, -50.0, Double.NaN)) {
            assertThrows(IllegalArgumentException::class.java) {
                NpfModel.evaluate(
                    NpfModel.Input(BuiltInCameras.A1_II, focal, 2.0, Degrees(-29.0), Strictness.STRICT),
                )
            }
        }
        for (aperture in listOf(0.0, -2.0, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                NpfModel.evaluate(
                    NpfModel.Input(BuiltInCameras.A1_II, 50.0, aperture, Degrees(-29.0), Strictness.STRICT),
                )
            }
        }
    }

    @Test
    fun `rule of 500 comparison at 50mm is 10s`() {
        val r = NpfModel.evaluate(
            NpfModel.Input(BuiltInCameras.A1_II, 50.0, 2.0, Degrees(-29.0), Strictness.STRICT),
        )
        assertEquals(10.0, r.ruleOf500.value, 0.15)
    }

    @Test
    fun `k scales linearly with strictness tier`() {
        val strict = npf(BuiltInCameras.A1_II, 2.0)
        val standard = NpfModel.evaluate(
            NpfModel.Input(BuiltInCameras.A1_II, 50.0, 2.0, Degrees(-29.0), Strictness.STANDARD),
        ).maxShutter.value
        val relaxed = NpfModel.evaluate(
            NpfModel.Input(BuiltInCameras.A1_II, 50.0, 2.0, Degrees(-29.0), Strictness.RELAXED),
        ).maxShutter.value
        assertEquals(strict * 2, standard, 1e-9)
        assertEquals(strict * 3, relaxed, 1e-9)
    }

    @Test
    fun `declination beyond 85 degrees is capped and flagged`() {
        val r = NpfModel.evaluate(
            NpfModel.Input(BuiltInCameras.A1_II, 50.0, 2.0, Degrees(89.0), Strictness.STRICT),
        )
        assertTrue(r.declinationCapped)
        val atCap = NpfModel.evaluate(
            NpfModel.Input(BuiltInCameras.A1_II, 50.0, 2.0, Degrees(85.0), Strictness.STRICT),
        )
        assertFalse(atCap.declinationCapped)
        assertEquals(atCap.maxShutter.value, r.maxShutter.value, 1e-9)
    }
}
