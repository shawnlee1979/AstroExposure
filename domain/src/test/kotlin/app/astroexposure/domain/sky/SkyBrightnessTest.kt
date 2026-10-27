package app.astroexposure.domain.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Goldens: the mpsas <-> luminance zero point (108000 cd/m² at 0 mag) puts
 * the Falchi natural sky 0.1712 mcd/m² at 22.0 mpsas. The Bortle mapping is
 * the empirical SQM correspondence recorded in [SkyBrightness]'s KDoc.
 */
class SkyBrightnessTest {

    @Test
    fun `natural sky converts to 22 mpsas`() {
        assertEquals(22.0, SkyBrightness.totalToMpsas(SkyBrightness.NATURAL_SKY_MCD_M2), 0.01)
        assertEquals(SkyBrightness.NATURAL_SKY_MCD_M2, SkyBrightness.mpsasToTotalMcd(22.0), 0.001)
    }

    @Test
    fun `mpsas round trip is consistent`() {
        for (m in listOf(22.0, 21.0, 19.5, 18.0)) {
            assertEquals(m, SkyBrightness.totalToMpsas(SkyBrightness.mpsasToTotalMcd(m)), 1e-9)
        }
    }

    @Test
    fun `bortle classes follow the empirical SQM ranges`() {
        assertEquals(1, SkyBrightness.bortleFromMpsas(21.99))
        assertEquals(2, SkyBrightness.bortleFromMpsas(21.70))
        assertEquals(3, SkyBrightness.bortleFromMpsas(21.40))
        assertEquals(4, SkyBrightness.bortleFromMpsas(20.90))
        assertEquals(5, SkyBrightness.bortleFromMpsas(20.00))
        assertEquals(6, SkyBrightness.bortleFromMpsas(19.20))
        assertEquals(7, SkyBrightness.bortleFromMpsas(18.65))
        assertEquals(8, SkyBrightness.bortleFromMpsas(18.10))
        assertEquals(9, SkyBrightness.bortleFromMpsas(17.50))
    }

    @Test
    fun `non-finite mpsas is rejected instead of silently mapping to a class`() {
        for (mpsas in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                SkyBrightness.bortleFromMpsas(mpsas)
            }
        }
    }

    @Test
    fun `representative mpsas maps back to its own class`() {
        for (b in 1..9) {
            assertEquals(b, SkyBrightness.bortleFromMpsas(SkyBrightness.representativeMpsasForBortle(b)))
        }
    }

    @Test
    fun `artificial component grows monotonically with bortle class`() {
        var prev = -1.0
        for (b in 1..9) {
            val artificial = SkyBrightness.artificialMcdForBortle(b)
            assertTrue("class $b artificial $artificial not > $prev", artificial > prev)
            prev = artificial
        }
        // Class 1 is essentially natural sky: artificial part is a small residue.
        assertTrue(SkyBrightness.artificialMcdForBortle(1) < 0.01)
    }
}
