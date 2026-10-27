package app.astroexposure.domain.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Basic shape checks for the lens library entry type (design §2.4). */
class LensTest {

    @Test
    fun `prime has equal focal bounds`() {
        val prime = Lens("x", "50mm F1.4", "E", 50.0, 50.0, 1.4)
        assertTrue(prime.isPrime)
        assertEquals(50.0, prime.focalMinMm, 1e-9)
    }

    @Test
    fun `zoom carries a focal range`() {
        val zoom = Lens("y", "24-70mm F2.8", "E", 24.0, 70.0, 2.8)
        assertFalse(zoom.isPrime)
        assertEquals(2.8, zoom.maxAperture, 1e-9)
    }

    @Test
    fun `constant aperture lens returns the same aperture at any focal length`() {
        val zoom = Lens("y", "24-70mm F2.8", "E", 24.0, 70.0, 2.8)
        for (focal in listOf(10.0, 24.0, 35.0, 50.0, 70.0, 100.0)) {
            assertEquals(2.8, zoom.maxApertureAt(focal), 1e-9)
        }
        val prime = Lens("x", "50mm F1.4", "E", 50.0, 50.0, 1.4)
        assertEquals(1.4, prime.maxApertureAt(50.0), 1e-9)
    }

    @Test
    fun `variable aperture zoom interpolates in log2 space between its endpoints`() {
        // An f/3.5-5.6 18-55mm kit zoom.
        val kit = Lens("kit", "18-55mm F3.5-5.6", "E", 18.0, 55.0, 3.5, maxApertureAtMaxFocal = 5.6)
        assertEquals(3.5, kit.maxApertureAt(18.0), 1e-9)
        assertEquals(5.6, kit.maxApertureAt(55.0), 1e-9)
        val mid = kit.maxApertureAt(36.5) // midpoint of the focal range
        assertTrue("mid $mid not strictly between 3.5 and 5.6", mid > 3.5 && mid < 5.6)
        // Log-space midpoint is the geometric mean of the endpoints.
        assertEquals(kotlin.math.sqrt(3.5 * 5.6), mid, 1e-9)
    }

    @Test
    fun `variable aperture zoom clamps out-of-range focal lengths to the endpoints`() {
        val kit = Lens("kit", "18-55mm F3.5-5.6", "E", 18.0, 55.0, 3.5, maxApertureAtMaxFocal = 5.6)
        assertEquals(3.5, kit.maxApertureAt(12.0), 1e-9)
        assertEquals(5.6, kit.maxApertureAt(80.0), 1e-9)
    }
}
