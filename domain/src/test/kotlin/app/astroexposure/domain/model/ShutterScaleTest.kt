package app.astroexposure.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct tests for the shared shutter scale (previously covered only
 * indirectly through the Moon and Eclipse models).
 */
class ShutterScaleTest {

    @Test
    fun `scale is sorted, unique and spans 30 s to 1-8000`() {
        val scale = ShutterScale.SCALE_SECONDS
        assertEquals(scale.sorted(), scale)
        assertEquals(scale.size, scale.toSet().size)
        assertEquals(30.0, scale.max(), 1e-9)
        assertEquals(1.0 / 8000, scale.min(), 1e-12)
    }

    @Test
    fun `snap returns exact scale entries unchanged`() {
        for (entry in listOf(30.0, 15.0, 0.5, 1.0 / 60, 1.0 / 250, 1.0 / 8000)) {
            assertEquals(entry, ShutterScale.snap(entry).value, 1e-12)
        }
    }

    @Test
    fun `snap picks the log-space nearest entry`() {
        // 1/100 s is itself a scale entry's neighbour: 0.011 s is closer
        // (log space) to 1/100 than to 1/80 or 1/125.
        assertEquals(1.0 / 100, ShutterScale.snap(0.011).value, 1e-12)
        // 3 s lies between the 1/3-stop entries 2.976 s and 3.75 s, much
        // closer (log space) to the former.
        assertEquals(2.976, ShutterScale.snap(3.0).value, 0.01)
    }

    @Test
    fun `snap rejects non-positive durations`() {
        assertThrows(IllegalArgumentException::class.java) { ShutterScale.snap(0.0) }
        assertThrows(IllegalArgumentException::class.java) { ShutterScale.snap(-1.0) }
    }

    @Test
    fun `denominatorOf reports sub-second denominators only`() {
        assertEquals(100, ShutterScale.denominatorOf(app.astroexposure.domain.units.Seconds(1.0 / 100)))
        assertEquals(0, ShutterScale.denominatorOf(app.astroexposure.domain.units.Seconds(2.0)))
    }
}
