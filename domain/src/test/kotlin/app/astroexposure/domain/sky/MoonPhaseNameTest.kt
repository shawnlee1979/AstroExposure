package app.astroexposure.domain.sky

import app.astroexposure.domain.units.Degrees
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase-name classification (ui-design §7.4): eight 45° sectors over
 * the phase angle θ, lower bound inclusive, no gaps. Every sector
 * boundary is pinned from both sides.
 */
class MoonPhaseNameTest {

    @Test
    fun sectorCentresMapToTheirNames() {
        assertEquals(MoonPhaseName.NEW_MOON, MoonPhaseNames.ofDegrees(0.0))
        assertEquals(MoonPhaseName.WAXING_CRESCENT, MoonPhaseNames.ofDegrees(45.0))
        assertEquals(MoonPhaseName.FIRST_QUARTER, MoonPhaseNames.ofDegrees(90.0))
        assertEquals(MoonPhaseName.WAXING_GIBBOUS, MoonPhaseNames.ofDegrees(135.0))
        assertEquals(MoonPhaseName.FULL_MOON, MoonPhaseNames.ofDegrees(180.0))
        assertEquals(MoonPhaseName.WANING_GIBBOUS, MoonPhaseNames.ofDegrees(225.0))
        assertEquals(MoonPhaseName.LAST_QUARTER, MoonPhaseNames.ofDegrees(270.0))
        assertEquals(MoonPhaseName.WANING_CRESCENT, MoonPhaseNames.ofDegrees(315.0))
    }

    @Test
    fun everyBoundaryBelongsToTheNextSector() {
        // (boundary, name just below, name at/just above)
        val cases = listOf(
            Triple(22.5, MoonPhaseName.NEW_MOON, MoonPhaseName.WAXING_CRESCENT),
            Triple(67.5, MoonPhaseName.WAXING_CRESCENT, MoonPhaseName.FIRST_QUARTER),
            Triple(112.5, MoonPhaseName.FIRST_QUARTER, MoonPhaseName.WAXING_GIBBOUS),
            Triple(157.5, MoonPhaseName.WAXING_GIBBOUS, MoonPhaseName.FULL_MOON),
            Triple(202.5, MoonPhaseName.FULL_MOON, MoonPhaseName.WANING_GIBBOUS),
            Triple(247.5, MoonPhaseName.WANING_GIBBOUS, MoonPhaseName.LAST_QUARTER),
            Triple(292.5, MoonPhaseName.LAST_QUARTER, MoonPhaseName.WANING_CRESCENT),
            Triple(337.5, MoonPhaseName.WANING_CRESCENT, MoonPhaseName.NEW_MOON),
        )
        for ((boundary, below, atAndAbove) in cases) {
            assertEquals("just below $boundary", below, MoonPhaseNames.ofDegrees(boundary - 0.1))
            assertEquals("at $boundary", atAndAbove, MoonPhaseNames.ofDegrees(boundary))
            assertEquals("just above $boundary", atAndAbove, MoonPhaseNames.ofDegrees(boundary + 0.1))
        }
    }

    @Test
    fun anglesNormaliseIntoTheCircle() {
        assertEquals(MoonPhaseName.NEW_MOON, MoonPhaseNames.ofDegrees(360.0))
        assertEquals(MoonPhaseName.FULL_MOON, MoonPhaseNames.ofDegrees(540.0))
        assertEquals(MoonPhaseName.WANING_CRESCENT, MoonPhaseNames.ofDegrees(-45.0))
        assertEquals(MoonPhaseName.NEW_MOON, MoonPhaseNames.of(Degrees(359.9)))
    }
}
