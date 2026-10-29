package app.astroexposure.domain.timeline

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Pure layout mathematics for the Tonight timeline (ui-design §7.4): the
 * background band segmentation, the Moon-above-horizon intervals and
 * the altitude-axis range. Everything here is a pure function of its
 * inputs — the caller samples the ephemeris port and supplies the axis;
 * nothing reads a clock, a file or a device (rules D.1/D.2). (Marker
 * labels and their two-row placement were retired with the 0.4.1
 * marker revision: marks are ticks only, details open on tap.)
 *
 * Band thresholds are the standard twilight definitions (public
 * reference values): civil twilight ends when the Sun's centre is 6°
 * below the horizon, nautical at 12°, astronomical at 18°.
 */
object NightTimeline {

    /** Sampling step for the Sun/Moon altitude curves (ui-design §7.4). */
    const val SAMPLE_STEP_MINUTES = 5L

    const val CIVIL_LIMIT_DEG = -6.0
    const val NAUTICAL_LIMIT_DEG = -12.0
    const val ASTRONOMICAL_LIMIT_DEG = -18.0

    enum class SunBand { DAY, CIVIL_TWILIGHT, NAUTICAL_TWILIGHT, ASTRONOMICAL_TWILIGHT, ASTRONOMICAL_NIGHT }

    /** One sampled point on the axis: Sun and Moon altitude in degrees. */
    data class Sample(
        val instant: Instant,
        val sunAltitudeDeg: Double,
        val moonAltitudeDeg: Double,
        /** Moon illumination fraction 0..1 at [instant]; every construction samples it. */
        val moonIllumination: Double,
    )

    /** A span of the axis classified as one solar band. */
    data class BandSegment(val start: Instant, val end: Instant, val band: SunBand)

    /** A span of the axis (window bands, Moon-up spans). */
    data class Interval(val start: Instant, val end: Instant)

    fun bandFor(sunAltitudeDeg: Double): SunBand = when {
        sunAltitudeDeg > 0.0 -> SunBand.DAY
        sunAltitudeDeg > CIVIL_LIMIT_DEG -> SunBand.CIVIL_TWILIGHT
        sunAltitudeDeg > NAUTICAL_LIMIT_DEG -> SunBand.NAUTICAL_TWILIGHT
        sunAltitudeDeg > ASTRONOMICAL_LIMIT_DEG -> SunBand.ASTRONOMICAL_TWILIGHT
        else -> SunBand.ASTRONOMICAL_NIGHT
    }

    /**
     * Band segments derived from the sampled solar-altitude series itself,
     * so both ends of the axis use one rule and polar extremes (no
     * sunrise, no sunset, no night) simply yield fewer bands instead of
     * special cases. Each span between consecutive samples takes the band
     * of its earlier sample; a tail span runs from the last sample to
     * [axisEnd]. Adjacent spans of the same band merge.
     */
    fun bandSegments(samples: List<Sample>, axisStart: Instant, axisEnd: Instant): List<BandSegment> {
        if (samples.isEmpty() || !axisEnd.isAfter(axisStart)) return emptyList()
        val out = mutableListOf<BandSegment>()
        fun extend(start: Instant, end: Instant, band: SunBand) {
            if (!end.isAfter(start)) return
            val last = out.lastOrNull()
            if (last != null && last.band == band && last.end == start) {
                out[out.lastIndex] = BandSegment(last.start, end, band)
            } else {
                out += BandSegment(start, end, band)
            }
        }
        for (i in samples.indices) {
            val start = maxOf(samples[i].instant, axisStart)
            val end = if (i + 1 < samples.size) minOf(samples[i + 1].instant, axisEnd) else axisEnd
            extend(start, end, bandFor(samples[i].sunAltitudeDeg))
        }
        return out
    }

    /**
     * Spans where the Moon is above the horizon, at sample resolution:
     * a span starts at the first above-horizon sample of a run and ends
     * at the first sample after the run (or at [axisEnd]).
     */
    fun moonAboveIntervals(samples: List<Sample>, axisStart: Instant, axisEnd: Instant): List<Interval> {
        val out = mutableListOf<Interval>()
        for (run in contiguousRuns(samples) { it.moonAltitudeDeg > 0.0 }) {
            val start = maxOf(samples[run.first].instant, axisStart)
            val end = if (run.last + 1 < samples.size) {
                minOf(samples[run.last + 1].instant, axisEnd)
            } else {
                axisEnd
            }
            out += Interval(start, end)
        }
        return out.filter { it.end.isAfter(it.start) }
    }

    /**
     * The maximal runs of consecutive indices whose elements satisfy
     * [predicate], as index ranges. Endpoint conventions (what an index
     * range means in time) stay with each caller; only the scanning is
     * shared.
     */
    fun <T> contiguousRuns(items: List<T>, predicate: (T) -> Boolean): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var runStart = -1
        for (i in items.indices) {
            if (predicate(items[i])) {
                if (runStart < 0) runStart = i
            } else if (runStart >= 0) {
                out += runStart..(i - 1)
                runStart = -1
            }
        }
        if (runStart >= 0) out += runStart..items.lastIndex
        return out
    }

    /**
     * Sampling instants from [start] to [end] (both inclusive) at a fixed
     * [stepMinutes] step — the one sampling grid the tonight timeline and
     * the per-target altitude series share.
     */
    fun sampleInstants(start: Instant, end: Instant, stepMinutes: Long = SAMPLE_STEP_MINUTES): List<Instant> {
        require(stepMinutes > 0) { "sampling step must be positive, was $stepMinutes" }
        val out = mutableListOf<Instant>()
        var t = start
        while (!t.isAfter(end)) {
            out += t
            t = t.plusSeconds(stepMinutes * 60)
        }
        return out
    }

    /**
     * Clamp a window with possibly open edges (null = already in effect
     * at the axis start / still in effect at the axis end) to the axis.
     * Returns null when the window does not intersect the axis at all.
     */
    fun clampToAxis(start: Instant?, end: Instant?, axisStart: Instant, axisEnd: Instant): Interval? {
        val s = maxOf(start ?: axisStart, axisStart)
        val e = minOf(end ?: axisEnd, axisEnd)
        return if (e.isAfter(s)) Interval(s, e) else null
    }

    /**
     * Whether [instant] falls on the axis, endpoints included — the
     * point-wise companion of [clampToAxis]: a caller that presents an
     * instant AS a position on the axis (a band label anchored at the
     * instant) checks this first, instead of letting [fractionOf]
     * silently clamp an off-axis instant onto an edge.
     */
    fun isWithinAxis(instant: Instant, axisStart: Instant, axisEnd: Instant): Boolean =
        !instant.isBefore(axisStart) && !instant.isAfter(axisEnd)

    /** Fraction of [instant] along the axis; values outside clamp to the edges. */
    fun fractionOf(instant: Instant, axisStart: Instant, axisEnd: Instant): Double {
        val total = axisEnd.epochSecond - axisStart.epochSecond
        if (total <= 0) return 0.0
        return ((instant.epochSecond - axisStart.epochSecond).toDouble() / total).coerceIn(0.0, 1.0)
    }

    /**
     * The instant at [fraction] along the axis — the inverse of
     * [fractionOf]. The fraction is clamped to [0, 1] here, the same
     * clamp [fractionOf] applies, so a caller handing in a raw drag
     * fraction lands on the nearer axis end instead of extrapolating
     * past it. Sub-second remainders truncate, the convention the
     * axis labels and the cursor placement always used.
     */
    fun instantAtFraction(fraction: Double, axisStart: Instant, axisEnd: Instant): Instant {
        val total = axisEnd.epochSecond - axisStart.epochSecond
        return axisStart.plusSeconds((fraction.coerceIn(0.0, 1.0) * total).toLong())
    }

    /** Candidate steps, in hours, for the whole-hour axis (ui-design §7.4). */
    private val HOUR_AXIS_STEP_CANDIDATES = listOf(1, 2, 3, 6)

    /**
     * Most step cells the whole-hour axis spans (ui-design §7.4): the
     * axis is a sparse reference scale in the comparison board's
     * density (its 15.2-hour axis carries five labels at a 3-hour
     * step), not an hourly ruler, so a step must also keep the axis
     * within about this many cells however wide the panel is.
     */
    private const val HOUR_AXIS_MAX_CELLS = 6.0

    /**
     * The whole-hour axis's step, in hours (ui-design §7.4, 0.8.10):
     * the smallest candidate in {1, 2, 3, 6} that satisfies BOTH
     * rules — adjacent labels clear each other (one step spans at
     * least the caller's [labelWidthDp] estimate plus [gapDp]) and
     * the density cap [HOUR_AXIS_MAX_CELLS] holds (axis hours divided
     * by the step). On a roughly 15-hour axis at phone widths the
     * non-overlap rule alone would already allow 2h; the density cap
     * is what makes it 3h, matching the board. When no candidate
     * clears the labels (an extreme axis on a narrow plot) the
     * coarsest step is returned and the caller's overlap-omission
     * backstop decides what survives.
     */
    fun hourAxisStepHours(
        axisStart: Instant,
        axisEnd: Instant,
        plotWidthDp: Double,
        labelWidthDp: Double,
        gapDp: Double = 4.0,
    ): Int {
        val axisSeconds = (axisEnd.epochSecond - axisStart.epochSecond).toDouble()
        if (axisSeconds <= 0.0 || plotWidthDp <= 0.0) return HOUR_AXIS_STEP_CANDIDATES.last()
        val axisHours = axisSeconds / 3600.0
        val dpPerHour = plotWidthDp / axisHours
        return HOUR_AXIS_STEP_CANDIDATES.firstOrNull { step ->
            step * dpPerHour >= labelWidthDp + gapDp &&
                axisHours / step <= HOUR_AXIS_MAX_CELLS
        } ?: HOUR_AXIS_STEP_CANDIDATES.last()
    }

    /**
     * The whole hours the axis labels (ui-design §7.4): every local
     * whole hour inside the axis whose hour-of-day is a multiple of
     * [stepHours], in axis order. [hourAxisStepHours] picks the step;
     * the caller formats and places the labels and applies its
     * overlap-omission backstop.
     */
    fun hourAxisLabels(
        axisStart: Instant,
        axisEnd: Instant,
        zone: ZoneId,
        stepHours: Int,
    ): List<Instant> {
        require(stepHours > 0) { "hour axis step must be positive, was $stepHours" }
        val out = mutableListOf<Instant>()
        var t = axisStart.atZone(zone).truncatedTo(ChronoUnit.HOURS)
        if (t.toInstant().isBefore(axisStart)) t = t.plusHours(1)
        while (!t.toInstant().isAfter(axisEnd)) {
            if (t.hour % stepHours == 0) out += t.toInstant()
            t = t.plusHours(1)
        }
        return out
    }

    /** Lowest altitude of the cursor track band's vertical domain (ui-design §7.4). */
    const val TRACK_MIN_ALTITUDE_DEG = -36.0

    /** Highest altitude of the cursor track band's vertical domain (ui-design §7.4). */
    const val TRACK_MAX_ALTITUDE_DEG = 90.0

    /**
     * Where an altitude falls in the cursor track band's vertical
     * domain (ui-design §7.4, 0.8.11): 0 at [TRACK_MIN_ALTITUDE_DEG],
     * 1 at [TRACK_MAX_ALTITUDE_DEG], clamped outside. The lower bound
     * is twice the astronomical-twilight depth — a fixed depth that
     * keeps the twilight story legible; it does NOT reach the bodies'
     * nightly minima (a winter Sun at mid-northern latitudes bottoms
     * near −79°, the Moon near −60°…−78°), so out-of-domain parts of
     * both sampled curves clamp at the band's edge: the Sun's curve
     * riding the bottom edge through a winter night is the expected
     * shape, not a sampling defect. The domain does not adapt to the
     * season. The 0° horizon sits at 36/126 of the height. Pure
     * mapping — the UI only turns the fraction into a y position.
     */
    fun altitudeFraction(altitudeDeg: Double): Double =
        ((altitudeDeg - TRACK_MIN_ALTITUDE_DEG) /
            (TRACK_MAX_ALTITUDE_DEG - TRACK_MIN_ALTITUDE_DEG)).coerceIn(0.0, 1.0)

}
