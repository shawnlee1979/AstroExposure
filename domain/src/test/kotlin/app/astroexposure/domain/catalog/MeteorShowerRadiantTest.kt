package app.astroexposure.domain.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The radiant drift extrapolation ([MeteorShower.radiantAt],
 * 0.17.3): RA is circular, so an extrapolation that crosses
 * 0 h / 24 h wraps instead of leaving the range the ephemeris
 * accepts; the declination moves by the same drift, unwrapped.
 */
class MeteorShowerRadiantTest {

    private fun shower(
        radiantRaHours: Double,
        radiantDecDeg: Double,
        driftRaPerDay: Double,
        driftDecPerDay: Double,
    ) = MeteorShower(
        iauCode = "TST",
        nameEn = "Test",
        nameZh = "测试",
        activityStartLon = 0.0,
        activityEndLon = 10.0,
        peakLon = 5.0,
        peakZhr = 10,
        radiantRaHours = radiantRaHours,
        radiantDecDeg = radiantDecDeg,
        driftRaPerDay = driftRaPerDay,
        driftDecPerDay = driftDecPerDay,
        parent = null,
        velocityKmS = 40.0,
        populationIndex = 2.5,
    )

    @Test
    fun `radiant at the peak is the catalogue position`() {
        val at = shower(3.1, 30.0, 0.05, 0.2).radiantAt(0.0)
        assertEquals(3.1, at.raHours, 1e-12)
        assertEquals(30.0, at.declination.value, 1e-12)
    }

    @Test
    fun `radiant extrapolation wraps past 24 hours`() {
        val at = shower(23.9, 10.0, 0.1, 0.0).radiantAt(3.0)
        assertEquals(0.2, at.raHours, 1e-9)
        assertTrue(at.raHours in 0.0..<24.0)
    }

    @Test
    fun `radiant extrapolation wraps below 0 hours`() {
        // The crash record's shape: a small negative RA.
        val at = shower(0.05, 10.0, -0.05, 0.0).radiantAt(2.644464728066841)
        assertEquals(23.917776763596658, at.raHours, 1e-9)
    }

    @Test
    fun `wrapRaHours keeps the circle's identity cases exact`() {
        assertEquals(0.0, app.astroexposure.domain.ephemeris.wrapRaHours(24.0), 0.0)
        assertEquals(0.0, app.astroexposure.domain.ephemeris.wrapRaHours(0.0), 0.0)
        assertEquals(23.5, app.astroexposure.domain.ephemeris.wrapRaHours(-0.5), 0.0)
        assertEquals(1.5, app.astroexposure.domain.ephemeris.wrapRaHours(49.5), 0.0)
    }
}
