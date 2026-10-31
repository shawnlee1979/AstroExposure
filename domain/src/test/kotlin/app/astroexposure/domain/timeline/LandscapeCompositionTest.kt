package app.astroexposure.domain.timeline

import app.astroexposure.domain.camera.FullFrameFraming
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The landscape composition solver on synthetic series
 * (ui-design §7.9, 0.15.0): the alt/az paths are analytic, so the
 * verdicts are arithmetic facts, not ephemeris readings. The
 * golden-night cases against the real ephemeris live in the app
 * suite (LandscapeGoldenNightTest).
 */
class LandscapeCompositionTest {

    private val t0: Instant = Instant.parse("2026-10-27T22:00:00Z")

    private fun at(minutes: Long): Instant = t0.plus(Duration.ofMinutes(minutes))

    /**
     * A synthetic night: 5-minute samples over [minutes] minutes;
     * the centre's azimuth/altitude and the range points come from
     * the given functions of elapsed minutes.
     */
    private fun series(
        minutes: Long = 600,
        centre: (Long) -> LandscapeComposition.PointSample,
        points: (Long) -> List<LandscapeComposition.PointSample> = { emptyList() },
    ): List<LandscapeComposition.Sample> =
        (0..minutes step 5).map { m ->
            LandscapeComposition.Sample(at(m), centre(m), points(m))
        }

    private fun advice(low: Double, high: Double) =
        FullFrameFraming.Advice.Range(low, high, (low + high) / 2, 0.5)

    @Test
    fun aLowCrossingSubjectIsFeasibleWithACrossingBest() {
        // Centre sweeps east through the bearing at 0.1°/min,
        // peaking at 22°; two range points ride ±3° around it.
        val samples = series(centre = { m ->
            LandscapeComposition.PointSample(
                azimuthDeg = 150.0 + 0.1 * m,
                altitudeDeg = 22.0 - 0.00004 * (m - 300) * (m - 300),
            )
        }, points = { m ->
            val c = 150.0 + 0.1 * m
            val a = 22.0 - 0.00004 * (m - 300) * (m - 300)
            listOf(
                LandscapeComposition.PointSample(c - 3.0, a - 2.0),
                LandscapeComposition.PointSample(c + 3.0, a + 2.0),
            )
        })
        val result = LandscapeComposition.evaluate(
            samples, bearingDeg = 180.0, foregroundTopDeg = 0.0,
            advice = advice(24.0, 60.0), sizeMajorArcmin = 300.0,
            nightSpan = NightTimeline.Interval(at(0), at(600)),
        )
        val verdict = result.verdict
        assertTrue("was $verdict", verdict is LandscapeComposition.Verdict.Feasible)
        assertTrue(result.keyMoment.exactCrossing)
        assertEquals(300L, Duration.between(at(0), result.keyMoment.instant).toMinutes())
        val recommended =
            if ((verdict as LandscapeComposition.Verdict.Feasible).recommendedPortrait) {
                result.portrait
            } else {
                result.landscape
            }
        assertTrue(recommended.segments.isNotEmpty())
        assertTrue(recommended.recommendedFocalMm!! >= 24.0)
        assertTrue(recommended.recommendedFocalMm <= recommended.capFocalMm!!)
        val best = recommended.segments.first().best
        assertTrue(best is LandscapeComposition.BestMoment.Crossing)
    }

    @Test
    fun aBearingNeverApproachedIsTheNeverCrossesVerdict() {
        val samples = series(centre = { m ->
            LandscapeComposition.PointSample(azimuthDeg = 40.0 + 0.02 * m, altitudeDeg = 30.0)
        })
        val result = LandscapeComposition.evaluate(
            samples, bearingDeg = 180.0, foregroundTopDeg = 0.0,
            advice = advice(24.0, 60.0), sizeMajorArcmin = 300.0,
            nightSpan = NightTimeline.Interval(at(0), at(600)),
        )
        assertTrue(result.verdict is LandscapeComposition.Verdict.NeverCrossesBearing)
        assertTrue(result.keyMoment.residualDeg > LandscapeComposition.widestHalfWidthDeg)
    }

    @Test
    fun aVerticalSpanBeyondTheWidestFrameIsTheOverflowVerdict() {
        // Foreground top at -20° (the site looks down at it); at
        // the crossing the range runs from -10° to 80° — a 90° span
        // set too high for even the portrait 14 mm frame's upper
        // edge (-20 + 0.9 * 104.3° ≈ 73.9°), so neither orientation
        // has a cap.
        val samples = series(centre = { m ->
            LandscapeComposition.PointSample(
                azimuthDeg = 150.0 + 0.1 * m, altitudeDeg = 35.0,
            )
        }, points = { m ->
            val c = 150.0 + 0.1 * m
            listOf(
                LandscapeComposition.PointSample(c, -10.0),
                LandscapeComposition.PointSample(c, 80.0),
            )
        })
        val result = LandscapeComposition.evaluate(
            samples, bearingDeg = 180.0, foregroundTopDeg = -20.0,
            advice = advice(24.0, 60.0), sizeMajorArcmin = 300.0,
            nightSpan = NightTimeline.Interval(at(0), at(600)),
        )
        val verdict = result.verdict
        assertTrue("was $verdict", verdict is LandscapeComposition.Verdict.VerticalOverflow)
        verdict as LandscapeComposition.Verdict.VerticalOverflow
        assertEquals(80.0, verdict.subjectTopAltitudeDeg, 1.0)
        // The reported reach is the landscape 14 mm frame's upper
        // edge: -20 + 0.9 * 2*atan(12/14) ≈ 53.1°.
        assertEquals(53.1, verdict.reachableTopDeg, 0.5)
    }

    @Test
    fun capsBelowTheAdviceFloorAreTheShareConflictVerdict() {
        // A modest subject the frame holds only wide, against an
        // advice interval far above the caps.
        val samples = series(centre = { m ->
            LandscapeComposition.PointSample(
                azimuthDeg = 150.0 + 0.1 * m, altitudeDeg = 45.0,
            )
        }, points = { m ->
            val c = 150.0 + 0.1 * m
            listOf(
                LandscapeComposition.PointSample(c - 4.0, 30.0),
                LandscapeComposition.PointSample(c + 4.0, 62.0),
            )
        })
        val result = LandscapeComposition.evaluate(
            samples, bearingDeg = 180.0, foregroundTopDeg = 0.0,
            advice = advice(300.0, 600.0), sizeMajorArcmin = 60.0,
            nightSpan = NightTimeline.Interval(at(0), at(600)),
        )
        val verdict = result.verdict
        assertTrue("was $verdict", verdict is LandscapeComposition.Verdict.ShareConflict)
        val live = (verdict as LandscapeComposition.Verdict.ShareConflict).liveFact
        assertTrue(live != null)
        assertTrue(live!!.capFocalMm < 300.0)
        assertTrue(live.share < 1.0 / 3.0)
    }

    @Test
    fun windowsEntirelyOutsideTheNightSpanAreTheTwilightVerdict() {
        val samples = series(centre = { m ->
            LandscapeComposition.PointSample(
                azimuthDeg = 60.0 + 0.4 * m,
                altitudeDeg = 15.0,
            )
        })
        val result = LandscapeComposition.evaluate(
            samples, bearingDeg = 180.0, foregroundTopDeg = 0.0,
            advice = advice(24.0, 60.0), sizeMajorArcmin = 300.0,
            // The "night" covers only the first two hours; the
            // crossing (and its window) sits at minute 300.
            nightSpan = NightTimeline.Interval(at(0), at(120)),
        )
        assertTrue(
            "was ${result.verdict}",
            result.verdict is LandscapeComposition.Verdict.AllOutsideNight,
        )
        val recommended = result.landscape
        assertTrue(recommended.geometricSegments.isNotEmpty())
        assertTrue(recommended.segments.isEmpty())
    }

    @Test
    fun aBlinkWindowIsTheTooShortVerdict() {
        // The centre races through the bearing at 2°/min and the
        // advice forces a long focal: the full-in window at f* is a
        // few minutes at best.
        val samples = series(minutes = 120, centre = { m ->
            LandscapeComposition.PointSample(
                azimuthDeg = 60.0 + 2.0 * m, altitudeDeg = 25.0,
            )
        }, points = { m ->
            val c = 60.0 + 2.0 * m
            listOf(
                LandscapeComposition.PointSample(c - 1.5, 24.0),
                LandscapeComposition.PointSample(c + 1.5, 26.0),
            )
        })
        val result = LandscapeComposition.evaluate(
            samples, bearingDeg = 180.0, foregroundTopDeg = 22.0,
            advice = advice(180.0, 220.0), sizeMajorArcmin = 90.0,
            nightSpan = NightTimeline.Interval(at(0), at(120)),
        )
        assertTrue(
            "was ${result.verdict}",
            result.verdict is LandscapeComposition.Verdict.WindowTooShort,
        )
    }

    @Test
    fun placementFormulasAreTheStatedOnes() {
        // Centre = T + 0.4 * V; landscape V at 50mm = 2*atan(12/50).
        assertEquals(
            10.0 + 0.4 * 26.99,
            LandscapeComposition.frameCentreAltitudeDeg(10.0, 50.0, portrait = false),
            0.05,
        )
        // Portrait V at 50mm is the long side: 2*atan(18/50) ≈ 39.6°.
        assertEquals(
            39.6, LandscapeComposition.verticalFovDeg(50.0, portrait = true), 0.1,
        )
        assertEquals(52.13, LandscapeComposition.widestHalfWidthDeg, 0.05)
    }

    @Test
    fun aClosestApproachWithoutCrossingYieldsTheClosestBestForm() {
        // The centre passes 8° east of the bearing and never crosses.
        val samples = series(centre = { m ->
            LandscapeComposition.PointSample(
                azimuthDeg = 188.0 + 0.0001 * (m - 300) * (m - 300),
                altitudeDeg = 20.0,
            )
        })
        val result = LandscapeComposition.evaluate(
            samples, bearingDeg = 180.0, foregroundTopDeg = 10.0,
            advice = advice(24.0, 60.0), sizeMajorArcmin = 300.0,
            nightSpan = NightTimeline.Interval(at(0), at(600)),
        )
        assertTrue(result.verdict is LandscapeComposition.Verdict.Feasible)
        assertTrue(!result.keyMoment.exactCrossing)
        assertEquals(8.0, result.keyMoment.residualDeg, 1.5)
        val best = result.landscape.segments.first().best
        assertTrue("was $best", best is LandscapeComposition.BestMoment.ClosestInSegment)
    }

    @Test
    fun aCrossingCutByTheNightEdgeYieldsTheOutsideNightBestForm() {
        // Crossing at minute 300; the night ends at 250, mid-window.
        val samples = series(centre = { m ->
            LandscapeComposition.PointSample(
                azimuthDeg = 150.0 + 0.1 * m, altitudeDeg = 20.0,
            )
        })
        val result = LandscapeComposition.evaluate(
            samples, bearingDeg = 180.0, foregroundTopDeg = 10.0,
            advice = advice(24.0, 60.0), sizeMajorArcmin = 300.0,
            nightSpan = NightTimeline.Interval(at(0), at(250)),
        )
        assertTrue(result.verdict is LandscapeComposition.Verdict.Feasible)
        val best = result.landscape.segments.first().best
        assertTrue("was $best", best is LandscapeComposition.BestMoment.CrossingOutsideNight)
        best as LandscapeComposition.BestMoment.CrossingOutsideNight
        assertEquals(LandscapeComposition.SegmentEdge.END, best.edge)
        assertEquals(at(250), best.endpoint)
    }

    @Test
    fun twoPassesGiveTwoSegmentsNeverMerged() {
        // The azimuth oscillates: two distinct crossings, two spans.
        val samples = series(centre = { m ->
            LandscapeComposition.PointSample(
                azimuthDeg = 180.0 + 25.0 * kotlin.math.sin(m * 2.0 * Math.PI / 300.0),
                altitudeDeg = 20.0,
            )
        })
        val result = LandscapeComposition.evaluate(
            samples, bearingDeg = 180.0, foregroundTopDeg = 10.0,
            advice = advice(40.0, 45.0), sizeMajorArcmin = 300.0,
            nightSpan = NightTimeline.Interval(at(0), at(600)),
        )
        assertTrue(result.verdict is LandscapeComposition.Verdict.Feasible)
        assertTrue(
            "segments=${result.landscape.segments.size}",
            result.landscape.segments.size >= 2,
        )
    }

    @Test
    fun anOrientationWithoutACapStaysVisibleInTheFeasibleOutcome() {
        // Range top at 85° against a ground-level foreground top:
        // the landscape 14 mm frame's upper edge reaches only
        // 0.9 * 81.2° ≈ 73°, so landscape has no cap at all; the
        // portrait frame's 104° vertical field does hold it, at a
        // cap of about 17mm.
        val samples = series(centre = { m ->
            LandscapeComposition.PointSample(
                azimuthDeg = 150.0 + 0.1 * m, altitudeDeg = 42.0,
            )
        }, points = { m ->
            val c = 150.0 + 0.1 * m
            listOf(
                LandscapeComposition.PointSample(c, 0.0),
                LandscapeComposition.PointSample(c, 85.0),
            )
        })
        val result = LandscapeComposition.evaluate(
            samples, bearingDeg = 180.0, foregroundTopDeg = 0.0,
            advice = advice(14.0, 60.0), sizeMajorArcmin = 300.0,
            nightSpan = NightTimeline.Interval(at(0), at(600)),
        )
        val verdict = result.verdict
        assertTrue("was $verdict", verdict is LandscapeComposition.Verdict.Feasible)
        assertTrue((verdict as LandscapeComposition.Verdict.Feasible).recommendedPortrait)
        assertTrue(result.landscape.capFocalMm == null)
        assertTrue(result.portrait.recommendedFocalMm != null)
    }

    @Test
    fun theLowAltitudeHintFiresUnderTheRail() {
        val samples = series(centre = { m ->
            LandscapeComposition.PointSample(
                azimuthDeg = 150.0 + 0.1 * m, altitudeDeg = 3.0,
            )
        })
        val result = LandscapeComposition.evaluate(
            samples, bearingDeg = 180.0, foregroundTopDeg = 0.0,
            advice = advice(24.0, 60.0), sizeMajorArcmin = 300.0,
            nightSpan = NightTimeline.Interval(at(0), at(600)),
        )
        assertTrue(result.verdict is LandscapeComposition.Verdict.Feasible)
        assertTrue(result.lowAltitudeHint)
    }

    @Test
    fun segmentAltitudeRangeTracksTheCentre() {
        val samples = series(centre = { m ->
            LandscapeComposition.PointSample(
                azimuthDeg = 150.0 + 0.1 * m,
                altitudeDeg = 10.0 + 0.05 * m,
            )
        })
        val result = LandscapeComposition.evaluate(
            samples, bearingDeg = 180.0, foregroundTopDeg = 0.0,
            advice = advice(24.0, 35.0), sizeMajorArcmin = 300.0,
            nightSpan = NightTimeline.Interval(at(0), at(600)),
        )
        val segment = result.landscape.segments.first()
        assertTrue(segment.maxAltitudeDeg > segment.minAltitudeDeg)
        assertTrue(segment.minAltitudeDeg >= 10.0)
        assertTrue(segment.maxAltitudeDeg <= 10.0 + 0.05 * 600)
    }

    @Test
    fun aFlatWindowKeepsTheIntervalTopAsRecommended() {
        // A slow, centred subject: the window is the whole night at
        // every focal in the interval, so f* is the interval top
        // (the 90% rule never binds).
        val samples = series(centre = { m ->
            LandscapeComposition.PointSample(azimuthDeg = 180.0, altitudeDeg = 20.0)
        })
        val result = LandscapeComposition.evaluate(
            samples, bearingDeg = 180.0, foregroundTopDeg = 10.0,
            advice = advice(24.0, 35.0), sizeMajorArcmin = 300.0,
            nightSpan = NightTimeline.Interval(at(0), at(600)),
        )
        val outcome = result.landscape
        assertEquals(35.0, outcome.recommendedFocalMm!!, 0.01)
        assertEquals(600.0, outcome.totalWindowMinutes, 1.0)
    }

    @Test
    fun theRecommendedFocalAvoidsThePinchedCapWindow() {
        // The subject climbs steeply through the crossing: at the
        // cap the top edge pinches the window to a one-sided stub
        // ending at t̂, while a shorter focal keeps a long window
        // across t̂. f* must sit below the cap with a window that
        // spans the key moment.
        val samples = series(centre = { m ->
            LandscapeComposition.PointSample(
                azimuthDeg = 150.0 + 0.1 * m,
                altitudeDeg = 8.0 + 0.09 * m,
            )
        }, points = { m ->
            val c = 150.0 + 0.1 * m
            val a = 8.0 + 0.09 * m
            listOf(
                LandscapeComposition.PointSample(c - 5.0, a - 6.0),
                LandscapeComposition.PointSample(c + 5.0, a + 9.0),
            )
        })
        val result = LandscapeComposition.evaluate(
            samples, bearingDeg = 180.0, foregroundTopDeg = 20.0,
            advice = advice(24.0, 90.0), sizeMajorArcmin = 600.0,
            nightSpan = NightTimeline.Interval(at(0), at(600)),
        )
        val verdict = result.verdict
        assertTrue("was $verdict", verdict is LandscapeComposition.Verdict.Feasible)
        val recommended =
            if ((verdict as LandscapeComposition.Verdict.Feasible).recommendedPortrait) {
                result.portrait
            } else {
                result.landscape
            }
        val star = recommended.recommendedFocalMm!!
        val cap = recommended.capFocalMm!!
        assertTrue("f* $star should sit below cap $cap", star < cap)
        val segment = recommended.segments.maxByOrNull { it.durationMinutes }!!
        assertTrue(segment.start.isBefore(result.keyMoment.instant))
        assertTrue(segment.end.isAfter(result.keyMoment.instant))
        assertTrue(segment.durationMinutes >= 30.0)
    }
}
