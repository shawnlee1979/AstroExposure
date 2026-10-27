package app.astroexposure.domain.timeline

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TonightDateWindowTest {

    private val anchor: LocalDate = LocalDate.of(2026, 10, 27)

    @Test
    fun windowEdgesAreSevenBackAndThirtyForward() {
        assertEquals(LocalDate.of(2026, 10, 20), TonightDateWindow.earliest(anchor))
        assertEquals(LocalDate.of(2026, 11, 26), TonightDateWindow.latest(anchor))
        assertTrue(TonightDateWindow.isInWindow(anchor, TonightDateWindow.earliest(anchor)))
        assertTrue(TonightDateWindow.isInWindow(anchor, TonightDateWindow.latest(anchor)))
        assertFalse(TonightDateWindow.isInWindow(anchor, anchor.minusDays(8)))
        assertFalse(TonightDateWindow.isInWindow(anchor, anchor.plusDays(31)))
    }

    @Test
    fun clampAndStepStopAtTheEdges() {
        assertEquals(TonightDateWindow.earliest(anchor), TonightDateWindow.clamp(anchor, anchor.minusDays(90)))
        assertEquals(TonightDateWindow.latest(anchor), TonightDateWindow.clamp(anchor, anchor.plusDays(90)))
        assertFalse(TonightDateWindow.canStep(anchor, TonightDateWindow.earliest(anchor), -1))
        assertFalse(TonightDateWindow.canStep(anchor, TonightDateWindow.latest(anchor), 1))
        assertTrue(TonightDateWindow.canStep(anchor, anchor, -1))
        assertEquals(TonightDateWindow.latest(anchor), TonightDateWindow.stepped(anchor, anchor.plusDays(29), 5))
    }

    @Test
    fun relativeClassificationCoversBothSides() {
        assertEquals(TonightDateWindow.Relative.TONIGHT, TonightDateWindow.relative(anchor, anchor))
        assertEquals(TonightDateWindow.Relative.TOMORROW, TonightDateWindow.relative(anchor, anchor.plusDays(1)))
        assertEquals(TonightDateWindow.Relative.YESTERDAY, TonightDateWindow.relative(anchor, anchor.minusDays(1)))
        assertEquals(TonightDateWindow.Relative.FUTURE, TonightDateWindow.relative(anchor, anchor.plusDays(5)))
        assertEquals(TonightDateWindow.Relative.PAST, TonightDateWindow.relative(anchor, anchor.minusDays(5)))
        assertEquals(5L, TonightDateWindow.daysFromAnchor(anchor, anchor.plusDays(5)))
    }
}
