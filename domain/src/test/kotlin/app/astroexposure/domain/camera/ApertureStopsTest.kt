package app.astroexposure.domain.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The standard aperture scale and the per-lens selectable sequence
 * (UI aperture picker's single data source).
 */
class ApertureStopsTest {

    @Test
    fun `standard scale runs from f1 to f22`() {
        assertEquals(1.0, ApertureStops.STANDARD_SCALE.first(), 1e-9)
        assertEquals(22.0, ApertureStops.STANDARD_SCALE.last(), 1e-9)
        assertEquals(28, ApertureStops.STANDARD_SCALE.size)
    }

    @Test
    fun `scaleFrom on-scale maximum starts at that stop`() {
        val scale = ApertureStops.scaleFrom(1.4)
        assertEquals(1.4, scale.first(), 1e-9)
        assertTrue(scale.contains(2.8))
        assertTrue(scale.contains(22.0))
    }

    @Test
    fun `scaleFrom off-scale maximum starts with the lens value itself`() {
        val scale = ApertureStops.scaleFrom(2.9)
        assertEquals(2.9, scale.first(), 1e-9)
        assertEquals(3.2, scale[1], 1e-9)
    }

    @Test
    fun `scaleFrom f1_2 contains both 1_2 and the next standard stop`() {
        val scale = ApertureStops.scaleFrom(1.2)
        assertTrue(scale.contains(1.2))
        assertTrue(scale.contains(1.4))
        assertEquals(1.2, scale.first(), 1e-9)
    }

    @Test
    fun `sequences are strictly ascending and end at f22`() {
        for (maxAperture in listOf(1.0, 1.2, 1.4, 1.9, 2.0, 2.8, 2.9, 4.0, 5.6)) {
            val scale = ApertureStops.scaleFrom(maxAperture)
            assertEquals("max $maxAperture last", 22.0, scale.last(), 1e-9)
            for (i in 1 until scale.size) {
                assertTrue(
                    "max $maxAperture: ${scale[i]} not > ${scale[i - 1]}",
                    scale[i] > scale[i - 1],
                )
            }
            assertEquals("max $maxAperture has duplicates", scale.size, scale.distinct().size)
        }
    }
}
