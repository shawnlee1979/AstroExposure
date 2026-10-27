package app.astroexposure.domain.model

import app.astroexposure.domain.units.Degrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Goldens: design §5.4.1 — Looney 11 base case and its worked EV example,
 * the phase-correction table (incl. boundary rule), and the low-altitude
 * correction. §8.6 requires Looney conversions exact on the 1/3-stop scale.
 */
class MoonModelTest {

    private fun moon(iso: Int, aperture: Double, illumination: Double, altitudeDeg: Double = 45.0) =
        MoonModel.evaluate(MoonModel.Input(iso, aperture, illumination, Degrees(altitudeDeg)))

    @Test
    fun `Looney 11 base - full moon ISO 100 f11 gives 1-100`() {
        val r = moon(iso = 100, aperture = 11.0, illumination = 0.99)
        assertEquals(100, r.shutterDenominator)
        assertEquals(0.0, r.phaseCorrectionStops, 1e-9)
        assertFalse(r.lowAltitudeCorrectionApplied)
    }

    @Test
    fun `design worked example - full moon ISO 100 f2_8 gives 1-1600`() {
        val r = moon(iso = 100, aperture = 2.8, illumination = 0.99)
        assertEquals(1600, r.shutterDenominator)
    }

    @Test
    fun `phase table bands`() {
        assertEquals(0.0, MoonModel.phaseCorrectionStops(0.99), 1e-9)
        assertEquals(1.0, MoonModel.phaseCorrectionStops(0.85), 1e-9)
        assertEquals(1.5, MoonModel.phaseCorrectionStops(0.68), 1e-9)
        assertEquals(2.0, MoonModel.phaseCorrectionStops(0.50), 1e-9)
        assertEquals(3.0, MoonModel.phaseCorrectionStops(0.25), 1e-9)
        assertEquals(4.0, MoonModel.phaseCorrectionStops(0.08), 1e-9)
    }

    @Test
    fun `phase table boundaries belong to the higher band`() {
        // Design boundary rule: 0.97 -> full band, 0.75 -> 75-97 band,
        // 0.60 -> 60-75 band, 0.40 -> 40-60 band, 0.15 -> 15-40 band.
        assertEquals(0.0, MoonModel.phaseCorrectionStops(0.97), 1e-9)
        assertEquals(1.0, MoonModel.phaseCorrectionStops(0.75), 1e-9)
        assertEquals(1.5, MoonModel.phaseCorrectionStops(0.60), 1e-9)
        assertEquals(2.0, MoonModel.phaseCorrectionStops(0.40), 1e-9)
        assertEquals(3.0, MoonModel.phaseCorrectionStops(0.15), 1e-9)
    }

    @Test
    fun `half moon adds two stops`() {
        // ISO 100, f/11, illumination 50% -> 1/100 slowed by 2 stops = 1/25.
        val r = moon(iso = 100, aperture = 11.0, illumination = 0.50)
        assertEquals(25, r.shutterDenominator)
    }

    @Test
    fun `low altitude adds one stop`() {
        val r = moon(iso = 100, aperture = 11.0, illumination = 0.99, altitudeDeg = 10.0)
        assertTrue(r.lowAltitudeCorrectionApplied)
        assertEquals(50, r.shutterDenominator)
    }

    @Test
    fun `ISO scales shutter inversely`() {
        val r = moon(iso = 400, aperture = 11.0, illumination = 0.99)
        assertEquals(400, r.shutterDenominator)
    }
}
