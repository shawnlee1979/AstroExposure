package app.astroexposure.usecase

import app.astroexposure.domain.ephemeris.NightWindow
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Trigger tests for the location card's moonlight-line moonrise note
 * (ui-design §7.2, 0.8.4): the note exists for exactly one situation —
 * the dusk sample caught the Moon below the horizon, yet it rises
 * within the astronomical night.
 */
class MoonriseNoteTest {

    private val dusk: Instant = Instant.parse("2026-10-28T22:47:00Z")
    private val dawn: Instant = Instant.parse("2026-10-29T10:58:00Z")
    private val night = NightWindow(dusk, dawn)

    @Test
    fun moonDownAtDuskRisingInsideTheNightIsNamed() {
        val rise = Instant.parse("2026-10-29T01:20:00Z")
        assertEquals(rise, moonriseNoteInstant(false, rise, night))
    }

    @Test
    fun moonAlreadyUpAtDuskIsNotNamed() {
        val rise = Instant.parse("2026-10-29T01:20:00Z")
        assertNull(moonriseNoteInstant(true, rise, night))
    }

    @Test
    fun riseBeforeDuskIsNotNamed() {
        // The Moon rose during twilight, before the dusk sample: it
        // was up when the sample was taken, whatever moonUpAtDusk
        // says — the rise is outside the (dusk, dawn) window anyway.
        val rise = Instant.parse("2026-10-28T21:30:00Z")
        assertNull(moonriseNoteInstant(false, rise, night))
    }

    @Test
    fun riseAfterDawnIsNotNamed() {
        val rise = Instant.parse("2026-10-29T12:05:00Z")
        assertNull(moonriseNoteInstant(false, rise, night))
    }

    @Test
    fun missingInputsAreNotNamed() {
        val rise = Instant.parse("2026-10-29T01:20:00Z")
        assertNull(moonriseNoteInstant(false, null, night))
        assertNull(moonriseNoteInstant(false, rise, null))
        // No sky context: nothing was sampled, nothing to explain.
        assertNull(moonriseNoteInstant(null, rise, night))
    }
}
