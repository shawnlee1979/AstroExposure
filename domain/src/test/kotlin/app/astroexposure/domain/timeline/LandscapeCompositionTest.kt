package app.astroexposure.domain.timeline

import app.astroexposure.domain.camera.FullFrameFraming
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The landscape composition solver, second revision, on synthetic
 * series (ui-design §7.9, 0.16.0): the alt/az paths are analytic,
 * so the verdicts are arithmetic facts, not ephemeris readings.
 * Points on the centre's own azimuth are used throughout, for
 * which the tangent-plane height of a point is exactly
 * tan(altitude − tangent altitude), so the closed-form spans are
 * computable by hand.
 */
class LandscapeCompositionTest {

    private val t0: Instant = Instant.parse("2026-10-27T22:00:00Z")

    private fun at(minutes: Long): Instant = t0.plus(Duration.ofMinutes(minutes))

    private fun minutesOf(instant: Instant): Long = Duration.between(t0, instant).toMinutes()

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

    private fun runEval(
        samples: List<LandscapeComposition.Sample>,
        foregroundTopDeg: Double,
        sizeMajorArcmin: Double? = null,
        nightSpan: NightTimeline.Interval? = NightTimeline.Interval(at(0), at(600)),
    ): LandscapeComposition.Result = LandscapeComposition.evaluate(
        samples, foregroundTopDeg, sizeMajorArcmin, nightSpan,
    )

    private fun point(azimuthDeg: Double, altitudeDeg: Double) =
        LandscapeComposition.PointSample(azimuthDeg, altitudeDeg)

    @Test
    fun theClosedFormDominatesForASimpleSubject() {
        // A centre-only subject parked at 40° over a 10° foreground
        // top: the span is tan(40° − 10°) = 0.577, the frame height
        // 0.641, so the closed forms are 24/0.641 and 36/0.641 and
        // the exact check at the anchor has room to spare — the
        // derived focals are the closed forms themselves.
        val samples = series(centre = { point(180.0, 40.0) })
        val result = runEval(samples, foregroundTopDeg = 10.0)
        assertTrue("was ${result.verdict}", result.verdict is LandscapeComposition.Verdict.Feasible)
        val land = result.landscape
        val port = result.portrait
        assertEquals(37.4, land.closedFormFocalMm, 1.0)
        assertEquals(56.1, port.closedFormFocalMm, 1.5)
        assertEquals(land.derivedFocalMm!!, land.closedFormFocalMm, 1.0)
        assertEquals(port.derivedFocalMm!!, port.closedFormFocalMm, 1.5)
        // The portrait closed form is the landscape one times the
        // sensor-side ratio 36/24.
        assertEquals(1.5, port.closedFormFocalMm / land.closedFormFocalMm, 0.02)
        assertEquals(600.0, land.totalWindowMinutes, 1.0)
    }

    @Test
    fun theExactCheckOnlyShortensForAWideSubject() {
        // Range points ride ±50° of azimuth at the centre's
        // altitude: the vertical span still fixes the closed form,
        // but the points overflow the candidate's horizontal field,
        // so the exact check bisects the derived focal down.
        val samples = series(
            centre = { point(180.0, 40.0) },
            points = { listOf(point(130.0, 40.0), point(230.0, 40.0)) },
        )
        val result = runEval(samples, foregroundTopDeg = 10.0)
        val land = result.landscape
        assertEquals(27.0, land.closedFormFocalMm, 1.5)
        val derived = land.derivedFocalMm
        assertTrue("derived was $derived", derived != null)
        assertTrue("derived $derived not below closed ${land.closedFormFocalMm}",
            derived!! < land.closedFormFocalMm - 0.5)
        assertTrue(derived >= LandscapeComposition.MIN_FOCAL_MM)
    }

    @Test
    fun anOrientationUnderTheWidestFocalIsSkippedForTheOther() {
        // Centre-only at 62.6° over a level foreground: the span
        // tan(62.6°) puts the landscape closed form under 14 mm
        // while the portrait one (1.5x) stays in the domain.
        val samples = series(centre = { point(180.0, 62.6) })
        val result = runEval(samples, foregroundTopDeg = 0.0)
        assertTrue(result.landscape.closedFormFocalMm < LandscapeComposition.MIN_FOCAL_MM)
        assertNull(result.landscape.derivedFocalMm)
        assertTrue(result.portrait.derivedFocalMm != null)
        val verdict = result.verdict
        assertTrue("was $verdict", verdict is LandscapeComposition.Verdict.Feasible)
        assertTrue((verdict as LandscapeComposition.Verdict.Feasible).recommendedPortrait)
    }

    @Test
    fun aSubjectThatNeverClearsTheForegroundTopIsTheFirstVerdict() {
        // The range bottom rides at 20° all night against a 25°
        // foreground top: verdict 1, with the greatest bottom and
        // its 5° shortfall reported, and no derived focal anywhere.
        val samples = series(
            centre = { point(180.0, 30.0) },
            points = { listOf(point(180.0, 20.0), point(180.0, 40.0)) },
        )
        val result = runEval(samples, foregroundTopDeg = 25.0)
        val verdict = result.verdict
        assertTrue("was $verdict", verdict is LandscapeComposition.Verdict.NeverAboveForegroundTop)
        verdict as LandscapeComposition.Verdict.NeverAboveForegroundTop
        assertEquals(20.0, verdict.maxBottomAltitudeDeg, 0.01)
        assertEquals(at(0), verdict.atInstant)
        assertEquals(25.0, verdict.foregroundTopDeg, 0.01)
        assertEquals(5.0, verdict.foregroundTopDeg - verdict.maxBottomAltitudeDeg, 0.01)
        assertNull(result.landscape.derivedFocalMm)
        assertNull(result.portrait.derivedFocalMm)
        // The closed forms are still reported (finite here).
        assertEquals(81.9, result.landscape.closedFormFocalMm, 4.0)
        assertTrue(result.landscape.segments.isEmpty())
    }

    @Test
    fun aSpanBeyondBothWidestFramesIsTheSecondVerdict() {
        // Centre-only at 75°: the span tan(75°) needs about 128.5°
        // of vertical field; both closed forms sit under 14 mm.
        val samples = series(centre = { point(180.0, 75.0) })
        val result = runEval(samples, foregroundTopDeg = 0.0)
        val verdict = result.verdict
        assertTrue("was $verdict", verdict is LandscapeComposition.Verdict.SpanExceedsWidestFrame)
        verdict as LandscapeComposition.Verdict.SpanExceedsWidestFrame
        assertEquals(result.anchor.instant, verdict.anchorInstant)
        assertEquals(at(0), verdict.anchorInstant)
        assertEquals(128.5, verdict.requiredVerticalFovDeg, 3.0)
        assertEquals(128.5, result.landscape.requiredVerticalFovDeg, 3.0)
        assertNull(result.landscape.derivedFocalMm)
        assertNull(result.portrait.derivedFocalMm)
    }

    @Test
    fun windowsOnlyOutsideTheNightSpanAreTheThirdVerdict() {
        // Early on, the range rides clear of the 20° foreground
        // top (bottom 22°). By the time the night span opens at
        // minute 350 the skeleton has rotated so its bottom sits
        // at 19° — sunk all night — while the centre is at its
        // highest. A geometric window exists; no night window does.
        fun centreAlt(m: Long): Double = when {
            m <= 150 -> 25.0
            m >= 350 -> 30.0
            else -> 25.0 + 5.0 * (m - 150) / 200.0
        }
        fun bottomAlt(m: Long): Double = when {
            m <= 150 -> 22.0
            m >= 350 -> 19.0
            else -> 22.0 - 3.0 * (m - 150) / 200.0
        }
        fun topAlt(m: Long): Double = when {
            m <= 150 -> 35.0
            m >= 350 -> 45.0
            else -> 35.0 + 10.0 * (m - 150) / 200.0
        }
        val samples = series(
            centre = { m -> point(180.0, centreAlt(m)) },
            points = { m -> listOf(point(180.0, bottomAlt(m)), point(180.0, topAlt(m))) },
        )
        val result = runEval(
            samples, foregroundTopDeg = 20.0,
            nightSpan = NightTimeline.Interval(at(350), at(600)),
        )
        assertTrue("was ${result.verdict}",
            result.verdict is LandscapeComposition.Verdict.AllOutsideNight)
        assertTrue(result.landscape.derivedFocalMm != null)
        assertTrue(result.landscape.geometricSegments.isNotEmpty())
        assertTrue(result.landscape.segments.isEmpty())
        assertTrue(result.portrait.segments.isEmpty())
    }

    @Test
    fun aBlinkOfNightWindowIsTheFourthVerdict() {
        // The parked subject of the closed-form test, whose
        // geometric window is the whole axis — but the night span
        // clips only a 6-minute slice of it. The night window is
        // real yet under the 10-minute usable floor: verdict 4.
        val samples = series(centre = { point(180.0, 40.0) })
        val result = runEval(
            samples, foregroundTopDeg = 10.0,
            nightSpan = NightTimeline.Interval(at(100), at(106)),
        )
        assertTrue("was ${result.verdict}",
            result.verdict is LandscapeComposition.Verdict.WindowTooShort)
        val segment = result.landscape.segments.single()
        assertEquals(6.0, segment.durationMinutes, 0.1)
        assertEquals(600.0, result.landscape.geometricSegments.single().durationMinutes, 1.0)
    }

    @Test
    fun theSinkConstraintSetsAWindowEnd() {
        // The centre descends linearly; the range bottom reaches
        // the 10° foreground top when the centre crosses 15°, at
        // minute 500 — long before the descending range would
        // leave the frame's lower edge. The segment must end at
        // the interpolated sink crossing.
        val samples = series(
            centre = { m -> point(180.0, 40.0 - 0.05 * m) },
            points = { m ->
                val a = 40.0 - 0.05 * m
                listOf(point(180.0, a - 5.0), point(180.0, a + 5.0))
            },
        )
        val result = runEval(samples, foregroundTopDeg = 10.0)
        val segment = result.landscape.segments.single()
        assertEquals(at(0), segment.start)
        assertTrue("end at minute ${minutesOf(segment.end)}",
            minutesOf(segment.end) in 492..508)
    }

    @Test
    fun theFrameTopSetsAWindowEndWhileTheSubjectStillRises() {
        // The centre culminates at minute 300, but the top point's
        // offset keeps growing, so after the culmination the top
        // point pushes through the frame's upper edge (at about
        // minute 326) while the range bottom is still far above
        // the foreground top: the frame constraint, not sinking,
        // ends the window.
        fun centreAlt(m: Long): Double = 40.0 - 0.02 * abs(m - 300)
        val samples = series(
            centre = { m -> point(180.0, centreAlt(m)) },
            points = { m ->
                listOf(
                    point(180.0, centreAlt(m) - 5.0),
                    point(180.0, centreAlt(m) + 5.0 + 0.05 * m),
                )
            },
        )
        val result = runEval(samples, foregroundTopDeg = 0.0)
        val segment = result.landscape.segments.single()
        assertEquals(at(0), segment.start)
        val endMinute = minutesOf(segment.end)
        assertTrue("end at minute $endMinute", endMinute in 312..342)
        // At that end the bottom is still far from sinking.
        val bottomAtEnd = centreAlt(endMinute) - 5.0
        assertTrue(bottomAtEnd > 20.0)
    }

    @Test
    fun theBestMomentSitsWhereTheVerticalMarginsBalance() {
        // A symmetric culmination at 40° with the range riding
        // ±8°: with the derived frame, the upper and lower margins
        // balance where the centre crosses about 29.5°, once on
        // the way up and once on the way down. The best moment is
        // a balance crossing, with the two margins near equal.
        fun centreAlt(m: Long): Double = 40.0 - 0.0002 * (m - 300) * (m - 300)
        val samples = series(
            centre = { m -> point(180.0, centreAlt(m)) },
            points = { m ->
                listOf(point(180.0, centreAlt(m) - 8.0), point(180.0, centreAlt(m) + 8.0))
            },
        )
        val result = runEval(samples, foregroundTopDeg = 10.0)
        val verdict = result.verdict
        assertTrue("was $verdict", verdict is LandscapeComposition.Verdict.Feasible)
        val outcome =
            if ((verdict as LandscapeComposition.Verdict.Feasible).recommendedPortrait) {
                result.portrait
            } else {
                result.landscape
            }
        val segment = outcome.segments.single()
        val bestMinute = minutesOf(segment.best.instant)
        assertTrue("best at minute $bestMinute", bestMinute in 55..90 || bestMinute in 510..545)
        val focal = outcome.derivedFocalMm!!
        val centre = LandscapeComposition.frameCentreAltitudeDeg(10.0, focal, outcome.portrait)
        val v = LandscapeComposition.verticalFovDeg(focal, outcome.portrait)
        val alt = segment.best.centreAltitudeDeg
        val upper = (centre + v / 2.0) - (alt + 8.0)
        val lower = (alt - 8.0) - 10.0
        assertTrue("margins upper $upper lower $lower", abs(upper - lower) < 1.0)
        assertTrue("best centre altitude $alt", alt in 28.0..31.0)
    }

    @Test
    fun aBestMomentCutByTheNightEdgeIsRecomputedInside() {
        // The same symmetric culmination, but the night opens at
        // minute 400: the cut segment's best is the balance
        // crossing on the way down (about minute 540), not the
        // geometric segment's earlier crossing.
        fun centreAlt(m: Long): Double = 40.0 - 0.0002 * (m - 300) * (m - 300)
        val samples = series(
            centre = { m -> point(180.0, centreAlt(m)) },
            points = { m ->
                listOf(point(180.0, centreAlt(m) - 8.0), point(180.0, centreAlt(m) + 8.0))
            },
        )
        val result = runEval(
            samples, foregroundTopDeg = 10.0,
            nightSpan = NightTimeline.Interval(at(400), at(600)),
        )
        val segment = result.landscape.segments.single()
        assertEquals(at(400), segment.start)
        val bestMinute = minutesOf(segment.best.instant)
        assertTrue("best at minute $bestMinute", bestMinute in 520..560)
    }

    @Test
    fun theAnchorFallsOnTheNightStartWhenTheNightOnlyDescends() {
        // The centre descends all night; inside a night span that
        // opens at minute 200 its greatest altitude is the span's
        // own interpolated start point.
        val samples = series(centre = { m -> point(180.0, 50.0 - 40.0 * m / 600.0) })
        val result = runEval(
            samples, foregroundTopDeg = 0.0,
            nightSpan = NightTimeline.Interval(at(200), at(600)),
        )
        assertEquals(at(200), result.anchor.instant)
        assertEquals(36.67, result.anchor.centreAltitudeDeg, 0.7)
    }

    @Test
    fun theShareIsNullWithoutSizeData() {
        val samples = series(centre = { point(180.0, 40.0) })
        val result = runEval(samples, foregroundTopDeg = 10.0, sizeMajorArcmin = null)
        assertTrue(result.landscape.derivedFocalMm != null)
        assertNull(result.landscape.share)
        assertNull(result.portrait.share)
    }

    @Test
    fun theShareMatchesTheLongSideShareAtTheDerivedFocal() {
        val samples = series(centre = { point(180.0, 40.0) })
        val result = runEval(samples, foregroundTopDeg = 10.0, sizeMajorArcmin = 300.0)
        for (outcome in listOf(result.landscape, result.portrait)) {
            val expected = FullFrameFraming.shareAtFocal(300.0, outcome.derivedFocalMm!!)
            assertEquals(expected, outcome.share!!, 1e-9)
        }
    }

    @Test
    fun aClosedFormBeyondTheDomainIsCappedAtTheMaximumFocal() {
        // A centre-only subject half a degree over the foreground
        // top: the span is tan(0.5°), the closed forms run to
        // thousands of millimetres, and both orientations solve at
        // the 2000 mm cap with the cap reported.
        val samples = series(centre = { point(180.0, 10.5) })
        val result = runEval(samples, foregroundTopDeg = 10.0, sizeMajorArcmin = 300.0)
        assertTrue("was ${result.verdict}", result.verdict is LandscapeComposition.Verdict.Feasible)
        for (outcome in listOf(result.landscape, result.portrait)) {
            assertTrue(outcome.closedFormFocalMm > LandscapeComposition.MAX_FOCAL_MM)
            assertEquals(LandscapeComposition.MAX_FOCAL_MM, outcome.derivedFocalMm!!, 0.01)
            assertTrue(outcome.focalCappedAtMax)
            assertEquals(
                FullFrameFraming.shareAtFocal(300.0, LandscapeComposition.MAX_FOCAL_MM),
                outcome.share!!, 1e-9,
            )
        }
    }

    @Test
    fun placementFormulasAreTheStatedOnes() {
        // Frame centre = T + 0.4 * V; landscape V at 50 mm is
        // 2*atan(12/50) ≈ 26.99°, portrait V the long side ≈ 39.6°.
        assertEquals(
            10.0 + 0.4 * 26.99,
            LandscapeComposition.frameCentreAltitudeDeg(10.0, 50.0, portrait = false),
            0.05,
        )
        assertEquals(
            27.0, LandscapeComposition.verticalFovDeg(50.0, portrait = false), 0.1,
        )
        assertEquals(
            39.6, LandscapeComposition.verticalFovDeg(50.0, portrait = true), 0.1,
        )
    }

    @Test
    fun twoPassesGiveTwoSegmentsNeverMerged() {
        // The centre's altitude oscillates with a 300-minute
        // period: two culminations, two separate in-window spans.
        val samples = series(centre = { m ->
            point(180.0, 15.0 + 10.0 * sin(m * 2.0 * Math.PI / 300.0))
        })
        val result = runEval(samples, foregroundTopDeg = 10.0)
        assertTrue("was ${result.verdict}", result.verdict is LandscapeComposition.Verdict.Feasible)
        assertTrue(
            "segments=${result.landscape.segments.size}",
            result.landscape.segments.size >= 2,
        )
    }

    @Test
    fun theLowAltitudeHintFiresUnderTheRailOnly() {
        val low = runEval(series(centre = { point(180.0, 4.0) }), foregroundTopDeg = 0.0)
        assertTrue(low.verdict is LandscapeComposition.Verdict.Feasible)
        assertTrue(low.lowAltitudeHint)
        val high = runEval(series(centre = { point(180.0, 20.0) }), foregroundTopDeg = 0.0)
        assertTrue(high.verdict is LandscapeComposition.Verdict.Feasible)
        assertTrue(!high.lowAltitudeHint)
    }

    @Test
    fun segmentAltitudeRangeTracksTheCentre() {
        // A slow linear climb across the whole axis, in frame and
        // clear of the foreground throughout: one segment spanning
        // the axis, its altitude range the climb's own ends.
        val samples = series(centre = { m -> point(180.0, 20.0 + 5.0 * m / 600.0) })
        val result = runEval(samples, foregroundTopDeg = 10.0)
        val segment = result.landscape.segments.single()
        assertEquals(20.0, segment.minAltitudeDeg, 0.6)
        assertEquals(25.0, segment.maxAltitudeDeg, 0.6)
        assertEquals(600.0, segment.durationMinutes, 2.0)
    }
}
