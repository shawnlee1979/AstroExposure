package app.astroexposure.domain.model

import app.astroexposure.domain.catalog.EclipseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Goldens: design §5.4.2 table cells at ISO 100 (Lodriguss/AstroPix table
 * structure) — partial phase f/8 = 1/1000, corona 1.0 Rs f/8 = 1/4 s,
 * corona 4.0 Rs f/2.8 = 1/4 s; ISO 200 shifts everything one stop faster.
 * The annular lock and the gate block are safety-critical branches.
 */
class EclipseModelTest {

    private fun denom(phenomenon: EclipseModel.Phenomenon, aperture: Double, iso: Int = 100): Int =
        app.astroexposure.domain.model.ShutterScale.denominatorOf(
            EclipseModel.shutterFor(phenomenon, aperture, iso)!!,
        )

    @Test
    fun `partial phase f8 ISO 100 is 1-1000`() {
        assertEquals(1000, denom(EclipseModel.Phenomenon.PARTIAL_PHASE, 8.0))
    }

    @Test
    fun `corona 1 Rs f8 ISO 100 is a quarter second`() {
        val s = EclipseModel.shutterFor(EclipseModel.Phenomenon.CORONA_1_0_RS, 8.0, 100)!!
        assertEquals(0.25, s.value, 1e-9)
    }

    @Test
    fun `corona 4 Rs f2_8 ISO 100 is a quarter second`() {
        val s = EclipseModel.shutterFor(EclipseModel.Phenomenon.CORONA_4_0_RS, 2.8, 100)!!
        assertEquals(0.25, s.value, 1e-9)
    }

    @Test
    fun `ISO 200 shifts the whole table one stop faster`() {
        assertEquals(2000, denom(EclipseModel.Phenomenon.PARTIAL_PHASE, 8.0, iso = 200))
        val s = EclipseModel.shutterFor(EclipseModel.Phenomenon.CORONA_1_0_RS, 8.0, 200)!!
        assertEquals(0.125, s.value, 0.01) // 1/8 s on the 1/3-stop scale
    }

    @Test
    fun `recorded-empty cells return null instead of a fabricated value`() {
        assertNull(EclipseModel.shutterFor(EclipseModel.Phenomenon.BAILY_BEADS, 2.8, 100))
        assertNull(EclipseModel.tableSeconds(EclipseModel.Phenomenon.CHROMOSPHERE, 2.8))
    }

    @Test
    fun `unrecorded aperture uses nearest column plus EV correction`() {
        // f/4 is not a recorded column; nearest is f/5.6, EV-corrected:
        // prominence f/5.6 = 1/2000, opening to f/4 is one stop -> about 1/4000.
        assertEquals(4000, denom(EclipseModel.Phenomenon.PROMINENCE, 4.0))
    }

    @Test
    fun `filter requirements follow the phase classes`() {
        assertEquals(
            EclipseModel.FilterRequirement.REQUIRED_ND5,
            EclipseModel.filterRequirement(EclipseModel.Phenomenon.PARTIAL_PHASE),
        )
        assertEquals(
            EclipseModel.FilterRequirement.NONE_TOTALITY_ONLY,
            EclipseModel.filterRequirement(EclipseModel.Phenomenon.CORONA_2_0_RS),
        )
        assertEquals(
            EclipseModel.FilterRequirement.TRANSITION_SECONDS,
            EclipseModel.filterRequirement(EclipseModel.Phenomenon.DIAMOND_RING),
        )
        assertEquals(
            EclipseModel.FilterRequirement.TRANSITION_SECONDS,
            EclipseModel.filterRequirement(EclipseModel.Phenomenon.BAILY_BEADS),
        )
    }

    @Test
    fun `annular and partial events lock the table to the partial row only`() {
        for (type in listOf(EclipseType.ANNULAR, EclipseType.PARTIAL)) {
            val table = EclipseModel.tableFor(type)
            assertTrue(table.lockedToFilter)
            assertEquals(listOf(EclipseModel.Phenomenon.PARTIAL_PHASE), table.rows)
        }
    }

    @Test
    fun `total events return the full unlocked table`() {
        val table = EclipseModel.tableFor(EclipseType.TOTAL)
        assertFalse(table.lockedToFilter)
        assertEquals(EclipseModel.Phenomenon.entries.size, table.rows.size)
    }

    @Test
    fun `gate blocks until acknowledged`() {
        assertTrue(SafetyGate.evaluate(acknowledged = false, EclipseType.TOTAL).blocked)
        assertFalse(SafetyGate.evaluate(acknowledged = true, EclipseType.TOTAL).blocked)
    }

    @Test
    fun `gate keeps annular events filter-locked even after acknowledgement`() {
        val result = SafetyGate.evaluate(acknowledged = true, EclipseType.ANNULAR)
        assertFalse(result.blocked)
        assertTrue(result.filterLocked)
        assertFalse(SafetyGate.evaluate(acknowledged = true, EclipseType.TOTAL).filterLocked)
    }

    @Test
    fun `totality bracket runs 1-2000 to 8 seconds in full stops`() {
        val bracket = EclipseModel.totalityBracket()
        assertEquals(15, bracket.size)
        assertEquals(1.0 / 2000, bracket.first().value, 1e-12)
        assertEquals(8.0, bracket.last().value, 1e-12)
        for (i in 1 until bracket.size) {
            assertEquals(2.0, bracket[i].value / bracket[i - 1].value, 0.35)
        }
    }
}
