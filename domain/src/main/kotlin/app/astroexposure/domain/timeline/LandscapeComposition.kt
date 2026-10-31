package app.astroexposure.domain.timeline

import app.astroexposure.domain.camera.FramingChart
import app.astroexposure.domain.camera.FullFrameFraming
import java.time.Instant
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToLong
import kotlin.math.tan

/**
 * The landscape composition model, second revision (ui-design
 * §7.9, 0.16.0): the subject is the anchor and the focal length is
 * derived, not chosen.
 *
 * The subject is already selected, so the frame's vertical
 * midline follows the subject centre's azimuth A(t) at every
 * instant; the foreground's azimuth is therefore a derived
 * quantity and never an input. The only site quantity is the
 * foreground top altitude T, read as a representative value along
 * the subject's track and held constant over the night. How much
 * foreground enters the picture is set by T alone: the foreground
 * top rests at [FOREGROUND_TOP_FRACTION] of the frame height
 * above the lower edge (the placement rule), and the vertical
 * span from T to the subject's top is what the frame must hold.
 *
 * The solve, per orientation:
 * - the anchor t̂ is the instant of greatest subject-centre
 *   altitude inside the night span (the whole sample axis when no
 *   night span is given); the span is largest there, so a focal
 *   derived at t̂ is the night's most conservative one. The anchor
 *   may sit on a night boundary, for instance when the centre
 *   descends monotonically all night.
 * - the vertical span S at t̂ is read in the tangent plane at the
 *   subject centre (roll 0): S = v_top − v_fg, where v_fg is the
 *   tangent-plane height of the point (A(t̂), T) and v_top the
 *   greatest tangent-plane height of the subject's range points.
 *   The subject's bottom does not enter the span; sinking below T
 *   is judged separately, as a window condition.
 * - the frame height the span needs is H = S / (1 − p) with
 *   p = [FOREGROUND_TOP_FRACTION], the required vertical field is
 *   V_req = 2·atan(H/2), and the closed-form focal is the sensor's
 *   vertical side over H (24 mm landscape, 36 mm portrait, so the
 *   portrait closed form is always 1.5x the landscape one).
 * - an exact containment check at t̂ may only shorten that focal:
 *   with the tangent point moved to the placed frame centre, the
 *   candidate is verified with [FramingChart.tangentPoint] and
 *   [FramingChart.pointInFrame]; a failing candidate is bisected
 *   down towards [MIN_FOCAL_MM], never raised. The vertical span
 *   the user set is the primary constraint; a horizontal overflow
 *   is answered with a shorter focal, never with a taller frame
 *   that would push the foreground out.
 * - the windows at the derived focal are the spans where the
 *   whole range is inside the placed frame AND no range point
 *   has sunk below T, cut to the night span. A segment's best
 *   moment is the point of greatest vertical margin inside it
 *   (see [Series.bestMomentIn]).
 * - the subject's share of the long side at the derived focal is
 *   a reported fact only: it never changes a verdict and never
 *   moves a focal.
 *
 * Judgement reuses §7.10's construction: the same tangent-plane
 * transform and the same in-frame test, with the tangent point at
 * the placed frame centre and the coordinates read as (azimuth,
 * altitude). The single placement formula lives in
 * [frameCentreAltitudeDeg]; nothing else in the codebase
 * restates it.
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
 * two window constraints, the same interpolation discipline as
 * §7.4.
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

    /**
     * The shortest window still presented as usable, minutes: twice
     * the sampling step — below it the interpolated boundary error
     * is the same order as the window itself (§7.9).
     */
    const val MIN_WINDOW_MINUTES = 10.0

    /**
     * The low-altitude hint rail, degrees: below this centre
     * altitude at the anchor the result carries a neutral
     * extinction / obstruction note. A hint only — it never cuts a
     * window and never forms a diagnosis (§7.9).
     */
    const val LOW_ALTITUDE_HINT_DEG = 5.0

    /** One sampling step, minutes: the recommendation tie band (§7.9). */
    private const val SAMPLE_STEP_MINUTES = 5.0

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

    /** The anchor t̂: the night's greatest centre altitude and where it falls. */
    data class AnchorMoment(
        val instant: Instant,
        /** Centre altitude at the anchor, degrees. */
        val centreAltitudeDeg: Double,
    )

    /** A segment's best moment: the greatest vertical margin inside it. */
    data class BestMoment(
        val instant: Instant,
        /** Centre altitude at the best moment, degrees. */
        val centreAltitudeDeg: Double,
    )

    /** One in-window span: bounds, best moment, centre altitude range. */
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
     * One orientation's solved state. [derivedFocalMm] is null when
     * the orientation cannot be used at the anchor (the closed form
     * undercuts the widest focal, or even the widest focal fails
     * the exact check there); [closedFormFocalMm] and
     * [requiredVerticalFovDeg] are reported regardless, as the
     * honest size of the span that was asked for.
     * [requiredVerticalFovDeg] is null when the span at the anchor
     * is not positive — the placement formula then has no required
     * field to state, and printing a number would invent one
     * (0.17.0).
     * [focalCappedAtMax] marks a closed form beyond the slider
     * domain, solved at [MAX_FOCAL_MM]. [share] is the subject's
     * share of the long side at the derived focal, null without
     * size data or a derived focal. [segments] are the night-cut
     * windows at the derived focal; [geometricSegments] the same
     * windows before the night cut.
     */
    data class OrientationOutcome(
        val portrait: Boolean,
        val derivedFocalMm: Double?,
        val closedFormFocalMm: Double,
        val requiredVerticalFovDeg: Double?,
        val focalCappedAtMax: Boolean,
        val share: Double?,
        val segments: List<Segment>,
        val geometricSegments: List<Segment>,
        val totalWindowMinutes: Double,
        val longestSegmentMinutes: Double,
    )

    /** The night's verdict — exactly one, in solver precedence (§7.9). */
    sealed interface Verdict {
        /** A usable composition exists; [recommendedPortrait] names the recommended orientation. */
        data class Feasible(val recommendedPortrait: Boolean) : Verdict

        /**
         * The subject's lowest range point stays under the
         * foreground top at every sampled instant of the axis: the
         * subject passes behind the foreground all night and no
         * focal length can change that. [maxBottomAltitudeDeg] is
         * the greatest of those lowest-point altitudes, reached at
         * [atInstant].
         */
        data class NeverAboveForegroundTop(
            val maxBottomAltitudeDeg: Double,
            val atInstant: Instant,
            val foregroundTopDeg: Double,
        ) : Verdict

        /**
         * At the anchor, neither orientation yields a derived
         * focal: the vertical span needs [requiredVerticalFovDeg]
         * of vertical field, more than the widest frame holds.
         * [requiredVerticalFovDeg] is null when the span at the
         * anchor is not positive (the subject top does not rise
         * past the placement the foreground asks for there): no
         * required field exists to state.
         */
        data class SpanExceedsWidestFrame(
            val anchorInstant: Instant,
            val requiredVerticalFovDeg: Double?,
        ) : Verdict

        /**
         * Windows exist geometrically but every one lies outside
         * the night span. Carries the recommended orientation —
         * the same domain recommendation [Feasible] carries — so
         * no caller re-derives it (0.17.0).
         */
        data class AllOutsideNight(val recommendedPortrait: Boolean) : Verdict

        /**
         * Solvable, but the recommended orientation's longest
         * night window is under the usable floor. Carries the
         * recommended orientation, like [Feasible] (0.17.0).
         */
        data class WindowTooShort(val recommendedPortrait: Boolean) : Verdict
    }

    /** The full evaluation of one night, one subject, one foreground top. */
    data class Result(
        val anchor: AnchorMoment,
        val landscape: OrientationOutcome,
        val portrait: OrientationOutcome,
        val verdict: Verdict,
        /** Anchor centre altitude under the hint rail (feasible-family verdicts only). */
        val lowAltitudeHint: Boolean,
    )

    /** The frame centre's altitude for a placed frame (§7.9): T + (0.5 − p) · V. */
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
     * Evaluate one night. [samples] are the subject's series over
     * the night axis (at least two, in time order);
     * [foregroundTopDeg] is the foreground top altitude T;
     * [sizeMajorArcmin] the subject's catalogue major axis (for the
     * share figure only); [nightSpan] the span windows are cut to,
     * or null to leave them uncut.
     */
    fun evaluate(
        samples: List<Sample>,
        foregroundTopDeg: Double,
        sizeMajorArcmin: Double?,
        nightSpan: NightTimeline.Interval?,
    ): Result {
        require(samples.size >= 2) { "landscape composition needs a sampled series" }
        val series = Series(samples)
        val anchor = series.anchorMoment(nightSpan)
        val anchorMs = anchor.instant.toEpochMilli()

        // The vertical span at the anchor and the closed forms it
        // fixes. A non-positive frame height (the subject top does
        // not rise past the placement the foreground asks for)
        // leaves the required field not applicable (null — never a
        // printed zero) and the closed form infinite, which then
        // walks the capped-at-maximum path below.
        val span = series.verticalSpanAt(anchorMs, foregroundTopDeg)
        val frameHeight = span / (1.0 - FOREGROUND_TOP_FRACTION)
        val requiredVerticalFovDeg =
            if (frameHeight <= 0.0) null else Math.toDegrees(2.0 * atan(frameHeight / 2.0))
        fun closedFormFor(portrait: Boolean): Double =
            if (frameHeight <= 0.0) {
                Double.POSITIVE_INFINITY
            } else {
                (if (portrait) FullFrameFraming.FRAME_LONG_MM else FullFrameFraming.FRAME_SHORT_MM) /
                    frameHeight
            }

        fun unsolvedOutcome(portrait: Boolean): OrientationOutcome {
            val closed = closedFormFor(portrait)
            return OrientationOutcome(
                portrait = portrait,
                derivedFocalMm = null,
                closedFormFocalMm = closed,
                requiredVerticalFovDeg = requiredVerticalFovDeg,
                focalCappedAtMax = closed > MAX_FOCAL_MM,
                share = null,
                segments = emptyList(),
                geometricSegments = emptyList(),
                totalWindowMinutes = 0.0,
                longestSegmentMinutes = 0.0,
            )
        }

        // Verdict 1: the subject never rises past the foreground
        // top. The lowest range point's altitude is read at the
        // samples; when even its greatest value stays under T, no
        // focal exists to solve for — both orientations report
        // their closed forms with no derived focal and no windows.
        val (maxBottom, maxBottomMs) = series.maxBottom()
        if (maxBottom < foregroundTopDeg) {
            return Result(
                anchor = anchor,
                landscape = unsolvedOutcome(portrait = false),
                portrait = unsolvedOutcome(portrait = true),
                verdict = Verdict.NeverAboveForegroundTop(
                    maxBottomAltitudeDeg = maxBottom,
                    atInstant = Instant.ofEpochMilli(maxBottomMs),
                    foregroundTopDeg = foregroundTopDeg,
                ),
                lowAltitudeHint = false,
            )
        }

        fun solveOrientation(portrait: Boolean): OrientationOutcome {
            val closed = closedFormFor(portrait)
            var derived: Double? = null
            if (closed >= MIN_FOCAL_MM) {
                val candidate = minOf(closed, MAX_FOCAL_MM)
                derived = series.focalAtAnchor(anchorMs, foregroundTopDeg, portrait, candidate)
            }
            val share = derived?.let { focal ->
                sizeMajorArcmin?.let { FullFrameFraming.shareAtFocal(it, focal) }
            }
            val (segments, geometric) = if (derived != null) {
                series.windowsAt(foregroundTopDeg, portrait, derived, nightSpan)
            } else {
                Pair(emptyList(), emptyList())
            }
            return OrientationOutcome(
                portrait = portrait,
                derivedFocalMm = derived,
                closedFormFocalMm = closed,
                requiredVerticalFovDeg = requiredVerticalFovDeg,
                focalCappedAtMax = closed > MAX_FOCAL_MM,
                share = share,
                segments = segments,
                geometricSegments = geometric,
                totalWindowMinutes = segments.sumOf { it.durationMinutes },
                longestSegmentMinutes = segments.maxOfOrNull { it.durationMinutes } ?: 0.0,
            )
        }

        val landscape = solveOrientation(portrait = false)
        val portraitOutcome = solveOrientation(portrait = true)

        // Verdict 2: the span defeats the widest frame in both
        // orientations at the anchor.
        val feasible = listOf(landscape, portraitOutcome).filter { it.derivedFocalMm != null }
        if (feasible.isEmpty()) {
            return Result(
                anchor = anchor,
                landscape = landscape,
                portrait = portraitOutcome,
                verdict = Verdict.SpanExceedsWidestFrame(
                    anchorInstant = anchor.instant,
                    requiredVerticalFovDeg = requiredVerticalFovDeg,
                ),
                lowAltitudeHint = false,
            )
        }

        // The recommended orientation (the §7.9 rule, stated on the
        // night-cut windows): among the orientations that derived a
        // focal, the one whose longest night segment is longer;
        // when the longest segments differ by at most one sampling
        // step, the larger derived focal (the same window with the
        // subject larger in frame); when still tied, landscape.
        val recommended = if (feasible.size == 1) {
            feasible.first()
        } else {
            val diff = portraitOutcome.longestSegmentMinutes - landscape.longestSegmentMinutes
            when {
                diff > SAMPLE_STEP_MINUTES -> portraitOutcome
                diff < -SAMPLE_STEP_MINUTES -> landscape
                portraitOutcome.derivedFocalMm!! > landscape.derivedFocalMm!! -> portraitOutcome
                else -> landscape
            }
        }

        val verdict = when {
            recommended.segments.isEmpty() && recommended.geometricSegments.isNotEmpty() ->
                Verdict.AllOutsideNight(recommendedPortrait = recommended.portrait)
            recommended.longestSegmentMinutes < MIN_WINDOW_MINUTES ->
                Verdict.WindowTooShort(recommendedPortrait = recommended.portrait)
            else -> Verdict.Feasible(recommendedPortrait = recommended.portrait)
        }
        return Result(
            anchor = anchor,
            landscape = landscape,
            portrait = portraitOutcome,
            verdict = verdict,
            lowAltitudeHint = anchor.centreAltitudeDeg < LOW_ALTITUDE_HINT_DEG,
        )
    }

    /**
     * The sampled night, prepared once: unwrapped azimuth series
     * for the centre and every range point, so interpolation never
     * tears at the 0°/360° seam, alongside the centre and point
     * altitude series. All interpolation is piecewise-linear
     * between samples.
     */
    private class Series(
        private val samples: List<Sample>,
    ) {
        private val times: LongArray = LongArray(samples.size) { samples[it].instant.toEpochMilli() }
        private val centreAlt: DoubleArray = DoubleArray(samples.size) { samples[it].centre.altitudeDeg }
        private val centreAz: DoubleArray = unwrapAbsolute(samples.map { it.centre.azimuthDeg })
        private val pointCount: Int = samples.first().points.size
        private val pointAlt: List<DoubleArray> = (0 until pointCount).map { p ->
            DoubleArray(samples.size) { samples[it].points[p].altitudeDeg }
        }
        private val pointAz: List<DoubleArray> = (0 until pointCount).map { p ->
            unwrapAbsolute(samples.map { it.points[p].azimuthDeg })
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

        private fun centreAltAt(t: Long): Double = interpAt(centreAlt, t)

        private fun centreAzAt(t: Long): Double = interpAt(centreAz, t)

        /** The judged points at [t]: the range points, or the centre alone. */
        private fun judgedPointsAt(t: Long): List<PointSample> =
            if (pointCount == 0) {
                listOf(PointSample(centreAzAt(t), centreAltAt(t)))
            } else {
                (0 until pointCount).map { p ->
                    PointSample(interpAt(pointAz[p], t), interpAt(pointAlt[p], t))
                }
            }

        /** The lowest judged altitude at sample [i] (the centre's when there are no range points). */
        private fun bottomAltAtSample(i: Int): Double =
            if (pointCount == 0) {
                centreAlt[i]
            } else {
                (0 until pointCount).minOf { p -> pointAlt[p][i] }
            }

        /** The lowest judged altitude at [t], from the interpolated point series. */
        private fun bottomAltAtTime(t: Long): Double =
            if (pointCount == 0) {
                centreAltAt(t)
            } else {
                (0 until pointCount).minOf { p -> interpAt(pointAlt[p], t) }
            }

        /** The greatest sample bottom altitude and the first instant it is reached. */
        fun maxBottom(): Pair<Double, Long> {
            var best = Double.NEGATIVE_INFINITY
            var bestT = times.first()
            for (i in samples.indices) {
                val bottom = bottomAltAtSample(i)
                if (bottom > best) {
                    best = bottom
                    bestT = times[i]
                }
            }
            return Pair(best, bestT)
        }

        /**
         * The anchor: the instant of greatest centre altitude
         * inside the night span (the whole axis when [nightSpan] is
         * null). The centre altitude is piecewise-linear, so its
         * maximum over the span falls on a sample or on a span
         * endpoint; the endpoints are evaluated by interpolation,
         * so a night that only descends anchors exactly on its
         * start. Ties keep the earliest instant.
         */
        fun anchorMoment(nightSpan: NightTimeline.Interval?): AnchorMoment {
            val lo = (nightSpan?.start?.toEpochMilli() ?: times.first())
                .coerceIn(times.first(), times.last())
            val hi = (nightSpan?.end?.toEpochMilli() ?: times.last())
                .coerceIn(times.first(), times.last())
            val candidates = sortedSetOf(lo, hi)
            for (t in times) if (t in lo..hi) candidates += t
            var bestT = lo
            var bestAlt = Double.NEGATIVE_INFINITY
            for (t in candidates) {
                val alt = centreAltAt(t)
                if (alt > bestAlt) {
                    bestAlt = alt
                    bestT = t
                }
            }
            return AnchorMoment(Instant.ofEpochMilli(bestT), bestAlt)
        }

        /**
         * The vertical span S at [t] (see the object doc): the
         * tangent plane sits at the subject centre, so the
         * foreground-top point (A(t), T) shares the centre's
         * azimuth and its height has the closed form
         * tan(T − centre altitude) — the same value
         * [FramingChart.tangentPoint] returns for a same-azimuth
         * point, used directly when that call reports the point
         * 90+ degrees away. A range point with no tangent
         * projection counts as infinitely high.
         */
        fun verticalSpanAt(t: Long, foregroundTopDeg: Double): Double {
            val cAz = centreAzAt(t)
            val cAlt = centreAltAt(t)
            val vForeground = FramingChart.tangentPoint(cAz, cAlt, cAz, foregroundTopDeg)?.yNorth
                ?: tan(Math.toRadians(foregroundTopDeg - cAlt))
            if (pointCount == 0) return 0.0 - vForeground
            var vTop = Double.NEGATIVE_INFINITY
            for (point in judgedPointsAt(t)) {
                val tangent = FramingChart.tangentPoint(cAz, cAlt, point.azimuthDeg, point.altitudeDeg)
                    ?: return Double.POSITIVE_INFINITY
                if (tangent.yNorth > vTop) vTop = tangent.yNorth
            }
            return vTop - vForeground
        }

        /** All-in test at an arbitrary instant (interpolated series). */
        private fun allInAt(
            t: Long,
            frameCentreAltDeg: Double,
            spec: FramingChart.FrameSpec,
        ): Boolean {
            val cAz = centreAzAt(t)
            for (point in judgedPointsAt(t)) {
                val tangent = FramingChart.tangentPoint(
                    cAz, frameCentreAltDeg, point.azimuthDeg, point.altitudeDeg,
                ) ?: return false
                if (!FramingChart.pointInFrame(tangent, spec)) return false
            }
            return true
        }

        /**
         * The derived focal at the anchor for one orientation: the
         * candidate itself when the exact check holds there, else
         * the largest focal in [MIN_FOCAL_MM, candidate] that
         * holds, else null. Full containment degrades monotonically
         * as the focal grows (the frame shrinks around a centre the
         * placement rule holds to the foreground), so bisection is
         * sound; the search only ever shortens the candidate.
         */
        fun focalAtAnchor(
            t: Long,
            foregroundTopDeg: Double,
            portrait: Boolean,
            candidate: Double,
        ): Double? {
            fun fits(focal: Double): Boolean {
                val centreAlt = LandscapeComposition.frameCentreAltitudeDeg(
                    foregroundTopDeg, focal, portrait,
                )
                return allInAt(t, centreAlt, FramingChart.FrameSpec(focal, portrait, 0.0))
            }
            if (fits(candidate)) return candidate
            if (!fits(LandscapeComposition.MIN_FOCAL_MM)) return null
            var lo = LandscapeComposition.MIN_FOCAL_MM
            var hi = candidate
            repeat(40) {
                val mid = (lo + hi) / 2.0
                if (fits(mid)) lo = mid else hi = mid
            }
            return lo
        }

        /**
         * Frame slack at [t] for a placed frame: the minimum over
         * the judged points of the smaller tangent-plane margin
         * (≥ 0 = inside). The tangent point sits at the frame
         * centre, whose azimuth is the subject centre's azimuth at
         * [t] — the midline follows the subject.
         */
        private fun frameSlackAtTime(
            t: Long,
            frameCentreAltDeg: Double,
            spec: FramingChart.FrameSpec,
        ): Double {
            val (halfRight, halfUp) = FramingChart.frameHalfExtents(spec)
            val cAz = centreAzAt(t)
            var slack = Double.POSITIVE_INFINITY
            for (point in judgedPointsAt(t)) {
                val tangent = FramingChart.tangentPoint(
                    cAz, frameCentreAltDeg, point.azimuthDeg, point.altitudeDeg,
                ) ?: return Double.NEGATIVE_INFINITY
                val (u, v) = FramingChart.frameCoords(tangent, spec)
                val s = minOf(halfRight - abs(u), halfUp - abs(v))
                if (s < slack) slack = s
            }
            return slack
        }

        /**
         * The windows at one focal: (night-cut segments, geometric
         * segments). A sample is in-window when both constraints
         * hold — the range fully inside the placed frame, and no
         * point sunk below the foreground top. A run's boundary is
         * set by whichever constraint crosses zero inside the
         * neighbouring step: entering takes the later crossing,
         * leaving the earlier one. Runs are never merged.
         */
        fun windowsAt(
            foregroundTopDeg: Double,
            portrait: Boolean,
            focalMm: Double,
            nightSpan: NightTimeline.Interval?,
        ): Pair<List<Segment>, List<Segment>> {
            val frameCentreAlt = LandscapeComposition.frameCentreAltitudeDeg(
                foregroundTopDeg, focalMm, portrait,
            )
            val spec = FramingChart.FrameSpec(focalMm, portrait, 0.0)
            val n = samples.size
            val frameSlacks = DoubleArray(n) { frameSlackAtTime(times[it], frameCentreAlt, spec) }
            val sinkSlacks = DoubleArray(n) { bottomAltAtSample(it) - foregroundTopDeg }
            fun crossingTime(t0: Long, t1: Long, s0: Double, s1: Double): Long {
                if (!s0.isFinite()) return t1
                if (!s1.isFinite()) return t0
                val f = s0 / (s0 - s1)
                return t0 + (f * (t1 - t0)).roundToLong()
            }
            fun entryTime(prev: Int, curr: Int): Long {
                val crossings = mutableListOf<Long>()
                if (frameSlacks[prev] < 0.0 && frameSlacks[curr] >= 0.0) {
                    crossings += crossingTime(times[prev], times[curr], frameSlacks[prev], frameSlacks[curr])
                }
                if (sinkSlacks[prev] < 0.0 && sinkSlacks[curr] >= 0.0) {
                    crossings += crossingTime(times[prev], times[curr], sinkSlacks[prev], sinkSlacks[curr])
                }
                return crossings.maxOrNull() ?: times[curr]
            }
            fun exitTime(curr: Int, next: Int): Long {
                val crossings = mutableListOf<Long>()
                if (frameSlacks[curr] >= 0.0 && frameSlacks[next] < 0.0) {
                    crossings += crossingTime(times[curr], times[next], frameSlacks[curr], frameSlacks[next])
                }
                if (sinkSlacks[curr] >= 0.0 && sinkSlacks[next] < 0.0) {
                    crossings += crossingTime(times[curr], times[next], sinkSlacks[curr], sinkSlacks[next])
                }
                return crossings.minOrNull() ?: times[curr]
            }
            val geometric = mutableListOf<Segment>()
            val indexList = samples.indices.toList()
            for (run in NightTimeline.contiguousRuns(indexList) {
                frameSlacks[it] >= 0.0 && sinkSlacks[it] >= 0.0
            }) {
                val a = run.first
                val b = run.last
                val start: Long = if (a == 0) times[0] else entryTime(a - 1, a)
                val end: Long = if (b == n - 1) times[n - 1] else exitTime(b, b + 1)
                if (end <= start) continue
                geometric += makeSegment(start, end, foregroundTopDeg, frameCentreAlt, spec)
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
                    // A cut segment's best moment is recomputed
                    // inside what remains, never inherited.
                    makeSegment(
                        cutStart.toEpochMilli(), cutEnd.toEpochMilli(),
                        foregroundTopDeg, frameCentreAlt, spec,
                    )
                }
            }
            return Pair(cut, geometric)
        }

        private fun makeSegment(
            start: Long,
            end: Long,
            foregroundTopDeg: Double,
            frameCentreAltDeg: Double,
            spec: FramingChart.FrameSpec,
        ): Segment {
            val range = centreRangeIn(start, end)
            return Segment(
                start = Instant.ofEpochMilli(start),
                end = Instant.ofEpochMilli(end),
                best = bestMomentIn(start, end, foregroundTopDeg, frameCentreAltDeg, spec),
                minAltitudeDeg = range.first,
                maxAltitudeDeg = range.second,
            )
        }

        /** Centre altitude min/max over [start, end] (samples plus interpolated ends). */
        private fun centreRangeIn(start: Long, end: Long): Pair<Double, Double> {
            val values = mutableListOf(centreAltAt(start), centreAltAt(end))
            for (i in times.indices) {
                if (times[i] in start..end) values += centreAlt[i]
            }
            return Pair(values.min(), values.max())
        }

        /** The greatest frame-coordinate height among the judged points at [t]. */
        private fun frameTopAt(
            t: Long,
            frameCentreAltDeg: Double,
            spec: FramingChart.FrameSpec,
        ): Double {
            val cAz = centreAzAt(t)
            var top = Double.NEGATIVE_INFINITY
            for (point in judgedPointsAt(t)) {
                val tangent = FramingChart.tangentPoint(
                    cAz, frameCentreAltDeg, point.azimuthDeg, point.altitudeDeg,
                ) ?: return Double.POSITIVE_INFINITY
                val v = FramingChart.frameCoords(tangent, spec).second
                if (v > top) top = v
            }
            return top
        }

        /**
         * The vertical margin at [t]: the smaller of the upper
         * margin (the range top's angular distance under the
         * frame's upper edge, read in the frame's tangent plane)
         * and the lower margin (the lowest point's altitude above
         * the foreground top).
         */
        private fun verticalMarginAt(
            t: Long,
            foregroundTopDeg: Double,
            frameCentreAltDeg: Double,
            spec: FramingChart.FrameSpec,
        ): Double {
            val halfUp = FramingChart.frameHalfExtents(spec).second
            val vTop = frameTopAt(t, frameCentreAltDeg, spec)
            val upper = Math.toDegrees(atan(halfUp) - atan(vTop))
            val lower = bottomAltAtTime(t) - foregroundTopDeg
            return minOf(upper, lower)
        }

        /**
         * The best moment inside [start, end]: the instant of
         * greatest vertical margin. The segment's samples and ends
         * are scored first; the winning sample's neighbouring
         * intervals are then scanned at one-minute steps over the
         * interpolated series, so the result is a deterministic
         * function of the samples.
         */
        private fun bestMomentIn(
            start: Long,
            end: Long,
            foregroundTopDeg: Double,
            frameCentreAltDeg: Double,
            spec: FramingChart.FrameSpec,
        ): BestMoment {
            fun margin(t: Long): Double = verticalMarginAt(t, foregroundTopDeg, frameCentreAltDeg, spec)
            val coarse = sortedSetOf(start, end)
            for (t in times) if (t in start..end) coarse += t
            val coarseList = coarse.toList()
            var bestIndex = 0
            var bestMargin = Double.NEGATIVE_INFINITY
            for ((index, t) in coarseList.withIndex()) {
                val m = margin(t)
                if (m > bestMargin) {
                    bestMargin = m
                    bestIndex = index
                }
            }
            val scanStart = if (bestIndex > 0) coarseList[bestIndex - 1] else start
            val scanEnd = if (bestIndex < coarseList.lastIndex) coarseList[bestIndex + 1] else end
            var bestT = coarseList[bestIndex]
            var t = scanStart
            while (t < scanEnd) {
                val m = margin(t)
                if (m > bestMargin) {
                    bestMargin = m
                    bestT = t
                }
                t += 60_000L
            }
            val endMargin = margin(scanEnd)
            if (endMargin > bestMargin) {
                bestT = scanEnd
            }
            return BestMoment(Instant.ofEpochMilli(bestT), centreAltAt(bestT))
        }
    }
}
