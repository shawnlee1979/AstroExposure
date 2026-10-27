package app.astroexposure.domain.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Goldens: Krisciunas & Schaefer (1991) as reproduced in ING TN127 —
 * I0(0°) = 10^(-0.4*3.84) ≈ 0.0291; phase angle from illumination via
 * cos(phi) = 2*illum - 1. Absolute sky values are order-of-magnitude
 * assertions (the model's stated ±50% footing, design §5 general rule 2).
 */
class MoonlightModelTest {

    @Test
    fun `phase angle recovers from illumination`() {
        assertEquals(0.0, MoonlightModel.phaseAngleDegrees(1.0), 1e-9)
        assertEquals(90.0, MoonlightModel.phaseAngleDegrees(0.5), 1e-9)
        assertEquals(180.0, MoonlightModel.phaseAngleDegrees(0.0), 1e-9)
    }

    @Test
    fun `phase function at full moon matches KS91 constant`() {
        assertEquals(0.0291, MoonlightModel.phaseFunctionI0(1.0), 0.001)
    }

    @Test
    fun `moon below horizon contributes zero`() {
        val v = MoonlightModel.contributionMcdPerM2(1.0, -5.0, 90.0, 60.0)
        assertEquals(0.0, v, 1e-12)
    }

    @Test
    fun `full moon at zenith 90 degrees from target is order of a few mcd`() {
        // Hand evaluation of the KS91 formula for this geometry gives about 3.8 mcd/m².
        val v = MoonlightModel.contributionMcdPerM2(1.0, 90.0, 90.0, 90.0)
        assertTrue("full-moon contribution $v outside 2.0..6.0 mcd/m²", v in 2.0..6.0)
    }

    @Test
    fun `half moon contributes roughly a tenth of full moon`() {
        val full = MoonlightModel.contributionMcdPerM2(1.0, 90.0, 90.0, 90.0)
        val half = MoonlightModel.contributionMcdPerM2(0.5, 90.0, 90.0, 90.0)
        val ratio = half / full
        assertTrue("half/full ratio $ratio outside 0.05..0.15", ratio in 0.05..0.15)
    }

    @Test
    fun `contribution falls with separation and with low moon`() {
        val near = MoonlightModel.contributionMcdPerM2(1.0, 60.0, 30.0, 60.0)
        val far = MoonlightModel.contributionMcdPerM2(1.0, 60.0, 120.0, 60.0)
        assertTrue(near > far)
        val high = MoonlightModel.contributionMcdPerM2(1.0, 80.0, 90.0, 60.0)
        val low = MoonlightModel.contributionMcdPerM2(1.0, 10.0, 90.0, 60.0)
        assertTrue(high > low)
    }
}
