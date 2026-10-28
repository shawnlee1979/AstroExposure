package app.astroexposure.domain.timeline

import java.time.Instant

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
        var runStart: Instant? = null
        for (sample in samples) {
            val up = sample.moonAltitudeDeg > 0.0
            if (up && runStart == null) runStart = maxOf(sample.instant, axisStart)
            if (!up && runStart != null) {
                out += Interval(runStart, minOf(sample.instant, axisEnd))
                runStart = null
            }
        }
        if (runStart != null) out += Interval(runStart, axisEnd)
        return out.filter { it.end.isAfter(it.start) }
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

    /** Fraction of [instant] along the axis; values outside clamp to the edges. */
    fun fractionOf(instant: Instant, axisStart: Instant, axisEnd: Instant): Double {
        val total = axisEnd.epochSecond - axisStart.epochSecond
        if (total <= 0) return 0.0
        return ((instant.epochSecond - axisStart.epochSecond).toDouble() / total).coerceIn(0.0, 1.0)
    }

    /** Margin below the deepest sample on the altitude axis (layout spacing, empirical). */
    const val AXIS_MIN_MARGIN_DEG = 5.0

    /** The axis never extends below the geometric nadir direction. */
    const val AXIS_MIN_FLOOR_DEG = -90.0

    /** Fallback lower bound when there are no samples at all. */
    const val AXIS_MIN_DEFAULT_DEG = -20.0

    /** The axis always reaches at least this far below the horizon, so the 0° line stays readable. */
    const val AXIS_MIN_SHALLOWEST_DEG = -5.0

    /**
     * Lower bound of the altitude axis (ui-design §7.4): derived from
     * the sampled Sun/Moon series itself, not a fixed value — the
     * deepest sample minus [AXIS_MIN_MARGIN_DEG], rounded down to a
     * whole 5° step, clamped to [[AXIS_MIN_FLOOR_DEG],
     * [AXIS_MIN_SHALLOWEST_DEG]] so the axis never goes below −90° and
     * the horizon (0°) reference always stays inside the chart even on
     * polar-day series whose samples never dip below the horizon.
     */
    fun axisMinAltitudeDeg(samples: List<Sample>): Double {
        if (samples.isEmpty()) return AXIS_MIN_DEFAULT_DEG
        val deepest = samples.minOf { minOf(it.sunAltitudeDeg, it.moonAltitudeDeg) }
        val withMargin = deepest - AXIS_MIN_MARGIN_DEG
        val stepped = kotlin.math.floor(withMargin / 5.0) * 5.0
        return stepped.coerceIn(AXIS_MIN_FLOOR_DEG, AXIS_MIN_SHALLOWEST_DEG)
    }
}
