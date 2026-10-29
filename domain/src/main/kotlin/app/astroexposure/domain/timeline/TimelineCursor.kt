package app.astroexposure.domain.timeline

import app.astroexposure.domain.ephemeris.HorizontalCoordinates
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

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

    /**
     * Hold time after which a press on the axis "grabs" an already
     * placed cursor for repositioning (ui-design §7.5, anti-mistouch
     * rule): shorter touches never move a placed cursor.
     */
    const val LONG_PRESS_REPOSITION_MS = 300L

    /** Placement state of the cursor for one night (ui-design §7.5 gesture state machine). */
    enum class CursorPlacement { NO_CURSOR, PLACED_UNLOCKED, PLACED_LOCKED }

    /** The four ways the axis and the time bar can act on the cursor (ui-design §7.5). */
    enum class CursorGesture { TAP, DRAG, LONG_PRESS_DRAG, STEP }

    /**
     * Whether [gesture] may move the cursor in [placement] state
     * (ui-design §7.5, 0.5.2 anti-mistouch rule). With no cursor, a tap
     * or a drag (plain or after a hold) places it — the first placement
     * is unguarded. Once placed, plain taps and direct drags no longer
     * move it: repositioning requires a long-press drag. Locked, even
     * that is refused. The time bar's ±1′ / 10′ steps are deliberate
     * actions and stay available in both placed states, but with no
     * cursor there is nothing to step. Restoring a retained position
     * after "whole night" is not a move and is not governed here.
     */
    fun mayMoveCursor(placement: CursorPlacement, gesture: CursorGesture): Boolean =
        when (placement) {
            CursorPlacement.NO_CURSOR -> gesture != CursorGesture.STEP
            CursorPlacement.PLACED_UNLOCKED -> gesture == CursorGesture.LONG_PRESS_DRAG ||
                gesture == CursorGesture.STEP
            CursorPlacement.PLACED_LOCKED -> gesture == CursorGesture.STEP
        }

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
        for (run in NightTimeline.contiguousRuns(samples) { it.altitudeDeg >= thresholdDeg }) {
            val start = samples[run.first].instant
            val end = if (run.last + 1 < samples.size) samples[run.last + 1].instant else axisEnd
            out += NightTimeline.Interval(start, end)
        }
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
    fun moonAltitudeAt(samples: List<NightTimeline.Sample>, instant: Instant): Double? =
        interpolateSampleValue(samples, instant) { it.moonAltitudeDeg }

    /**
     * Sun altitude at an arbitrary instant, interpolated between the
     * timeline samples exactly like [moonAltitudeAt] — the cursor
     * bubble's solar-segment fallback while the cursor evaluation is
     * in flight (ui-design §7.5, 0.8.9), classified by
     * [NightTimeline.bandFor] at the call site.
     */
    fun sunAltitudeAt(samples: List<NightTimeline.Sample>, instant: Instant): Double? =
        interpolateSampleValue(samples, instant) { it.sunAltitudeDeg }

    /**
     * Moon illumination fraction at an arbitrary instant, interpolated
     * between the timeline samples exactly like [moonAltitudeAt] — so
     * the bubble's two Moon figures always describe the SAME instant
     * (the cursor's), never a mixture of cursor time and dusk.
     */
    fun moonIlluminationAt(samples: List<NightTimeline.Sample>, instant: Instant): Double? =
        interpolateSampleValue(samples, instant) { it.moonIllumination }

    /** The one interpolation both sampled Moon series share. */
    private fun interpolateSampleValue(
        samples: List<NightTimeline.Sample>,
        instant: Instant,
        selector: (NightTimeline.Sample) -> Double,
    ): Double? {
        if (samples.isEmpty()) return null
        if (!instant.isAfter(samples.first().instant)) return selector(samples.first())
        if (!instant.isBefore(samples.last().instant)) return selector(samples.last())
        val upper = samples.first { !it.instant.isBefore(instant) }
        val lower = samples.last { !it.instant.isAfter(instant) }
        if (lower.instant == upper.instant) return selector(lower)
        val span = (upper.instant.epochSecond - lower.instant.epochSecond).toDouble()
        val fraction = (instant.epochSecond - lower.instant.epochSecond) / span
        return selector(lower) + fraction * (selector(upper) - selector(lower))
    }

    /** Drop from the night's peak altitude that still counts as "near culmination" (ui-design §7.5). */
    const val CULMINATION_BAND_DROP_DEG = 5.0

    /**
     * The three phases of one target pass over its shootable window
     * (ui-design §7.5, 0.5.1 revision): [rising] runs from the window
     * start to the culmination span, [culminationSpan] is the maximal
     * run of samples at or above (peak − [CULMINATION_BAND_DROP_DEG]),
     * and [setting] runs from the span's end to the window end. Either
     * side segment is null when it has zero length (a monotonic pass, or
     * a pass spent entirely inside the culmination band). Intervals use
     * the [aboveThresholdIntervals] convention: an interval ends at the
     * first sample after its run, or at [NightTimeline] axis end.
     */
    data class ArcPhases(
        val window: NightTimeline.Interval,
        val rising: NightTimeline.Interval?,
        val culminationSpan: NightTimeline.Interval,
        val setting: NightTimeline.Interval?,
        val culmination: AltitudeSample,
    )

    /**
     * Phases of the above-[thresholdDeg] run that contains the night's
     * highest sample of a target, or null when the target never reaches
     * the threshold. Ties for the highest sample resolve to the FIRST
     * such sample (maxByOrNull semantics), so a flat-topped pass takes
     * its culmination at the plateau's start.
     */
    fun arcPhases(
        samples: List<AltitudeSample>,
        thresholdDeg: Double,
        axisEnd: Instant,
    ): ArcPhases? {
        if (samples.isEmpty()) return null
        val peakIndex = samples.indices.maxByOrNull { samples[it].altitudeDeg } ?: return null
        val peak = samples[peakIndex]
        if (peak.altitudeDeg < thresholdDeg) return null
        fun endAfter(index: Int): Instant =
            if (index + 1 < samples.size) samples[index + 1].instant else axisEnd
        // The shootable window: expand from the peak while samples stay
        // at or above the threshold.
        var windowStart = peakIndex
        while (windowStart > 0 && samples[windowStart - 1].altitudeDeg >= thresholdDeg) windowStart--
        var windowEnd = peakIndex
        while (windowEnd + 1 < samples.size && samples[windowEnd + 1].altitudeDeg >= thresholdDeg) {
            windowEnd++
        }
        // The culmination span: expand from the peak while samples stay
        // within the drop band (never below the shootable threshold).
        val bandFloor = maxOf(thresholdDeg, peak.altitudeDeg - CULMINATION_BAND_DROP_DEG)
        var spanStart = peakIndex
        while (spanStart > windowStart && samples[spanStart - 1].altitudeDeg >= bandFloor) spanStart--
        var spanEnd = peakIndex
        while (spanEnd < windowEnd && samples[spanEnd + 1].altitudeDeg >= bandFloor) spanEnd++
        val window = NightTimeline.Interval(samples[windowStart].instant, endAfter(windowEnd))
        val span = NightTimeline.Interval(samples[spanStart].instant, endAfter(spanEnd))
        val rising = if (spanStart > windowStart) {
            NightTimeline.Interval(samples[windowStart].instant, samples[spanStart].instant)
        } else {
            null
        }
        val setting = if (spanEnd < windowEnd) {
            NightTimeline.Interval(span.end, window.end)
        } else {
            null
        }
        return ArcPhases(
            window = window,
            rising = rising,
            culminationSpan = span,
            setting = setting,
            culmination = peak,
        )
    }

    // --- Night cut for the detail dialog (ui-design §7.5, 0.8.4) -------------------

    /**
     * Intersection of [interval] with [bounds]; null when they do not
     * overlap. Touching at an endpoint counts as empty — a zero-length
     * span is not a window.
     */
    fun intersectInterval(
        interval: NightTimeline.Interval,
        bounds: NightTimeline.Interval,
    ): NightTimeline.Interval? {
        val start = maxOf(interval.start, bounds.start)
        val end = minOf(interval.end, bounds.end)
        return if (end.isAfter(start)) NightTimeline.Interval(start, end) else null
    }

    /**
     * [phases] cut to the [bounds] span for presentation in the detail
     * dialog (ui-design §7.5, 0.8.4): the presented shootable window is
     * the ≥threshold run intersected with the night (the caller passes
     * the astronomical night, or sunset→next sunrise when there is no
     * astronomical night), so a near-circumpolar target whose run leaks
     * past dawn to the axis end is shown with the night's own bounds.
     * Null when the run and the span do not overlap. The phase segments
     * are cut by the same span so the copy stays a partition of its
     * window; when the culmination span lies wholly outside the span,
     * the cut window stands in for the span. This copy serves window
     * presentation only — [phaseAt] is applied to the uncut phases.
     */
    fun arcPhasesWithin(
        phases: ArcPhases,
        bounds: NightTimeline.Interval,
    ): ArcPhases? {
        val window = intersectInterval(phases.window, bounds) ?: return null
        val span = intersectInterval(phases.culminationSpan, bounds)
        return if (span != null) {
            phases.copy(
                window = window,
                rising = phases.rising?.let { intersectInterval(it, bounds) },
                culminationSpan = span,
                setting = phases.setting?.let { intersectInterval(it, bounds) },
            )
        } else {
            phases.copy(
                window = window,
                rising = null,
                culminationSpan = window,
                setting = null,
            )
        }
    }

    // --- Recommended shooting window (ui-design §7.5, 0.6.0) -------------------

    /** Altitude floor of the recommended window for high passes (ui-design §7.5). */
    const val RECOMMENDED_MIN_ALTITUDE_DEG = 40.0

    /** Drop from culmination that still counts as recommended for high passes. */
    const val RECOMMENDED_DROP_DEG = 10.0

    /**
     * A target's recommended shooting window for one night (ui-design
     * §7.5, 0.6.0): the maximal run of samples around the culmination
     * that stays at or above [thresholdDeg], where the threshold is
     * max(40°, culmination − 10°) for passes culminating at or above
     * 40°, and (culmination − 10°) for lower passes — those are
     * [relativeBest] windows and the detail dialog must say so instead of
     * dressing a low pass up as a good one. Interval ends follow the
     * [aboveThresholdIntervals] convention (first sample after the run,
     * or the axis end).
     */
    data class RecommendedWindow(
        val interval: NightTimeline.Interval,
        val thresholdDeg: Double,
        val culminationAltitudeDeg: Double,
        val relativeBest: Boolean,
    )

    /** Recommended window of a target from its night altitude samples, or null when there are none. */
    fun recommendedWindow(
        samples: List<AltitudeSample>,
        axisEnd: Instant,
    ): RecommendedWindow? {
        if (samples.isEmpty()) return null
        val peakIndex = samples.indices.maxByOrNull { samples[it].altitudeDeg } ?: return null
        val peak = samples[peakIndex].altitudeDeg
        val relativeBest = peak < RECOMMENDED_MIN_ALTITUDE_DEG
        val threshold = if (relativeBest) {
            peak - RECOMMENDED_DROP_DEG
        } else {
            maxOf(RECOMMENDED_MIN_ALTITUDE_DEG, peak - RECOMMENDED_DROP_DEG)
        }
        var start = peakIndex
        while (start > 0 && samples[start - 1].altitudeDeg >= threshold) start--
        var end = peakIndex
        while (end + 1 < samples.size && samples[end + 1].altitudeDeg >= threshold) end++
        val intervalEnd = if (end + 1 < samples.size) samples[end + 1].instant else axisEnd
        return RecommendedWindow(
            interval = NightTimeline.Interval(samples[start].instant, intervalEnd),
            thresholdDeg = threshold,
            culminationAltitudeDeg = peak,
            relativeBest = relativeBest,
        )
    }

    /**
     * [window] cut to the [bounds] span for presentation in the detail
     * dialog (ui-design §7.5, 0.8.4), the same night cut [arcPhasesWithin]
     * applies to the shootable window; null when they do not overlap.
     * Threshold, culmination altitude and the relative-best flag are
     * properties of the pass, not of the cut, and carry over unchanged.
     */
    fun recommendedWithin(
        window: RecommendedWindow,
        bounds: NightTimeline.Interval,
    ): RecommendedWindow? =
        intersectInterval(window.interval, bounds)?.let { window.copy(interval = it) }

    /** Where the cursor sits relative to a recommended window (ui-design §7.5). */
    enum class RecommendedState { BEFORE, INSIDE, AFTER }

    /** Cursor state against [window]: before its start, inside [start, end), or from its end on. */
    fun recommendedState(window: RecommendedWindow, instant: Instant): RecommendedState = when {
        instant.isBefore(window.interval.start) -> RecommendedState.BEFORE
        !instant.isBefore(window.interval.end) -> RecommendedState.AFTER
        else -> RecommendedState.INSIDE
    }

    /** Whole minutes until the cursor enters [window]; 0 once it is inside or past it. */
    fun recommendedMinutesUntil(window: RecommendedWindow, instant: Instant): Long =
        if (instant.isBefore(window.interval.start)) {
            Duration.between(instant, window.interval.start).toMinutes()
        } else {
            0L
        }

    // --- Moon separation and exclusion (ui-design §7.5, 0.6.0) ------------------

    /** Separations strictly below this angle can exclude a target (ui-design §7.5). */
    const val MOON_EXCLUSION_SEPARATION_DEG = 40.0

    /** Separations below this angle earn the detail dialog a moonlight caution line. */
    const val MOON_CAUTION_SEPARATION_DEG = 60.0

    /**
     * True spherical angular separation in degrees between two
     * directions given by their horizontal coordinates, via the
     * spherical law of cosines on (altitude, azimuth). This is the
     * actual angle between the two lines of sight — NOT the altitude
     * difference, which is only a lower bound and is never presented
     * as a separation anywhere in the cursor rules.
     */
    fun angularSeparationDeg(a: HorizontalCoordinates, b: HorizontalCoordinates): Double {
        val altA = Math.toRadians(a.altitude.value)
        val altB = Math.toRadians(b.altitude.value)
        val deltaAz = Math.toRadians(a.azimuth.value - b.azimuth.value)
        val cosSep = sin(altA) * sin(altB) + cos(altA) * cos(altB) * cos(deltaAz)
        return Math.toDegrees(acos(cosSep.coerceIn(-1.0, 1.0)))
    }

    /**
     * The cursor-set exclusion (ui-design §7.5, 0.6.0): a target drops
     * out of the shootable set at the cursor instant only when ALL
     * three hold — the Moon is above the horizon (strictly, altitude
     * > 0°), the true separation is strictly below 40°, and the
     * moonlight interference toward the target is STRONG (the existing
     * ≥1/3-of-baseline tier). A weak contribution or a wide separation
     * never excludes; a Moon below the horizon never excludes whatever
     * the geometry says.
     */
    fun moonExcluded(
        separationDeg: Double,
        moonAltitudeDeg: Double,
        interference: MoonInterference,
    ): Boolean =
        moonAltitudeDeg > 0.0 &&
            separationDeg < MOON_EXCLUSION_SEPARATION_DEG &&
            interference == MoonInterference.STRONG

    /** How the Moon bears on one target at the cursor (detail-card lines, ui-design §7.5). */
    enum class MoonProximity { EXCLUDED, CAUTION, CLEAR }

    /**
     * Detail-card moon classification: EXCLUDED per [moonExcluded];
     * CAUTION when the Moon is up, some interference is present, and
     * either the tier is WEAK or the separation is below 60° (the
     * neutral reminder line); CLEAR otherwise — including every case
     * with the Moon below the horizon, where the separation is shown
     * for reference only.
     */
    fun moonProximity(
        separationDeg: Double,
        moonAltitudeDeg: Double,
        interference: MoonInterference,
    ): MoonProximity = when {
        moonExcluded(separationDeg, moonAltitudeDeg, interference) ->
            MoonProximity.EXCLUDED
        moonAltitudeDeg > 0.0 && interference != MoonInterference.NONE &&
            (interference == MoonInterference.WEAK ||
                separationDeg < MOON_CAUTION_SEPARATION_DEG) ->
            MoonProximity.CAUTION
        else -> MoonProximity.CLEAR
    }

    /** Where an instant sits in a target's pass (ui-design §7.5 detail-card wording). */
    enum class PassPhase { RISING, NEAR_CULMINATION, SETTING }

    /**
     * The pass phase at [instant]: before the culmination span the
     * target is rising, inside the span it is near culmination, from
     * the span's end on it is setting. Instants outside the shootable
     * window follow the same rule (before the window = still rising,
     * after it = setting) — the altitude shown beside the phase says
     * how far below the threshold the target is.
     */
    fun phaseAt(phases: ArcPhases, instant: Instant): PassPhase = when {
        instant.isBefore(phases.culminationSpan.start) -> PassPhase.RISING
        !instant.isBefore(phases.culminationSpan.end) -> PassPhase.SETTING
        else -> PassPhase.NEAR_CULMINATION
    }
}
