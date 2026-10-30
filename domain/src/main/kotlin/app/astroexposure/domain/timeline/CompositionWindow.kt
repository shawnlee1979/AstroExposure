package app.astroexposure.domain.timeline

import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Pure mathematics of the landscape composition window (ui-design
 * §7.9): given a subject's sampled azimuth/altitude series over the
 * night axis, a foreground bearing and a tolerance, find the spans in
 * which the subject can be composed against that foreground — its
 * azimuth within the tolerance of the bearing (shortest arc,
 * wrap-safe at 0°), its altitude inside the fixed shootable band,
 * cut to the night span the caller supplies (the astronomical night,
 * or the caller's sunset→sunrise fallback; null stands uncut, the
 * same span discipline as the detail dialog's windows, §7.5).
 *
 * Everything is a pure function of the samples (rules D.1/D.2): the
 * caller samples the ephemeris port on the shared 5-minute grid and
 * supplies the results. Both series are treated piecewise-linearly
 * between samples — segment boundaries sit at the interpolated
 * constraint crossings (the azimuth band edge, the altitude band
 * edges), and a segment's best moment is the interpolated zero
 * crossing of the azimuth difference, the same interpolation the
 * track band's boundary crossings use (§7.4).
 *
 * Band calibres are fixed definitions, not preferences (§7.9):
 * [MIN_ALTITUDE_DEG] — below ~5° extinction climbs steeply and
 * ordinary foreground obstruction (tree lines, roofs, ridges)
 * usually eats the frame's lower edge, so 5° is the honest floor
 * for "the subject is in the frame and recognisable"; the altitude
 * is the subject CENTRE's, an extended entry's body may reach lower.
 * [MAX_ALTITUDE_DEG] — above ~55° even a 14 mm-class wide angle
 * (vertical field ≈72°) can barely hold foreground and subject in
 * one frame, and a tripod aimed past 55° is awkward to work; the
 * cap is what separates a composition window from a high culmination.
 * [TOLERANCE_TIERS_DEG] — field widths comparable to common wide
 * lenses' horizontal fields (24 mm ≈ 74°, 50 mm ≈ 40°): inside the
 * ±10° default the subject can be framed by shifting position or
 * cropping, without waiting for dead centre.
 */
object CompositionWindow {

    /** Floor of the fixed composition altitude band, degrees (see class doc). */
    const val MIN_ALTITUDE_DEG = 5.0

    /** Ceiling of the fixed composition altitude band, degrees (see class doc). */
    const val MAX_ALTITUDE_DEG = 55.0

    /** The user-selectable azimuth tolerance tiers, degrees (§7.9). */
    val TOLERANCE_TIERS_DEG = listOf(5.0, 10.0, 15.0)

    /** The tolerance a fresh composition section starts with, degrees. */
    const val DEFAULT_TOLERANCE_DEG = 10.0

    /** One sampled point of the subject's pass: azimuth and altitude in degrees. */
    data class Sample(
        val instant: Instant,
        val azimuthDeg: Double,
        val altitudeDeg: Double,
    )

    /** Which end of a segment a clipped-away crossing belongs to. */
    enum class SegmentEdge { START, END }

    /** A segment's best moment (see [windows] for the precedence). */
    sealed interface BestMoment {
        /** The azimuth difference crosses zero inside the segment. */
        data class Crossing(val instant: Instant, val altitudeDeg: Double) : BestMoment

        /**
         * No zero crossing inside the segment (the azimuth passes
         * within tolerance but never through the bearing): the
         * closest approach point in the segment, with its residual.
         */
        data class ClosestInSegment(
            val instant: Instant,
            val altitudeDeg: Double,
            val residualDiffDeg: Double,
        ) : BestMoment

        /**
         * The zero crossing exists but the night cut removed it from
         * this segment: [endpoint] (the segment's nearer end) stands
         * in as the in-window best, and the true crossing is named
         * alongside, never dressed up as an in-window moment (§7.9).
         */
        data class CrossingOutsideNight(
            val edge: SegmentEdge,
            val endpoint: Instant,
            val crossingInstant: Instant,
            val crossingAltitudeDeg: Double,
        ) : BestMoment
    }

    /** One composition span: its bounds, its best moment and its altitude range. */
    data class Segment(
        val start: Instant,
        val end: Instant,
        val best: BestMoment,
        val minAltitudeDeg: Double,
        val maxAltitudeDeg: Double,
    )

    /**
     * Why a night has no composition segment — every variant is a
     * factual statement computed from the samples, never a blank
     * (design §5: honest states). Exactly one is present when
     * [Result.segments] is empty, in this precedence: spans exist
     * but all fall outside the night span; the azimuth band is
     * entered but the altitude never enters the band during it; the
     * azimuth band is never entered at all.
     */
    sealed interface Diagnosis {
        /** In-band spans exist, but every one lies outside the night span. */
        data class TwilightOnly(val segments: List<Segment>) : Diagnosis

        /**
         * The azimuth band is entered, but at those times the
         * altitude stays outside [MIN_ALTITUDE_DEG]..[MAX_ALTITUDE_DEG];
         * the range says which bound it fails (wholly below the
         * floor, wholly above the ceiling, or straddling in samples
         * too coarse to catch the transit).
         */
        data class AltitudeOutsideBand(
            val azimuthBandMinAltitudeDeg: Double,
            val azimuthBandMaxAltitudeDeg: Double,
        ) : Diagnosis

        /** The azimuth never comes within tolerance of the bearing tonight. */
        data class NeverInAzimuthBand(
            val closestInstant: Instant,
            val closestDiffDeg: Double,
            val closestAltitudeDeg: Double,
        ) : Diagnosis
    }

    /** The night's composition verdict: the spans, or why there are none. */
    data class Result(
        val segments: List<Segment>,
        val diagnosis: Diagnosis?,
    )

    /**
     * Shortest signed arc from [bearingDeg] to [azimuthDeg], in
     * (−180, 180]: positive when the azimuth is east (clockwise from
     * north) of the bearing. Inputs may be any real degrees; both
     * are normalised first, so a bearing of 355° and an azimuth of
     * 2° differ by +7°, never by −353°.
     */
    fun signedAzimuthDiffDeg(azimuthDeg: Double, bearingDeg: Double): Double {
        val az = ((azimuthDeg % 360.0) + 360.0) % 360.0
        val bearing = ((bearingDeg % 360.0) + 360.0) % 360.0
        var diff = az - bearing
        if (diff <= -180.0) diff += 360.0
        if (diff > 180.0) diff -= 360.0
        return diff
    }

    /**
     * The composition windows of one night. [samples] are the
     * subject's azimuth/altitude series over the night axis (at
     * least two, in time order); [nightSpan] is the span windows are
     * cut to, or null to leave them uncut. All in-band spans are
     * returned, never merged and never reduced to one (§7.9): a
     * high-declination subject can sweep the same bearing band twice.
     */
    fun windows(
        samples: List<Sample>,
        bearingDeg: Double,
        toleranceDeg: Double,
        nightSpan: NightTimeline.Interval?,
    ): Result {
        require(samples.size >= 2) { "composition needs a sampled series, was ${samples.size}" }
        require(toleranceDeg > 0.0) { "tolerance must be positive, was $toleranceDeg" }
        val n = samples.size
        val times = LongArray(n) { samples[it].instant.toEpochMilli() }
        val diffs = DoubleArray(n) { signedAzimuthDiffDeg(samples[it].azimuthDeg, bearingDeg) }
        val alts = DoubleArray(n) { samples[it].altitudeDeg }
        fun inAzimuth(i: Int): Boolean = abs(diffs[i]) <= toleranceDeg
        fun inAltitude(i: Int): Boolean =
            alts[i] >= MIN_ALTITUDE_DEG && alts[i] <= MAX_ALTITUDE_DEG
        fun inFull(i: Int): Boolean = inAzimuth(i) && inAltitude(i)

        fun interpAt(values: DoubleArray, t: Long): Double {
            if (t <= times.first()) return values.first()
            if (t >= times.last()) return values.last()
            var i = 0
            while (times[i + 1] < t) i++
            val span = (times[i + 1] - times[i]).toDouble()
            if (span <= 0.0) return values[i]
            val f = (t - times[i]).toDouble() / span
            return values[i] + f * (values[i + 1] - values[i])
        }
        fun crossingMillis(i: Int, values: DoubleArray, level: Double): Long {
            // Fraction of the step i→i+1 at which the linear series
            // reaches [level]; callers guarantee it is crossed there.
            val f = (level - values[i]) / (values[i + 1] - values[i])
            return times[i] + (f * (times[i + 1] - times[i])).roundToLong()
        }

        val rawSegments = mutableListOf<Segment>()
        val indexList = samples.indices.toList()
        for (run in NightTimeline.contiguousRuns(indexList) { inFull(it) }) {
            val a = run.first
            val b = run.last
            // Segment start: the axis edge when the run opens the
            // series, else the LATEST constraint crossing in the
            // step into the run (every violated constraint must have
            // been entered, so the binding one enters last).
            val start: Long = if (a == 0) {
                times[0]
            } else {
                var f = 0.0
                if (!inAzimuth(a - 1)) {
                    val level = if (diffs[a - 1] > 0) toleranceDeg else -toleranceDeg
                    f = maxOf(f, (level - diffs[a - 1]) / (diffs[a] - diffs[a - 1]))
                }
                if (alts[a - 1] < MIN_ALTITUDE_DEG) {
                    f = maxOf(f, (MIN_ALTITUDE_DEG - alts[a - 1]) / (alts[a] - alts[a - 1]))
                }
                if (alts[a - 1] > MAX_ALTITUDE_DEG) {
                    f = maxOf(f, (MAX_ALTITUDE_DEG - alts[a - 1]) / (alts[a] - alts[a - 1]))
                }
                times[a - 1] + (f * (times[a] - times[a - 1])).roundToLong()
            }
            // Segment end: symmetric — the EARLIEST constraint
            // crossing in the step out of the run.
            val end: Long = if (b == n - 1) {
                times[n - 1]
            } else {
                var f = 1.0
                if (!inAzimuth(b + 1)) {
                    val level = if (diffs[b + 1] > 0) toleranceDeg else -toleranceDeg
                    f = minOf(f, (level - diffs[b]) / (diffs[b + 1] - diffs[b]))
                }
                if (alts[b + 1] < MIN_ALTITUDE_DEG) {
                    f = minOf(f, (MIN_ALTITUDE_DEG - alts[b]) / (alts[b + 1] - alts[b]))
                }
                if (alts[b + 1] > MAX_ALTITUDE_DEG) {
                    f = minOf(f, (MAX_ALTITUDE_DEG - alts[b]) / (alts[b + 1] - alts[b]))
                }
                times[b] + (f * (times[b + 1] - times[b])).roundToLong()
            }
            if (end <= start) continue
            // Zero crossings of the azimuth difference over the
            // steps the segment spans, boundary steps included (a
            // crossing can share the step that the altitude band
            // admitted the segment on).
            val crossings = mutableListOf<Long>()
            val firstStep = maxOf(0, a - 1)
            val lastStep = minOf(b, n - 2)
            for (j in firstStep..lastStep) {
                if (diffs[j] == 0.0) {
                    crossings += times[j]
                } else if (diffs[j] * diffs[j + 1] < 0.0) {
                    crossings += crossingMillis(j, diffs, 0.0)
                }
            }
            val crossing = crossings.firstOrNull { it in start..end }
            val best: BestMoment = if (crossing != null) {
                BestMoment.Crossing(
                    Instant.ofEpochMilli(crossing), interpAt(alts, crossing),
                )
            } else {
                closestBest(times, diffs, alts, a, b, start, end, ::interpAt)
            }
            val altCandidates = (a..b).map { alts[it] } +
                listOf(interpAt(alts, start), interpAt(alts, end))
            rawSegments += Segment(
                start = Instant.ofEpochMilli(start),
                end = Instant.ofEpochMilli(end),
                best = best,
                minAltitudeDeg = altCandidates.min(),
                maxAltitudeDeg = altCandidates.max(),
            )
        }

        // Cut to the night span. A raw segment the cut empties is a
        // twilight span, kept whole for the diagnosis.
        val segments = mutableListOf<Segment>()
        val twilight = mutableListOf<Segment>()
        for (raw in rawSegments) {
            if (nightSpan == null) {
                segments += raw
                continue
            }
            val cutStart = maxOf(raw.start, nightSpan.start)
            val cutEnd = minOf(raw.end, nightSpan.end)
            if (!cutEnd.isAfter(cutStart)) {
                twilight += raw
                continue
            }
            segments += if (cutStart == raw.start && cutEnd == raw.end) {
                raw
            } else {
                cutSegment(raw, cutStart, cutEnd, times, diffs, alts, ::interpAt)
            }
        }
        if (segments.isNotEmpty()) return Result(segments, diagnosis = null)
        if (twilight.isNotEmpty()) {
            return Result(emptyList(), Diagnosis.TwilightOnly(twilight))
        }
        val azimuthBandIndices = samples.indices.filter { inAzimuth(it) }
        if (azimuthBandIndices.isNotEmpty()) {
            return Result(
                emptyList(),
                Diagnosis.AltitudeOutsideBand(
                    azimuthBandMinAltitudeDeg = azimuthBandIndices.minOf { alts[it] },
                    azimuthBandMaxAltitudeDeg = azimuthBandIndices.maxOf { alts[it] },
                ),
            )
        }
        val closest = samples.indices.minByOrNull { abs(diffs[it]) } ?: 0
        return Result(
            emptyList(),
            Diagnosis.NeverInAzimuthBand(
                closestInstant = samples[closest].instant,
                closestDiffDeg = abs(diffs[closest]),
                closestAltitudeDeg = alts[closest],
            ),
        )
    }

    /** The closest-approach best of the span [start, end] over run samples [a, b]. */
    private fun closestBest(
        times: LongArray,
        diffs: DoubleArray,
        alts: DoubleArray,
        a: Int,
        b: Int,
        start: Long,
        end: Long,
        interpAt: (DoubleArray, Long) -> Double,
    ): BestMoment.ClosestInSegment {
        var bestT = start
        var bestAbs = abs(interpAt(diffs, start))
        for (i in a..b) {
            val v = abs(diffs[i])
            if (v < bestAbs) {
                bestAbs = v
                bestT = times[i]
            }
        }
        val endAbs = abs(interpAt(diffs, end))
        if (endAbs < bestAbs) {
            bestAbs = endAbs
            bestT = end
        }
        return BestMoment.ClosestInSegment(
            Instant.ofEpochMilli(bestT), interpAt(alts, bestT), bestAbs,
        )
    }

    /**
     * [raw] cut to [cutStart, cutEnd]: the best moment is re-derived
     * against the cut — a crossing the cut removed is reported as
     * outside with the nearer endpoint standing in, and a closest
     * point the cut removed is recomputed inside what remains.
     */
    private fun cutSegment(
        raw: Segment,
        cutStart: Instant,
        cutEnd: Instant,
        times: LongArray,
        diffs: DoubleArray,
        alts: DoubleArray,
        interpAt: (DoubleArray, Long) -> Double,
    ): Segment {
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
            is BestMoment.ClosestInSegment -> {
                // Recompute inside the cut from the samples it keeps
                // plus its own endpoints.
                var bestT = startMs
                var bestAbs = abs(interpAt(diffs, startMs))
                for (i in times.indices) {
                    if (times[i] in startMs..endMs) {
                        val v = abs(diffs[i])
                        if (v < bestAbs) {
                            bestAbs = v
                            bestT = times[i]
                        }
                    }
                }
                val endAbs = abs(interpAt(diffs, endMs))
                if (endAbs < bestAbs) {
                    bestAbs = endAbs
                    bestT = endMs
                }
                BestMoment.ClosestInSegment(
                    Instant.ofEpochMilli(bestT), interpAt(alts, bestT), bestAbs,
                )
            }
            is BestMoment.CrossingOutsideNight -> rawBest
        }
        val altCandidates = mutableListOf(interpAt(alts, startMs), interpAt(alts, endMs))
        for (i in times.indices) {
            if (times[i] in startMs..endMs) altCandidates += alts[i]
        }
        return Segment(
            start = cutStart,
            end = cutEnd,
            best = best,
            minAltitudeDeg = altCandidates.min(),
            maxAltitudeDeg = altCandidates.max(),
        )
    }
}
