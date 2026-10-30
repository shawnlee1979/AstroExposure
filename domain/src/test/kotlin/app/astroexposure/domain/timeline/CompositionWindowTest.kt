package app.astroexposure.domain.timeline

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Composition window mathematics (ui-design §7.9): synthetic series
 * whose crossings are computable by hand exactly, covering every
 * branch of the definition — band entry by azimuth and by altitude,
 * the altitude ceiling splitting a pass, wrap at 0°, the night cut
 * (including a crossing the cut removes), twilight-only spans,
 * both altitude diagnoses, never-in-band, a segment without a zero
 * crossing, a double sweep, and the tolerance tiers.
 */
class CompositionWindowTest {

    private val t0: Instant = Instant.ofEpochSecond(1_700_000_000)

    private fun at(step: Int, extraSeconds: Long = 0): Instant =
        t0.plusSeconds(step * 300L + extraSeconds)

    private fun series(
        azimuths: List<Double>,
        altitudes: List<Double>,
    ): List<CompositionWindow.Sample> {
        require(azimuths.size == altitudes.size)
        return azimuths.indices.map { i ->
            CompositionWindow.Sample(at(i), azimuths[i], altitudes[i])
        }
    }

    private fun flatAlt(count: Int, altitude: Double): List<Double> =
        List(count) { altitude }

    // --- Signed azimuth difference ------------------------------------------

    @Test
    fun signedDiffWrapsAcrossNorth() {
        assertEquals(7.0, CompositionWindow.signedAzimuthDiffDeg(2.0, 355.0), 1e-9)
        assertEquals(-7.0, CompositionWindow.signedAzimuthDiffDeg(355.0, 2.0), 1e-9)
        assertEquals(20.0, CompositionWindow.signedAzimuthDiffDeg(10.0, 350.0), 1e-9)
        assertEquals(-20.0, CompositionWindow.signedAzimuthDiffDeg(350.0, 10.0), 1e-9)
    }

    @Test
    fun signedDiffAntipodeAndNormalisation() {
        assertEquals(180.0, CompositionWindow.signedAzimuthDiffDeg(180.0, 0.0), 1e-9)
        assertEquals(180.0, CompositionWindow.signedAzimuthDiffDeg(0.0, 180.0), 1e-9)
        assertEquals(0.0, CompositionWindow.signedAzimuthDiffDeg(370.0, 10.0), 1e-9)
        assertEquals(0.0, CompositionWindow.signedAzimuthDiffDeg(-10.0, 350.0), 1e-9)
        assertEquals(0.0, CompositionWindow.signedAzimuthDiffDeg(90.0, 90.0), 1e-9)
    }

    // --- Basic window ---------------------------------------------------------

    @Test
    fun azimuthBoundWindowWithFractionalEdges() {
        // Azimuth ramps +4°/step through the ±10° band around 210°;
        // band edges are crossed halfway into the boundary steps.
        val samples = series(
            azimuths = listOf(194.0, 198.0, 202.0, 206.0, 210.0, 214.0, 218.0, 222.0, 226.0),
            altitudes = flatAlt(9, 30.0),
        )
        val result = CompositionWindow.windows(samples, 210.0, 10.0, null)
        assertNull(result.diagnosis)
        assertEquals(1, result.segments.size)
        val segment = result.segments.single()
        assertEquals(at(1, 150), segment.start)
        assertEquals(at(6, 150), segment.end)
        val best = segment.best as CompositionWindow.BestMoment.Crossing
        assertEquals(at(4), best.instant)
        assertEquals(30.0, best.altitudeDeg, 1e-9)
        assertEquals(30.0, segment.minAltitudeDeg, 1e-9)
        assertEquals(30.0, segment.maxAltitudeDeg, 1e-9)
    }

    @Test
    fun altitudeBoundWindowSplitByTheCeiling() {
        // Azimuth sits on the bearing all night; the altitude ramp
        // enters at 5°, exits at 55°, and after culmination re-enters
        // — two segments, each bounded by interpolated altitude
        // crossings, each spanning the full band (5..55).
        val samples = series(
            azimuths = flatAlt(13, 100.0),
            altitudes = listOf(
                0.0, 2.0, 8.0, 20.0, 40.0, 52.0, 58.0, 58.0, 40.0, 20.0, 8.0, 2.0, 0.0,
            ),
        )
        val result = CompositionWindow.windows(samples, 100.0, 10.0, null)
        assertEquals(2, result.segments.size)
        val first = result.segments[0]
        assertEquals(at(1, 150), first.start)
        assertEquals(at(5, 150), first.end)
        assertEquals(5.0, first.minAltitudeDeg, 1e-9)
        assertEquals(55.0, first.maxAltitudeDeg, 1e-9)
        val second = result.segments[1]
        assertEquals(at(7, 50), second.start)
        assertEquals(at(10, 150), second.end)
        assertEquals(5.0, second.minAltitudeDeg, 1e-9)
        assertEquals(55.0, second.maxAltitudeDeg, 1e-9)
    }

    @Test
    fun windowAcrossTheNorthWrap() {
        // Bearing 355°, azimuth sweeps 340° → 20° through north.
        val samples = series(
            azimuths = listOf(340.0, 348.0, 356.0, 4.0, 12.0, 20.0),
            altitudes = flatAlt(6, 30.0),
        )
        val result = CompositionWindow.windows(samples, 355.0, 10.0, null)
        assertEquals(1, result.segments.size)
        val segment = result.segments.single()
        assertEquals(t0.plusMillis(187_500), segment.start)
        assertEquals(at(3).plusMillis(37_500), segment.end)
        val best = segment.best as CompositionWindow.BestMoment.Crossing
        assertEquals(at(1).plusMillis(262_500), best.instant)
        assertEquals(30.0, best.altitudeDeg, 1e-9)
    }

    @Test
    fun altitudeBoundsAreInclusive() {
        // Samples exactly at 5° and 55° are inside the band.
        val samples = series(
            azimuths = flatAlt(3, 100.0),
            altitudes = listOf(5.0, 55.0, 5.0),
        )
        val result = CompositionWindow.windows(samples, 100.0, 10.0, null)
        assertEquals(1, result.segments.size)
        assertEquals(at(0), result.segments.single().start)
        assertEquals(at(2), result.segments.single().end)
        assertEquals(5.0, result.segments.single().minAltitudeDeg, 1e-9)
        assertEquals(55.0, result.segments.single().maxAltitudeDeg, 1e-9)
    }

    // --- Night cut --------------------------------------------------------------

    private fun clipFixture(): List<CompositionWindow.Sample> = series(
        azimuths = listOf(196.0, 204.0, 212.0, 220.0, 228.0),
        altitudes = flatAlt(5, 30.0),
    )
    // Uncut verdict of the fixture: segment at(0)+150s .. at(3),
    // crossing at at(1)+225s (azimuth 204° → 212° through 210°).

    @Test
    fun nightCutBeforeCrossingReportsItOutside() {
        val night = NightTimeline.Interval(at(1, 270), at(12))
        val result = CompositionWindow.windows(clipFixture(), 210.0, 10.0, night)
        assertNull(result.diagnosis)
        assertEquals(1, result.segments.size)
        val segment = result.segments.single()
        assertEquals(at(1, 270), segment.start)
        assertEquals(at(3), segment.end)
        val best = segment.best as CompositionWindow.BestMoment.CrossingOutsideNight
        assertEquals(CompositionWindow.SegmentEdge.START, best.edge)
        assertEquals(at(1, 270), best.endpoint)
        assertEquals(at(1, 225), best.crossingInstant)
        assertEquals(30.0, best.crossingAltitudeDeg, 1e-9)
    }

    @Test
    fun nightCutAfterCrossingReportsItOutside() {
        val night = NightTimeline.Interval(at(0), at(1, 180))
        val result = CompositionWindow.windows(clipFixture(), 210.0, 10.0, night)
        assertEquals(1, result.segments.size)
        val segment = result.segments.single()
        assertEquals(at(0, 150), segment.start)
        assertEquals(at(1, 180), segment.end)
        val best = segment.best as CompositionWindow.BestMoment.CrossingOutsideNight
        assertEquals(CompositionWindow.SegmentEdge.END, best.edge)
        assertEquals(at(1, 180), best.endpoint)
        assertEquals(at(1, 225), best.crossingInstant)
    }

    @Test
    fun spanEntirelyInTwilightIsTheDiagnosis() {
        val night = NightTimeline.Interval(at(6), at(12))
        val result = CompositionWindow.windows(clipFixture(), 210.0, 10.0, night)
        assertTrue(result.segments.isEmpty())
        val diagnosis = result.diagnosis as CompositionWindow.Diagnosis.TwilightOnly
        assertEquals(1, diagnosis.segments.size)
        val segment = diagnosis.segments.single()
        // The twilight span keeps its uncut bounds and crossing.
        assertEquals(at(0, 150), segment.start)
        assertEquals(at(3), segment.end)
        val best = segment.best as CompositionWindow.BestMoment.Crossing
        assertEquals(at(1, 225), best.instant)
    }

    // --- Empty-state diagnoses --------------------------------------------------

    @Test
    fun azimuthBandMetBelowTheAltitudeFloor() {
        val samples = series(
            azimuths = listOf(200.0, 205.0, 210.0, 215.0, 220.0),
            altitudes = listOf(1.0, 2.0, 3.0, 2.0, 1.0),
        )
        val result = CompositionWindow.windows(samples, 210.0, 10.0, null)
        assertTrue(result.segments.isEmpty())
        val diagnosis = result.diagnosis as CompositionWindow.Diagnosis.AltitudeOutsideBand
        assertEquals(1.0, diagnosis.azimuthBandMinAltitudeDeg, 1e-9)
        assertEquals(3.0, diagnosis.azimuthBandMaxAltitudeDeg, 1e-9)
    }

    @Test
    fun azimuthBandMetAboveTheAltitudeCeiling() {
        val samples = series(
            azimuths = listOf(200.0, 205.0, 210.0, 215.0, 220.0),
            altitudes = listOf(60.0, 70.0, 80.0, 70.0, 60.0),
        )
        val result = CompositionWindow.windows(samples, 210.0, 10.0, null)
        val diagnosis = result.diagnosis as CompositionWindow.Diagnosis.AltitudeOutsideBand
        assertEquals(60.0, diagnosis.azimuthBandMinAltitudeDeg, 1e-9)
        assertEquals(80.0, diagnosis.azimuthBandMaxAltitudeDeg, 1e-9)
    }

    @Test
    fun neverInAzimuthBandReportsTheClosestApproach() {
        // Azimuth parked 110° away all night; closest is the whole
        // series — the FIRST sample wins the tie.
        val samples = series(
            azimuths = flatAlt(3, 100.0),
            altitudes = listOf(12.0, 30.0, 50.0),
        )
        val result = CompositionWindow.windows(samples, 210.0, 10.0, null)
        assertTrue(result.segments.isEmpty())
        val diagnosis = result.diagnosis as CompositionWindow.Diagnosis.NeverInAzimuthBand
        assertEquals(at(0), diagnosis.closestInstant)
        assertEquals(110.0, diagnosis.closestDiffDeg, 1e-9)
        assertEquals(12.0, diagnosis.closestAltitudeDeg, 1e-9)
    }

    // --- Best-moment variants -----------------------------------------------------

    @Test
    fun segmentWithoutACrossingTakesItsClosestPoint() {
        // The azimuth passes 3° from the bearing at its closest and
        // never crosses: the best is that sample, residual stated.
        val samples = series(
            azimuths = listOf(204.0, 207.0, 205.0),
            altitudes = flatAlt(3, 30.0),
        )
        val result = CompositionWindow.windows(samples, 210.0, 10.0, null)
        assertEquals(1, result.segments.size)
        val best = result.segments.single().best
            as CompositionWindow.BestMoment.ClosestInSegment
        assertEquals(at(1), best.instant)
        assertEquals(3.0, best.residualDiffDeg, 1e-9)
        assertEquals(30.0, best.altitudeDeg, 1e-9)
    }

    @Test
    fun doubleSweepYieldsTwoSegmentsWithTwoCrossings() {
        // Azimuth sweeps up through the ±5° band and back down it
        // (the high-declination pattern): two disjoint segments,
        // each with its own exact crossing — never merged.
        val samples = series(
            azimuths = listOf(200.0, 205.0, 210.0, 215.0, 220.0, 215.0, 210.0, 205.0, 200.0),
            altitudes = flatAlt(9, 30.0),
        )
        val result = CompositionWindow.windows(samples, 210.0, 5.0, null)
        assertEquals(2, result.segments.size)
        val first = result.segments[0]
        assertEquals(at(1), first.start)
        assertEquals(at(3), first.end)
        assertEquals(
            at(2),
            (first.best as CompositionWindow.BestMoment.Crossing).instant,
        )
        val second = result.segments[1]
        assertEquals(at(5), second.start)
        assertEquals(at(7), second.end)
        assertEquals(
            at(6),
            (second.best as CompositionWindow.BestMoment.Crossing).instant,
        )
    }

    @Test
    fun toleranceTierNarrowsTheWindow() {
        val samples = series(
            azimuths = listOf(194.0, 198.0, 202.0, 206.0, 210.0, 214.0, 218.0, 222.0, 226.0),
            altitudes = flatAlt(9, 30.0),
        )
        val narrow = CompositionWindow.windows(samples, 210.0, 5.0, null)
        assertEquals(1, narrow.segments.size)
        assertEquals(at(2, 225), narrow.segments.single().start)
        assertEquals(at(5, 75), narrow.segments.single().end)
        val wide = CompositionWindow.windows(samples, 210.0, 10.0, null)
        assertTrue(wide.segments.single().start.isBefore(narrow.segments.single().start))
        assertTrue(wide.segments.single().end.isAfter(narrow.segments.single().end))
    }
}
