package app.astroexposure.domain.timeline

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The Tonight date-switch window (ui-design §7.3): the selected night may
 * range from 7 days before to 30 days after the anchor night ("tonight"
 * as read at the application boundary in the site's zone). Pure date
 * arithmetic — the anchor is an input, never read from a clock here.
 */
object TonightDateWindow {

    /** Range limits from ui-design §7.3 (product specification). */
    const val DAYS_BEFORE = 7L
    const val DAYS_AFTER = 30L

    fun earliest(anchor: LocalDate): LocalDate = anchor.minusDays(DAYS_BEFORE)

    fun latest(anchor: LocalDate): LocalDate = anchor.plusDays(DAYS_AFTER)

    fun isInWindow(anchor: LocalDate, date: LocalDate): Boolean =
        !date.isBefore(earliest(anchor)) && !date.isAfter(latest(anchor))

    /** Clamp a candidate date into the window. */
    fun clamp(anchor: LocalDate, date: LocalDate): LocalDate = when {
        date.isBefore(earliest(anchor)) -> earliest(anchor)
        date.isAfter(latest(anchor)) -> latest(anchor)
        else -> date
    }

    /** True when stepping [stepDays] from [current] stays inside the window. */
    fun canStep(anchor: LocalDate, current: LocalDate, stepDays: Long): Boolean =
        isInWindow(anchor, current.plusDays(stepDays))

    /** Step from [current], clamped to the window edges. */
    fun stepped(anchor: LocalDate, current: LocalDate, stepDays: Long): LocalDate =
        clamp(anchor, current.plusDays(stepDays))

    enum class Relative { YESTERDAY, TONIGHT, TOMORROW, PAST, FUTURE }

    /** Signed days from the anchor night to [date] (positive = later). */
    fun daysFromAnchor(anchor: LocalDate, date: LocalDate): Long =
        ChronoUnit.DAYS.between(anchor, date)

    fun relative(anchor: LocalDate, date: LocalDate): Relative =
        when (daysFromAnchor(anchor, date)) {
            -1L -> Relative.YESTERDAY
            0L -> Relative.TONIGHT
            1L -> Relative.TOMORROW
            else -> if (date.isBefore(anchor)) Relative.PAST else Relative.FUTURE
        }
}
