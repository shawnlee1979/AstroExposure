package app.astroexposure.domain.timeline

import app.astroexposure.domain.camera.FramingChart
import app.astroexposure.domain.camera.FullFrameFraming
import java.time.Instant
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToLong

/**
 * The landscape composition model (ui-design §7.9, 0.15.0): frame
 * the subject first, then include the foreground. The camera is
 * placed dead on the foreground — its vertical midline on the
 * foreground bearing B, the foreground's top edge (sight-line
 * altitude T) resting at a fixed fraction of the frame height
 * above the lower edge — and everything else is solved, not set:
 *
 * - the focal CAP per orientation: at the key moment t̂ (the
 *   subject centre's closest approach to B), the largest focal
 *   length whose frame still holds the whole subject range;
 * - the FEASIBLE interval per orientation: the framing advice
 *   interval (§7.10's share band) cut by the cap;
 * - the recommended focal f*: the largest focal in the feasible
 *   interval whose total window is at least [WINDOW_RULE_FRACTION]
 *   of the best achievable — at the cap itself the window is
 *   typically pinched into a one-sided stub (the cap is defined by
 *   full containment AT t̂), so the naive clamp lands exactly on
 *   the degenerate point;
 * - the WINDOWS at f*: the spans in which the whole subject range
 *   is inside the placed frame, cut to the night span, with the
 *   best moment in the three honest forms (crossing / closest /
 *   crossing cut away by the night).
 *
 * Judgement reuses §7.10's construction verbatim — the same
 * tangent-plane transform and the same in-frame test
 * ([FramingChart.tangentPoint], [FramingChart.pointInFrame]) — with
 * the tangent point moved to the frame centre and the coordinates
 * read as (azimuth, altitude). No second geometry lives here. The
 * camera is modelled level: in landscape mode the frame's up axis
 * is the altitude direction and the framing chart's rotation does
 * not participate (a stated simplification, §7.9).
 *
 * Subject range (supplied by the caller as sampled sky points):
 * a constellation is its skeleton's unique vertices, a deep-sky
 * object its catalogue ellipse's boundary, a viewpoint the rim of
 * the circle whose diameter is its long axis; a subject with no
 * range points is judged on its centre alone.
 *
 * Everything is a pure function of the samples (rules D.1/D.2):
 * the caller samples the ephemeris port on the shared 5-minute
 * grid. Series are treated piecewise-linearly between samples;
 * window boundaries sit at the interpolated zero crossings of the
 * frame slack, the same interpolation discipline as §7.4/§7.9's
 * predecessor.
 */
object LandscapeComposition {

    /** Lowest focal length of the framing slider domain, mm (§7.10). */
    const val MIN_FOCAL_MM = 14.0

    /** Highest focal length of the framing slider domain, mm (§7.10). */
    const val MAX_FOCAL_MM = 2000.0

    /**
     * Where the foreground top rests in the placed frame: this
     * fraction of the frame height above the lower edge. A fixed
     * placement rule, not a setting (§7.9).
     */
    const val FOREGROUND_TOP_FRACTION = 0.1

    /** f* keeps at least this fraction of the best window (§7.9). */
    const val WINDOW_RULE_FRACTION = 0.9

    /**
     * The shortest window still presented as usable, minutes: twice
     * the sampling step — below it the interpolated boundary error
     * is the same order as the window itself (§7.9).
     */
    const val MIN_WINDOW_MINUTES = 10.0

    /**
     * The low-altitude hint rail, degrees: below this centre
     * altitude at t̂ the result carries a neutral extinction /
     * obstruction note. A hint only — it never cuts a window and
     * never forms a diagnosis (§7.9; the retired band's floor,
     * demoted).
     */
    const val LOW_ALTITUDE_HINT_DEG = 5.0

    /** One sky point's sampled position: azimuth and altitude, degrees. */
    data class PointSample(val azimuthDeg: Double, val altitudeDeg: Double)

    /**
     * One sampled instant of the subject: its centre and its range
     * points (empty = judge the centre alone).
     */
    data class Sample(
        val instant: Instant,
        val centre: PointSample,
        val points: List<PointSample>,
    )

    /** The night's key moment: the centre's closest approach to the bearing. */
    data class KeyMoment(
        val instant: Instant,
        /** Centre altitude at the key moment, degrees. */
        val altitudeDeg: Double,
        /** True when the centre's azimuth crosses the bearing exactly there. */
        val exactCrossing: Boolean,
        /** |azimuth − bearing| at the key moment, degrees (≈0 for a crossing). */
        val residualDeg: Double,
    )

    /** Which end of a segment a clipped-away crossing belongs to. */
    enum class SegmentEdge { START, END }

    /** A segment's best moment (the three honest forms, §7.9). */
    sealed interface BestMoment {
        /** The centre crosses the bearing inside the segment. */
        data class Crossing(val instant: Instant, val altitudeDeg: Double) : BestMoment

        /** No crossing inside: the closest approach in the segment, with its residual. */
        data class ClosestInSegment(
            val instant: Instant,
            val altitudeDeg: Double,
            val residualDiffDeg: Double,
        ) : BestMoment

        /**
         * The crossing exists but the night cut removed it from
         * this segment: [endpoint] stands in as the in-window best
         * and the true crossing is named alongside (§7.9).
         */
        data class CrossingOutsideNight(
            val edge: SegmentEdge,
            val endpoint: Instant,
            val crossingInstant: Instant,
            val crossingAltitudeDeg: Double,
        ) : BestMoment
    }

    /** One in-frame span: bounds, best moment, centre altitude range. */
    data class Segment(
        val start: Instant,
        val end: Instant,
        val best: BestMoment,
        val minAltitudeDeg: Double,
        val maxAltitudeDeg: Double,
    ) {
        val durationMinutes: Double
            get() = (end.toEpochMilli() - start.toEpochMilli()) / 60_000.0
    }

    /**
     * One orientation's solved state. [capFocalMm] is null when even
     * the widest focal cannot hold the subject at the key moment.
     * The feasible fields are null when the orientation cannot be
     * used (no cap, or the cap undercuts the advice interval).
     * [segments] are the night-cut windows at [recommendedFocalMm];
     * [geometricSegments] the same windows before the night cut.
     */
    data class OrientationOutcome(
        val portrait: Boolean,
        val capFocalMm: Double?,
        val feasibleLowMm: Double?,
        val feasibleHighMm: Double?,
        val recommendedFocalMm: Double?,
        val segments: List<Segment>,
        val geometricSegments: List<Segment>,
        val totalWindowMinutes: Double,
        val longestSegmentMinutes: Double,
    )

    /** The live-fact row of the share-conflict verdict (§7.9 §2.5-3). */
    data class LiveFact(
        val portrait: Boolean,
        val capFocalMm: Double,
        /** Windows at the cap focal (night-cut when any exist there). */
        val segments: List<Segment>,
        /** True when [segments] are the uncut geometric windows (all in twilight). */
        val outsideNight: Boolean,
        /** The subject's actual long-side share at the cap focal, 0..1. */
        val share: Double,
    )

    /** The night's verdict — exactly one, in solver precedence (§7.9 §2.5). */
    sealed interface Verdict {
        /** A usable composition exists; [recommendedPortrait] names the recommended orientation. */
        data class Feasible(val recommendedPortrait: Boolean) : Verdict

        /** The centre never comes within the widest frame's half width of the bearing. */
        data object NeverCrossesBearing : Verdict

        /**
         * Both orientations lack a cap: the vertical span from the
         * foreground top to the subject top overflows even the
         * widest frame. [subjectTopAltitudeDeg] is the range's top
         * at the key moment; [reachableTopDeg] the landscape
         * 14 mm frame's upper edge.
         */
        data class VerticalOverflow(
            val subjectTopAltitudeDeg: Double,
            val reachableTopDeg: Double,
        ) : Verdict

        /**
         * Caps exist but both undercut the advice interval: the
         * foreground can be included only by framing the subject
         * smaller than the advised share. [liveFact] is the widest
         * workable reality, stated as fact — the verdict stands.
         */
        data class ShareConflict(val liveFact: LiveFact?) : Verdict

        /** Windows exist geometrically but every one lies outside the night span. */
        data object AllOutsideNight : Verdict

        /** Feasible, but the recommended orientation's longest window is under the usable floor. */
        data object WindowTooShort : Verdict
    }

    /** The full evaluation of one night, one subject, one site. */
    data class Result(
        val keyMoment: KeyMoment,
        /** The advice interval before the caps (null: no size data, no share constraint). */
        val adviceLowMm: Double?,
        val adviceHighMm: Double?,
        val landscape: OrientationOutcome,
        val portrait: OrientationOutcome,
        val verdict: Verdict,
        /** t̂ centre altitude under the hint rail (feasible-family verdicts only). */
        val lowAltitudeHint: Boolean,
    )

    /** The frame centre's altitude for a placed frame (§7.9 §2.1). */
    fun frameCentreAltitudeDeg(foregroundTopDeg: Double, focalMm: Double, portrait: Boolean): Double =
        foregroundTopDeg + (0.5 - FOREGROUND_TOP_FRACTION) * verticalFovDeg(focalMm, portrait)

    /** The vertical field of view at [focalMm] for the orientation. */
    fun verticalFovDeg(focalMm: Double, portrait: Boolean): Double =
        if (portrait) {
            FullFrameFraming.longSideFovDeg(focalMm)
        } else {
            FullFrameFraming.shortSideFovDeg(focalMm)
        }

    /**
     * The widest frame's horizontal half width, degrees (landscape
     * at [MIN_FOCAL_MM]): the reach the never-crosses verdict is
     * measured against.
     */
    val widestHalfWidthDeg: Double =
        Math.toDegrees(atan((FullFrameFraming.FRAME_LONG_MM / 2.0) / MIN_FOCAL_MM))

    /**
     * Evaluate one night. [samples] are the subject's series over
     * the night axis (at least two, in time order); [advice] is the
     * subject's §7.10 framing advice; [sizeMajorArcmin] its
     * catalogue major axis (for the share figures); [nightSpan] the
     * span windows are cut to, or null to leave them uncut.
     */
    fun evaluate(
        samples: List<Sample>,
        bearingDeg: Double,
        foregroundTopDeg: Double,
        advice: FullFrameFraming.Advice,
        sizeMajorArcmin: Double?,
        nightSpan: NightTimeline.Interval?,
    ): Result {
        require(samples.size >= 2) { "landscape composition needs a sampled series" }
        val series = Series(samples, bearingDeg)
        val key = series.keyMoment()

        // The advice interval in its unclamped share-band form: the
        // Range form's stored bounds are this interval clamped to
        // the advice focal limits, which the feasible cut re-derives
        // against the slider domain anyway.
        val adviceInterval: Pair<Double, Double>? = when (advice) {
            is FullFrameFraming.Advice.Range ->
                Pair(advice.lowFocalMm, advice.highFocalMm)
            is FullFrameFraming.Advice.CappedAtMaxFocal ->
                sizeMajorArcmin?.let {
                    Pair(
                        FullFrameFraming.focalForShare(it, FullFrameFraming.SHARE_LOW),
                        FullFrameFraming.focalForShare(it, FullFrameFraming.SHARE_HIGH),
                    )
                }
            is FullFrameFraming.Advice.CappedAtMinFocal ->
                sizeMajorArcmin?.let {
                    Pair(
                        FullFrameFraming.focalForShare(it, FullFrameFraming.SHARE_LOW),
                        FullFrameFraming.focalForShare(it, FullFrameFraming.SHARE_HIGH),
                    )
                }
            is FullFrameFraming.Advice.NoSizeData -> null
        }
        val adviceLow = adviceInterval?.first ?: MIN_FOCAL_MM
        val adviceHigh = adviceInterval?.second ?: MAX_FOCAL_MM

        if (!key.exactCrossing && key.residualDeg > widestHalfWidthDeg) {
            val empty = { portrait: Boolean ->
                OrientationOutcome(portrait, null, null, null, null, emptyList(), emptyList(), 0.0, 0.0)
            }
            return Result(
                keyMoment = key,
                adviceLowMm = adviceInterval?.first,
                adviceHighMm = adviceInterval?.second,
                landscape = empty(false),
                portrait = empty(true),
                verdict = Verdict.NeverCrossesBearing,
                lowAltitudeHint = false,
            )
        }

        val caps = listOf(false, true).associateWith { portrait ->
            series.capAtKeyMoment(foregroundTopDeg, portrait)
        }
        if (caps.values.all { it == null }) {
            val top = series.rangeTopAltitudeAt(key.instant)
            return Result(
                keyMoment = key,
                adviceLowMm = adviceInterval?.first,
                adviceHighMm = adviceInterval?.second,
                landscape = OrientationOutcome(false, null, null, null, null, emptyList(), emptyList(), 0.0, 0.0),
                portrait = OrientationOutcome(true, null, null, null, null, emptyList(), emptyList(), 0.0, 0.0),
                verdict = Verdict.VerticalOverflow(
                    subjectTopAltitudeDeg = top,
                    reachableTopDeg = foregroundTopDeg +
                        (1.0 - FOREGROUND_TOP_FRACTION) * verticalFovDeg(MIN_FOCAL_MM, portrait = false),
                ),
                lowAltitudeHint = false,
            )
        }

        val outcomes = listOf(false, true).associateWith { portrait ->
            solveOrientation(series, foregroundTopDeg, portrait, caps.getValue(portrait), adviceLow, adviceHigh, nightSpan)
        }
        val landscape = outcomes.getValue(false)
        val portraitOutcome = outcomes.getValue(true)
        val anyFeasible = landscape.recommendedFocalMm != null || portraitOutcome.recommendedFocalMm != null

        if (!anyFeasible) {
            // Caps exist (checked above) but both undercut the
            // advice interval: inclusion and share are mutually
            // exclusive. The live fact rides the larger cap.
            val liveFact = listOf(landscape, portraitOutcome)
                .filter { it.capFocalMm != null }
                .maxByOrNull { it.capFocalMm!! }
                ?.let { wider ->
                    val atCap = series.windowsAt(foregroundTopDeg, wider.portrait, wider.capFocalMm!!, nightSpan)
                    val share = sizeMajorArcmin?.let {
                        FullFrameFraming.shareAtFocal(it, wider.capFocalMm)
                    } ?: 0.0
                    LiveFact(
                        portrait = wider.portrait,
                        capFocalMm = wider.capFocalMm,
                        segments = atCap.first.ifEmpty { atCap.second },
                        outsideNight = atCap.first.isEmpty() && atCap.second.isNotEmpty(),
                        share = share,
                    )
                }
            return Result(
                keyMoment = key,
                adviceLowMm = adviceInterval?.first,
                adviceHighMm = adviceInterval?.second,
                landscape = landscape,
                portrait = portraitOutcome,
                verdict = Verdict.ShareConflict(liveFact),
                lowAltitudeHint = false,
            )
        }

        val recommended = when {
            landscape.recommendedFocalMm == null -> portraitOutcome
            portraitOutcome.recommendedFocalMm == null -> landscape
            landscape.totalWindowMinutes >= portraitOutcome.totalWindowMinutes -> landscape
            else -> portraitOutcome
        }
        val verdict = when {
            recommended.segments.isEmpty() && recommended.geometricSegments.isNotEmpty() ->
                Verdict.AllOutsideNight
            recommended.longestSegmentMinutes < MIN_WINDOW_MINUTES -> Verdict.WindowTooShort
            else -> Verdict.Feasible(recommendedPortrait = recommended.portrait)
        }
        return Result(
            keyMoment = key,
            adviceLowMm = adviceInterval?.first,
            adviceHighMm = adviceInterval?.second,
            landscape = landscape,
            portrait = portraitOutcome,
            verdict = verdict,
            lowAltitudeHint = key.altitudeDeg < LOW_ALTITUDE_HINT_DEG,
        )
    }

    /**
     * One orientation's solve: feasible interval, f* and the
     * windows at f*. A null cap (or a cap under the advice floor)
     * leaves the feasible fields null — the numbers that do exist
     * (the cap) stay on the outcome for the honest wording.
     */
    private fun solveOrientation(
        series: Series,
        foregroundTopDeg: Double,
        portrait: Boolean,
        capFocalMm: Double?,
        adviceLow: Double,
        adviceHigh: Double,
        nightSpan: NightTimeline.Interval?,
    ): OrientationOutcome {
        if (capFocalMm == null) {
            return OrientationOutcome(portrait, null, null, null, null, emptyList(), emptyList(), 0.0, 0.0)
        }
        val low = maxOf(adviceLow, MIN_FOCAL_MM)
        val high = minOf(adviceHigh, capFocalMm, MAX_FOCAL_MM)
        if (low > high) {
            return OrientationOutcome(portrait, capFocalMm, null, null, null, emptyList(), emptyList(), 0.0, 0.0)
        }
        val star = recommendedFocal(series, foregroundTopDeg, portrait, low, high, nightSpan)
        val (segments, geometric) = series.windowsAt(foregroundTopDeg, portrait, star, nightSpan)
        return OrientationOutcome(
            portrait = portrait,
            capFocalMm = capFocalMm,
            feasibleLowMm = low,
            feasibleHighMm = high,
            recommendedFocalMm = star,
            segments = segments,
            geometricSegments = geometric,
            totalWindowMinutes = segments.sumOf { it.durationMinutes },
            longestSegmentMinutes = segments.maxOfOrNull { it.durationMinutes } ?: 0.0,
        )
    }

    /**
     * f*: the largest focal in [low, high] whose total window keeps
     * [WINDOW_RULE_FRACTION] of the best total over the interval.
     * Scanned on a log grid (focal perception is logarithmic), then
     * the threshold crossing above the winner is bisected — the
     * rule exists because the window at the cap is typically a
     * one-sided stub, and landing on it by construction is the
     * failure this rule prevents (§7.9 §5).
     */
    private fun recommendedFocal(
        series: Series,
        foregroundTopDeg: Double,
        portrait: Boolean,
        low: Double,
        high: Double,
        nightSpan: NightTimeline.Interval?,
    ): Double {
        fun totalAt(focal: Double): Double =
            series.windowsAt(foregroundTopDeg, portrait, focal, nightSpan)
                .first.sumOf { it.durationMinutes }
        if (high <= low) return high
        val gridCount = 41
        val ratio = high / low
        val grid = (0 until gridCount).map { i ->
            low * Math.pow(ratio, i.toDouble() / (gridCount - 1))
        }
        val totals = grid.map { totalAt(it) }
        val best = totals.max()
        if (best <= 0.0) return high
        val threshold = WINDOW_RULE_FRACTION * best
        val winnerIndex = grid.indices.last { totals[it] >= threshold }
        if (winnerIndex == gridCount - 1) return high
        // Bisect the threshold crossing in (grid[winner], grid[winner+1]).
        var lo = grid[winnerIndex]
        var hi = grid[winnerIndex + 1]
        repeat(14) {
            val mid = (lo + hi) / 2.0
            if (totalAt(mid) >= threshold) lo = mid else hi = mid
        }
        return lo
    }

    /**
     * The sampled night, prepared once: unwrapped azimuth series
     * for the centre and every range point, so interpolation never
     * tears at the 0°/360° seam.
     */
    private class Series(
        private val samples: List<Sample>,
        private val bearingDeg: Double,
    ) {
        private val times: LongArray = LongArray(samples.size) { samples[it].instant.toEpochMilli() }
        private val centreAlt: DoubleArray = DoubleArray(samples.size) { samples[it].centre.altitudeDeg }
        /** Centre azimuth difference to the bearing, unwrapped-continuous. */
        private val centreDiff: DoubleArray = unwrapDiffs(samples.map { it.centre.azimuthDeg })
        private val pointCount: Int = samples.first().points.size
        private val pointAlt: List<DoubleArray> = (0 until pointCount).map { p ->
            DoubleArray(samples.size) { samples[it].points[p].altitudeDeg }
        }
        private val pointAz: List<DoubleArray> = (0 until pointCount).map { p ->
            unwrapAbsolute(samples.map { it.points[p].azimuthDeg })
        }
        private val centreAz: DoubleArray = unwrapAbsolute(samples.map { it.centre.azimuthDeg })

        private fun unwrapDiffs(azimuths: List<Double>): DoubleArray {
            val out = DoubleArray(azimuths.size)
            var prev = signedDiffToBearing(azimuths.first())
            out[0] = prev
            for (i in 1 until azimuths.size) {
                var d = signedDiffToBearing(azimuths[i])
                while (d - prev > 180.0) d -= 360.0
                while (d - prev < -180.0) d += 360.0
                out[i] = d
                prev = d
            }
            return out
        }

        private fun unwrapAbsolute(azimuths: List<Double>): DoubleArray {
            val out = DoubleArray(azimuths.size)
            var prev = azimuths.first()
            out[0] = prev
            for (i in 1 until azimuths.size) {
                var d = azimuths[i]
                while (d - prev > 180.0) d -= 360.0
                while (d - prev < -180.0) d += 360.0
                out[i] = d
                prev = d
            }
            return out
        }

        private fun signedDiffToBearing(azimuthDeg: Double): Double {
            val az = ((azimuthDeg % 360.0) + 360.0) % 360.0
            val bearing = ((bearingDeg % 360.0) + 360.0) % 360.0
            var diff = az - bearing
            if (diff <= -180.0) diff += 360.0
            if (diff > 180.0) diff -= 360.0
            return diff
        }

        /** Piecewise-linear interpolation of [values] at epoch millis [t]. */
        private fun interpAt(values: DoubleArray, t: Long): Double {
            if (t <= times.first()) return values.first()
            if (t >= times.last()) return values.last()
            var i = 0
            while (times[i + 1] < t) i++
            val span = (times[i + 1] - times[i]).toDouble()
            if (span <= 0.0) return values[i]
            val f = (t - times[i]).toDouble() / span
            return values[i] + f * (values[i + 1] - values[i])
        }

        /** The key moment: the centre's closest approach to the bearing. */
        fun keyMoment(): KeyMoment {
            // Candidate instants: every interpolated zero crossing of
            // the centre difference, plus the closest sample.
            var bestT = times[0]
            var bestAbs = abs(centreDiff[0])
            var bestAlt = centreAlt[0]
            for (i in centreDiff.indices) {
                val v = abs(centreDiff[i])
                if (v < bestAbs) {
                    bestAbs = v
                    bestT = times[i]
                    bestAlt = centreAlt[i]
                }
            }
            var crossingT: Long? = null
            var crossingAlt = 0.0
            for (j in 0 until centreDiff.size - 1) {
                if (centreDiff[j] == 0.0) {
                    crossingT = times[j]
                    crossingAlt = centreAlt[j]
                    break
                }
                if (centreDiff[j] * centreDiff[j + 1] < 0.0) {
                    val f = (0.0 - centreDiff[j]) / (centreDiff[j + 1] - centreDiff[j])
                    val t = times[j] + (f * (times[j + 1] - times[j])).roundToLong()
                    // Prefer the crossing whose altitude is highest
                    // (the night's real pass, not a below-horizon sweep).
                    val alt = interpAt(centreAlt, t)
                    if (crossingT == null || alt > crossingAlt) {
                        crossingT = t
                        crossingAlt = alt
                    }
                }
            }
            return if (crossingT != null) {
                KeyMoment(Instant.ofEpochMilli(crossingT), crossingAlt, exactCrossing = true, residualDeg = 0.0)
            } else {
                KeyMoment(Instant.ofEpochMilli(bestT), bestAlt, exactCrossing = false, residualDeg = bestAbs)
            }
        }

        /** The range's top altitude at [t] (the centre's when there are no range points). */
        fun rangeTopAltitudeAt(t: Instant): Double {
            val ms = t.toEpochMilli()
            if (pointCount == 0) return interpAt(centreAlt, ms)
            return (0 until pointCount).maxOf { p -> interpAt(pointAlt[p], ms) }
        }

        /**
         * Frame slack at sample [i] for a placed frame: the minimum
         * over the judged points of the smaller tangent-plane margin
         * (≥ 0 = inside). Judged points are the range points, or the
         * centre alone when the subject carries none.
         */
        private fun slackAt(
            i: Int,
            frameCentreAltDeg: Double,
            spec: FramingChart.FrameSpec,
        ): Double {
            val (halfRight, halfUp) = FramingChart.frameHalfExtents(spec)
            var slack = Double.POSITIVE_INFINITY
            fun judge(azDeg: Double, altDeg: Double) {
                val point = FramingChart.tangentPoint(bearingDeg, frameCentreAltDeg, azDeg, altDeg)
                if (point == null) {
                    slack = Double.NEGATIVE_INFINITY
                    return
                }
                val (u, v) = FramingChart.frameCoords(point, spec)
                val s = minOf(halfRight - abs(u), halfUp - abs(v))
                if (s < slack) slack = s
            }
            if (pointCount == 0) {
                judge(centreAz[i], centreAlt[i])
            } else {
                for (p in 0 until pointCount) judge(pointAz[p][i], pointAlt[p][i])
            }
            return slack
        }

        /** All-in test at an arbitrary instant (interpolated series). */
        private fun allInAt(
            t: Long,
            frameCentreAltDeg: Double,
            spec: FramingChart.FrameSpec,
        ): Boolean {
            val (halfRight, halfUp) = FramingChart.frameHalfExtents(spec)
            fun judge(azDeg: Double, altDeg: Double): Boolean {
                val point = FramingChart.tangentPoint(bearingDeg, frameCentreAltDeg, azDeg, altDeg)
                    ?: return false
                val (u, v) = FramingChart.frameCoords(point, spec)
                return abs(u) <= halfRight && abs(v) <= halfUp
            }
            return if (pointCount == 0) {
                judge(interpAt(centreAz, t), interpAt(centreAlt, t))
            } else {
                (0 until pointCount).all { p ->
                    judge(interpAt(pointAz[p], t), interpAt(pointAlt[p], t))
                }
            }
        }

        /**
         * The cap: the largest focal in the slider domain holding
         * the whole range at the key moment, or null when even the
         * widest focal fails there. Full containment degrades
         * monotonically as focal grows (the vertical field shrinks
         * and the placed centre sinks toward the foreground top),
         * so bisection is sound.
         */
        fun capAtKeyMoment(foregroundTopDeg: Double, portrait: Boolean): Double? {
            val key = keyMoment()
            val t = key.instant.toEpochMilli()
            fun fits(focal: Double): Boolean {
                val centreAlt = frameCentreAltitude(foregroundTopDeg, focal, portrait)
                return allInAt(t, centreAlt, FramingChart.FrameSpec(focal, portrait, 0.0))
            }
            if (!fits(MIN_FOCAL_MM)) return null
            if (fits(MAX_FOCAL_MM)) return MAX_FOCAL_MM
            var lo = MIN_FOCAL_MM
            var hi = MAX_FOCAL_MM
            repeat(40) {
                val mid = (lo + hi) / 2.0
                if (fits(mid)) lo = mid else hi = mid
            }
            return lo
        }

        /**
         * The windows at one focal: (night-cut segments, geometric
         * segments). Boundaries are the interpolated zero crossings
         * of the frame slack; best moments follow the three forms.
         */
        fun windowsAt(
            foregroundTopDeg: Double,
            portrait: Boolean,
            focalMm: Double,
            nightSpan: NightTimeline.Interval?,
        ): Pair<List<Segment>, List<Segment>> {
            val centreAlt = frameCentreAltitude(foregroundTopDeg, focalMm, portrait)
            val spec = FramingChart.FrameSpec(focalMm, portrait, 0.0)
            val n = samples.size
            val slacks = DoubleArray(n) { slackAt(it, centreAlt, spec) }
            val geometric = mutableListOf<Segment>()
            val indexList = samples.indices.toList()
            for (run in NightTimeline.contiguousRuns(indexList) { slacks[it] >= 0.0 }) {
                val a = run.first
                val b = run.last
                val start: Long = if (a == 0) {
                    times[0]
                } else {
                    val f = slacks[a - 1] / (slacks[a - 1] - slacks[a])
                    times[a - 1] + (f * (times[a] - times[a - 1])).roundToLong()
                }
                val end: Long = if (b == n - 1) {
                    times[n - 1]
                } else {
                    val f = slacks[b] / (slacks[b] - slacks[b + 1])
                    times[b] + (f * (times[b + 1] - times[b])).roundToLong()
                }
                if (end <= start) continue
                geometric += Segment(
                    start = Instant.ofEpochMilli(start),
                    end = Instant.ofEpochMilli(end),
                    best = bestIn(start, end),
                    minAltitudeDeg = centreRangeIn(start, end).first,
                    maxAltitudeDeg = centreRangeIn(start, end).second,
                )
            }
            if (nightSpan == null) return Pair(geometric, geometric)
            val cut = mutableListOf<Segment>()
            for (raw in geometric) {
                val cutStart = maxOf(raw.start, nightSpan.start)
                val cutEnd = minOf(raw.end, nightSpan.end)
                if (!cutEnd.isAfter(cutStart)) continue
                cut += if (cutStart == raw.start && cutEnd == raw.end) {
                    raw
                } else {
                    cutSegment(raw, cutStart, cutEnd)
                }
            }
            return Pair(cut, geometric)
        }

        /** The frame centre altitude under the placement rule (§7.9 §2.1). */
        private fun frameCentreAltitude(foregroundTopDeg: Double, focalMm: Double, portrait: Boolean): Double =
            foregroundTopDeg + (0.5 - FOREGROUND_TOP_FRACTION) * verticalFovDeg(focalMm, portrait)

        /** Centre altitude min/max over [start, end] (samples plus interpolated ends). */
        private fun centreRangeIn(start: Long, end: Long): Pair<Double, Double> {
            val values = mutableListOf(interpAt(centreAlt, start), interpAt(centreAlt, end))
            for (i in times.indices) {
                if (times[i] in start..end) values += centreAlt[i]
            }
            return Pair(values.min(), values.max())
        }

        /**
         * The best moment inside [start, end]: a zero crossing of
         * the centre difference inside the span is the crossing
         * form; otherwise the closest approach inside the span.
         */
        private fun bestIn(start: Long, end: Long): BestMoment {
            for (j in 0 until centreDiff.size - 1) {
                if (centreDiff[j] == 0.0 && times[j] in start..end) {
                    return BestMoment.Crossing(Instant.ofEpochMilli(times[j]), centreAlt[j])
                }
                if (centreDiff[j] * centreDiff[j + 1] < 0.0) {
                    val f = (0.0 - centreDiff[j]) / (centreDiff[j + 1] - centreDiff[j])
                    val t = times[j] + (f * (times[j + 1] - times[j])).roundToLong()
                    if (t in start..end) {
                        return BestMoment.Crossing(Instant.ofEpochMilli(t), interpAt(centreAlt, t))
                    }
                }
            }
            var bestT = start
            var bestAbs = abs(interpAt(centreDiff, start))
            for (i in times.indices) {
                if (times[i] in start..end) {
                    val v = abs(centreDiff[i])
                    if (v < bestAbs) {
                        bestAbs = v
                        bestT = times[i]
                    }
                }
            }
            val endAbs = abs(interpAt(centreDiff, end))
            if (endAbs < bestAbs) {
                bestAbs = endAbs
                bestT = end
            }
            return BestMoment.ClosestInSegment(
                Instant.ofEpochMilli(bestT), interpAt(centreAlt, bestT), bestAbs,
            )
        }

        /**
         * [raw] cut to [cutStart, cutEnd]: a crossing the cut
         * removed is reported as outside with the nearer endpoint
         * standing in; a closest point is recomputed inside what
         * remains (the predecessor's discipline, §7.9).
         */
        private fun cutSegment(raw: Segment, cutStart: Instant, cutEnd: Instant): Segment {
            val startMs = cutStart.toEpochMilli()
            val endMs = cutEnd.toEpochMilli()
            val best: BestMoment = when (val rawBest = raw.best) {
                is BestMoment.Crossing -> when {
                    rawBest.instant.isBefore(cutStart) -> BestMoment.CrossingOutsideNight(
                        SegmentEdge.START, cutStart, rawBest.instant, rawBest.altitudeDeg,
                    )
                    rawBest.instant.isAfter(cutEnd) -> BestMoment.CrossingOutsideNight(
                        SegmentEdge.END, cutEnd, rawBest.instant, rawBest.altitudeDeg,
                    )
                    else -> rawBest
                }
                is BestMoment.ClosestInSegment -> bestIn(startMs, endMs)
                is BestMoment.CrossingOutsideNight -> rawBest
            }
            val range = centreRangeIn(startMs, endMs)
            return Segment(
                start = cutStart,
                end = cutEnd,
                best = best,
                minAltitudeDeg = range.first,
                maxAltitudeDeg = range.second,
            )
        }
    }
}
