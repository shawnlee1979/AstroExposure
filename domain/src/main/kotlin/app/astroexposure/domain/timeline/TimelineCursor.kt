package app.astroexposure.domain.timeline

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Pure mathematics of the timeline cursor (ui-design §7.5): snapping to
 * key moments, the shootable-at-this-moment ranking, the three-tier
 * moonlight-interference classification, a target's above-threshold
 * arc intervals and the low-moonlight window membership of an instant.
 * Everything is a pure function of explicit inputs — the caller samples
 * the ephemeris port and the sky assembly and supplies the results
 * (rules D.1/D.2); nothing here reads a clock or a device.
 */
object TimelineCursor {

    /** Minimum altitude for a target to count as shootable at the cursor (ui-design §7.5). */
    const val MIN_ALTITUDE_DEG = 20.0

    /** A cursor within this distance of a key moment snaps onto it (ui-design §7.5). */
    const val SNAP_WINDOW_MINUTES = 5L

    /** Display cap for the cursor list and its arcs (ui-design §7.5 display rule). */
    const val MAX_DISPLAYED_TARGETS = 10

    /** One evaluated candidate at the cursor instant. */
    data class Candidate(val altitudeDeg: Double, val moonlightMcdPerM2: Double)

    /**
     * Indices of the shootable candidates, ranked for display
     * (ui-design §7.5): altitude at least [MIN_ALTITUDE_DEG] (which also
     * means the target has not set), highest altitude first; ties on
     * altitude go to the smaller moonlight contribution. The sort is
     * stable, so fully tied candidates keep catalogue order.
     */
    fun rankShootable(candidates: List<Candidate>): List<Int> =
        candidates.indices
            .filter { candidates[it].altitudeDeg >= MIN_ALTITUDE_DEG }
            .sortedWith(
                compareByDescending<Int> { candidates[it].altitudeDeg }
                    .thenBy { candidates[it].moonlightMcdPerM2 },
            )

    /** The three mutually exclusive moonlight-interference tiers of the status row. */
    enum class MoonInterference { NONE, WEAK, STRONG }

    /**
     * Status-row moonlight tier (ui-design §7.5): NONE when there is no
     * moonlight contribution at all, WEAK when the contribution is below
     * one third of the baseline total (natural floor + artificial
     * baseline), STRONG otherwise — the boundary itself (exactly 1/3)
     * belongs to STRONG. This is the single-moment classification line;
     * it is NOT the whole-night degradation line of design §5 rule 2
     * (total reaching ~4× the baseline), which must not be applied here.
     */
    fun moonInterference(moonlightMcdPerM2: Double, baselineTotalMcdPerM2: Double): MoonInterference =
        when {
            moonlightMcdPerM2 <= 0.0 -> MoonInterference.NONE
            baselineTotalMcdPerM2 <= 0.0 -> MoonInterference.STRONG
            moonlightMcdPerM2 < baselineTotalMcdPerM2 / 3.0 -> MoonInterference.WEAK
            else -> MoonInterference.STRONG
        }

    /**
     * Index of the key moment the cursor snaps to: the nearest moment
     * within [SNAP_WINDOW_MINUTES] minutes (the boundary itself snaps),
     * or null when no moment is in range. Equidistant moments resolve
     * to the earlier one.
     */
    fun snappedMomentIndex(cursor: Instant, momentInstants: List<Instant>): Int? {
        var best: Int? = null
        var bestAbs = Long.MAX_VALUE
        momentInstants.forEachIndexed { index, moment ->
            val delta = Duration.between(cursor, moment).seconds
            val abs = kotlin.math.abs(delta)
            if (abs <= SNAP_WINDOW_MINUTES * 60 && abs < bestAbs) {
                best = index
                bestAbs = abs
            }
        }
        return best
    }

    /** The cursor's minimum unit is one minute (ui-design §7.5): drop sub-minute parts. */
    fun truncatedToMinute(instant: Instant): Instant = instant.truncatedTo(ChronoUnit.MINUTES)

    /** Keep a placed cursor inside the axis span. */
    fun clampToAxis(instant: Instant, axisStart: Instant, axisEnd: Instant): Instant =
        instant.coerceIn(axisStart, axisEnd)

    /** One altitude sample of a target along the axis. */
    data class AltitudeSample(val instant: Instant, val altitudeDeg: Double)

    /**
     * Spans where a target's sampled altitude is at or above
     * [thresholdDeg], at sample resolution — the arcs drawn for cursor
     * targets (ui-design §7.5). A span starts at the first qualifying
     * sample of a run and ends at the first sample after the run (or at
     * [axisEnd]).
     */
    fun aboveThresholdIntervals(
        samples: List<AltitudeSample>,
        thresholdDeg: Double,
        axisEnd: Instant,
    ): List<NightTimeline.Interval> {
        val out = mutableListOf<NightTimeline.Interval>()
        var runStart: Instant? = null
        for (sample in samples) {
            val up = sample.altitudeDeg >= thresholdDeg
            if (up && runStart == null) runStart = sample.instant
            if (!up && runStart != null) {
                out += NightTimeline.Interval(runStart, sample.instant)
                runStart = null
            }
        }
        if (runStart != null) out += NightTimeline.Interval(runStart, axisEnd)
        return out.filter { it.end.isAfter(it.start) }
    }

    /** The highest sampled point of a target's pass (its culmination, at sample resolution). */
    fun culminationOf(samples: List<AltitudeSample>): AltitudeSample? =
        samples.maxByOrNull { it.altitudeDeg }

    /** Low-moonlight window membership of one instant (status row, ui-design §7.5). */
    sealed interface LowMoonStatus {
        /** The instant lies inside a window; [end] is when that window closes. */
        data class Inside(val end: Instant) : LowMoonStatus

        /** Not inside a window; [start] is when the next window opens. */
        data class Next(val start: Instant) : LowMoonStatus

        /** No window contains the instant and none opens later on the axis. */
        data object None : LowMoonStatus
    }

    fun lowMoonStatus(instant: Instant, windows: List<NightTimeline.Interval>): LowMoonStatus {
        for (window in windows) {
            if (!instant.isBefore(window.start) && instant.isBefore(window.end)) {
                return LowMoonStatus.Inside(window.end)
            }
        }
        val next = windows.filter { it.start.isAfter(instant) }.minByOrNull { it.start }
        return if (next != null) LowMoonStatus.Next(next.start) else LowMoonStatus.None
    }

    /**
     * Moon altitude at an arbitrary instant, linearly interpolated
     * between the timeline's 5-minute samples — display-grade input for
     * the cursor bubble while it is being dragged; outside the sampled
     * span the nearest edge sample is used.
     */
    fun moonAltitudeAt(samples: List<NightTimeline.Sample>, instant: Instant): Double? {
        if (samples.isEmpty()) return null
        if (!instant.isAfter(samples.first().instant)) return samples.first().moonAltitudeDeg
        if (!instant.isBefore(samples.last().instant)) return samples.last().moonAltitudeDeg
        val upper = samples.first { !it.instant.isBefore(instant) }
        val lower = samples.last { !it.instant.isAfter(instant) }
        if (lower.instant == upper.instant) return lower.moonAltitudeDeg
        val span = (upper.instant.epochSecond - lower.instant.epochSecond).toDouble()
        val fraction = (instant.epochSecond - lower.instant.epochSecond) / span
        return lower.moonAltitudeDeg + fraction * (upper.moonAltitudeDeg - lower.moonAltitudeDeg)
    }
}
