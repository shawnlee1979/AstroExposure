package app.astroexposure.domain.sky

import app.astroexposure.domain.camera.BuiltInCameras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Composition rules: design §5 general rule 2 (linear-domain composition,
 * 4x moon degradation trigger) and §2.8 (missing baseline -> source NONE).
 * The R_sky assertions check the derivation in [skyElectronRate]'s KDoc:
 * f cancels, leaving p²/N² scaling; absolute value at 22 mpsas, A1 II,
 * f/2, QE 0.5 hand-evaluates to about 1.09 e-/px/s.
 */
class TotalSkyTest {

    @Test
    fun `compose adds natural floor baseline and moonlight linearly`() {
        val sky = TotalSky.compose(1.0, BaselineSource.ATLAS, 0.5)
        assertEquals(SkyBrightness.NATURAL_SKY_MCD_M2 + 1.5, sky.totalMcdPerM2, 1e-9)
        assertEquals(SkyBrightness.totalToMpsas(sky.totalMcdPerM2), sky.totalMpsas, 1e-9)
        assertEquals(BaselineSource.ATLAS, sky.baselineSource)
    }

    @Test
    fun `missing baseline is recorded as NONE with null bortle estimate`() {
        val sky = TotalSky.noBaseline(moonlightMcdPerM2 = 0.2)
        assertEquals(BaselineSource.NONE, sky.baselineSource)
        assertNull(sky.baselineArtificialMcdPerM2)
        assertNull(sky.bortleEstimate)
        assertEquals(SkyBrightness.NATURAL_SKY_MCD_M2 + 0.2, sky.totalMcdPerM2, 1e-9)
    }

    @Test
    fun `manual bortle baseline composes through representative mpsas`() {
        val sky = TotalSky.fromManualBortle(5, moonlightMcdPerM2 = 0.0)
        assertEquals(BaselineSource.MANUAL_BORTLE, sky.baselineSource)
        assertEquals(20.0, sky.totalMpsas, 0.01)
        assertEquals(5, sky.bortleEstimate)
    }

    @Test
    fun `moon degradation triggers at four times the baseline total`() {
        val baselineTotal = SkyBrightness.NATURAL_SKY_MCD_M2 // dark site, no artificial light
        val degraded = TotalSky.compose(0.0, BaselineSource.ATLAS, moonlightMcdPerM2 = 3.0 * baselineTotal)
        assertTrue(degraded.moonDegraded) // total = 4x baseline total exactly at the boundary (>=)
        val mild = TotalSky.compose(0.0, BaselineSource.ATLAS, moonlightMcdPerM2 = 0.1)
        assertFalse(mild.moonDegraded)
    }

    @Test
    fun `sky electron rate hand value at dark sky`() {
        val r = skyElectronRate(22.0, 2.0, BuiltInCameras.A1_II)
        assertEquals(1.09, r, 0.05)
    }

    @Test
    fun `sky electron rate scales with pixel area and inverse aperture squared`() {
        val a1 = skyElectronRate(20.0, 2.0, BuiltInCameras.A1_II)
        val zv = skyElectronRate(20.0, 2.0, BuiltInCameras.ZV_E1)
        // (8.40/4.16)^2 ≈ 4.08
        assertEquals(4.08, zv / a1, 0.05)
        val stopped = skyElectronRate(20.0, 4.0, BuiltInCameras.A1_II)
        assertEquals(4.0, a1 / stopped, 1e-9)
    }
}
