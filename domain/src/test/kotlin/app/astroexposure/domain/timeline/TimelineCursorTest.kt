package app.astroexposure.domain.timeline

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Boundary and golden tests for the timeline cursor rules (ui-design §7.5). */
class TimelineCursorTest {

    private fun t(minutesFromBase: Long): Instant = BASE.plusSeconds(minutesFromBase * 60)

    // --- Ranking ---------------------------------------------------------------

    @Test
    fun rankingFiltersBelowThresholdAndSortsByAltitude() {
        val candidates = listOf(
            TimelineCursor.Candidate(19.9, 0.0), // below 20°: out
            TimelineCursor.Candidate(20.0, 5.0), // boundary: in
            TimelineCursor.Candidate(55.0, 9.0),
            TimelineCursor.Candidate(80.0, 1.0),
        )
        assertEquals(listOf(3, 2, 1), TimelineCursor.rankShootable(candidates))
    }

    @Test
    fun rankingTieOnAltitudePrefersSmallerMoonlight() {
        val candidates = listOf(
            TimelineCursor.Candidate(40.0, 3.0),
            TimelineCursor.Candidate(40.0, 1.0),
            TimelineCursor.Candidate(40.0, 2.0),
        )
        assertEquals(listOf(1, 2, 0), TimelineCursor.rankShootable(candidates))
    }

    @Test
    fun rankingEmptyWhenNothingQualifies() {
        val candidates = listOf(TimelineCursor.Candidate(-5.0, 0.0), TimelineCursor.Candidate(19.99, 0.0))
        assertTrue(TimelineCursor.rankShootable(candidates).isEmpty())
    }

    // --- Moonlight interference tiers -------------------------------------------

    @Test
    fun interferenceNoneWhenNoMoonlight() {
        assertEquals(
            TimelineCursor.MoonInterference.NONE,
            TimelineCursor.moonInterference(0.0, 0.25),
        )
    }

    @Test
    fun interferenceWeakBelowOneThirdLine() {
        // Baseline 0.30 -> the 1/3 line is 0.10.
        assertEquals(
            TimelineCursor.MoonInterference.WEAK,
            TimelineCursor.moonInterference(0.099, 0.30),
        )
    }

    @Test
    fun interferenceStrongAtExactlyOneThirdLine() {
        // The boundary belongs to STRONG (ui-design §7.5: "≥1/3").
        assertEquals(
            TimelineCursor.MoonInterference.STRONG,
            TimelineCursor.moonInterference(0.10, 0.30),
        )
        assertEquals(
            TimelineCursor.MoonInterference.STRONG,
            TimelineCursor.moonInterference(2.0, 0.30),
        )
    }

    @Test
    fun interferenceStrongWhenBaselineMissingAndMoonlightPresent() {
        assertEquals(
            TimelineCursor.MoonInterference.STRONG,
            TimelineCursor.moonInterference(0.01, 0.0),
        )
    }

    // --- Snapping -----------------------------------------------------------------

    @Test
    fun snapWithinFiveMinutesInclusive() {
        val moments = listOf(t(0), t(60))
        assertEquals(0, TimelineCursor.snappedMomentIndex(t(5), moments))
        assertEquals(0, TimelineCursor.snappedMomentIndex(t(-5), moments))
        assertEquals(1, TimelineCursor.snappedMomentIndex(t(55), moments))
    }

    @Test
    fun snapOutsideWindowIsNull() {
        val moments = listOf(t(0), t(60))
        assertNull(TimelineCursor.snappedMomentIndex(t(6), moments))
        assertNull(TimelineCursor.snappedMomentIndex(t(30), moments))
        assertNull(TimelineCursor.snappedMomentIndex(t(0), emptyList()))
    }

    @Test
    fun snapPicksNearestAndEarlierOnTie() {
        val moments = listOf(t(0), t(8))
        assertEquals(1, TimelineCursor.snappedMomentIndex(t(5), moments))
        assertEquals(0, TimelineCursor.snappedMomentIndex(t(4), moments)) // equidistant: earlier wins
    }

    // --- Minute unit / axis clamp ---------------------------------------------------

    @Test
    fun truncationDropsSecondsAndNanos() {
        val withSeconds = BASE.plusSeconds(90).plusNanos(123)
        assertEquals(BASE.plusSeconds(60), TimelineCursor.truncatedToMinute(withSeconds))
    }

    @Test
    fun clampKeepsCursorInsideAxis() {
        assertEquals(t(0), TimelineCursor.clampToAxis(t(-30), t(0), t(120)))
        assertEquals(t(120), TimelineCursor.clampToAxis(t(300), t(0), t(120)))
        assertEquals(t(42), TimelineCursor.clampToAxis(t(42), t(0), t(120)))
    }

    // --- Arc intervals / culmination ---------------------------------------------------

    private fun samples(vararg altitudes: Double): List<TimelineCursor.AltitudeSample> =
        altitudes.mapIndexed { i, alt -> TimelineCursor.AltitudeSample(t(i * 5L), alt) }

    @Test
    fun arcIntervalsFollowRunsAtSampleResolution() {
        // Above 20° from sample 1..2 and 4..5 (5-minute steps).
        val intervals = TimelineCursor.aboveThresholdIntervals(
            samples(10.0, 25.0, 30.0, 15.0, 22.0, 28.0, 5.0), 20.0, t(35),
        )
        assertEquals(2, intervals.size)
        assertEquals(t(5), intervals[0].start)
        assertEquals(t(15), intervals[0].end)
        assertEquals(t(20), intervals[1].start)
        assertEquals(t(30), intervals[1].end)
    }

    @Test
    fun arcIntervalOpenAtAxisEndClosesThere() {
        val intervals = TimelineCursor.aboveThresholdIntervals(samples(30.0, 40.0), 20.0, t(10))
        assertEquals(listOf(NightTimeline.Interval(t(0), t(10))), intervals)
    }

    @Test
    fun arcThresholdBoundaryCountsAsAbove() {
        val intervals = TimelineCursor.aboveThresholdIntervals(samples(20.0, 19.9), 20.0, t(10))
        assertEquals(1, intervals.size)
        assertEquals(t(5), intervals[0].end)
    }

    @Test
    fun culminationIsHighestSample() {
        val culmination = TimelineCursor.culminationOf(samples(10.0, 44.0, 30.0))
        assertEquals(t(5), culmination!!.instant)
        assertEquals(44.0, culmination.altitudeDeg, 1e-9)
        assertNull(TimelineCursor.culminationOf(emptyList()))
    }

    // --- Low-moonlight window status ------------------------------------------------------

    @Test
    fun lowMoonStatusInsideWindow() {
        val windows = listOf(NightTimeline.Interval(t(10), t(50)))
        val status = TimelineCursor.lowMoonStatus(t(10), windows)
        assertEquals(TimelineCursor.LowMoonStatus.Inside(t(50)), status)
        assertEquals(TimelineCursor.LowMoonStatus.Inside(t(50)), TimelineCursor.lowMoonStatus(t(49), windows))
    }

    @Test
    fun lowMoonStatusNextWindowAndNone() {
        val windows = listOf(
            NightTimeline.Interval(t(10), t(20)),
            NightTimeline.Interval(t(60), t(90)),
        )
        assertEquals(
            TimelineCursor.LowMoonStatus.Next(t(60)),
            TimelineCursor.lowMoonStatus(t(30), windows),
        )
        // At the closing edge the window no longer contains the instant.
        assertEquals(
            TimelineCursor.LowMoonStatus.Next(t(60)),
            TimelineCursor.lowMoonStatus(t(20), windows),
        )
        assertEquals(
            TimelineCursor.LowMoonStatus.None,
            TimelineCursor.lowMoonStatus(t(95), windows),
        )
        assertEquals(
            TimelineCursor.LowMoonStatus.None,
            TimelineCursor.lowMoonStatus(t(30), emptyList()),
        )
    }

    // --- Bubble moon altitude interpolation --------------------------------------------------

    @Test
    fun moonAltitudeInterpolatesBetweenSamples() {
        val axisSamples = listOf(
            NightTimeline.Sample(t(0), -10.0, 10.0, 0.0),
            NightTimeline.Sample(t(5), -12.0, 20.0, 0.0),
            NightTimeline.Sample(t(10), -14.0, 30.0, 0.0),
        )
        assertEquals(15.0, TimelineCursor.moonAltitudeAt(axisSamples, t(2).plusSeconds(30))!!, 1e-9)
        assertEquals(20.0, TimelineCursor.moonAltitudeAt(axisSamples, t(5))!!, 1e-9)
        // Outside the span: nearest edge sample.
        assertEquals(10.0, TimelineCursor.moonAltitudeAt(axisSamples, t(-30))!!, 1e-9)
        assertEquals(30.0, TimelineCursor.moonAltitudeAt(axisSamples, t(60))!!, 1e-9)
        assertNull(TimelineCursor.moonAltitudeAt(emptyList(), t(0)))
    }

    // --- Bubble moon illumination interpolation -------------------------------------------

    @Test
    fun moonIlluminationInterpolatesBetweenSamples() {
        val axisSamples = listOf(
            NightTimeline.Sample(t(0), -10.0, 10.0, 0.40),
            NightTimeline.Sample(t(5), -12.0, 20.0, 0.50),
            NightTimeline.Sample(t(10), -14.0, 30.0, 0.60),
        )
        assertEquals(0.45, TimelineCursor.moonIlluminationAt(axisSamples, t(2).plusSeconds(30))!!, 1e-9)
        assertEquals(0.50, TimelineCursor.moonIlluminationAt(axisSamples, t(5))!!, 1e-9)
        assertEquals(0.40, TimelineCursor.moonIlluminationAt(axisSamples, t(-30))!!, 1e-9)
        assertEquals(0.60, TimelineCursor.moonIlluminationAt(axisSamples, t(60))!!, 1e-9)
        assertNull(TimelineCursor.moonIlluminationAt(emptyList(), t(0)))
    }

    // --- Arc phases (0.5.1 segmentation) ------------------------------------------------------

    @Test
    fun arcPhasesSplitRisingCulminationAndSetting() {
        // 5-minute samples: rises through 20° at index 1, peaks 50° at
        // index 4, sets below 20° after index 7. The culmination band
        // (>= 45°) covers indices 3..5.
        val phases = TimelineCursor.arcPhases(
            samples(10.0, 25.0, 38.0, 46.0, 50.0, 47.0, 39.0, 26.0, 12.0), 20.0, t(45),
        )!!
        assertEquals(TimelineCursor.AltitudeSample(t(20), 50.0), phases.culmination)
        assertEquals(NightTimeline.Interval(t(5), t(40)), phases.window)
        assertEquals(NightTimeline.Interval(t(5), t(15)), phases.rising)
        assertEquals(NightTimeline.Interval(t(15), t(30)), phases.culminationSpan)
        assertEquals(NightTimeline.Interval(t(30), t(40)), phases.setting)
    }

    @Test
    fun arcPhasesMonotonicRiseHasNoSettingSegment() {
        // Still rising at the last sample: the culmination is the final
        // sample, the span closes at the axis end and there is no
        // setting segment.
        val phases = TimelineCursor.arcPhases(samples(10.0, 30.0, 44.0, 49.0), 20.0, t(20))!!
        assertEquals(NightTimeline.Interval(t(5), t(20)), phases.window)
        assertEquals(NightTimeline.Interval(t(10), t(20)), phases.culminationSpan)
        assertEquals(NightTimeline.Interval(t(5), t(10)), phases.rising)
        assertNull(phases.setting)
    }

    @Test
    fun arcPhasesMonotonicSettingHasNoRisingSegment() {
        val phases = TimelineCursor.arcPhases(samples(49.0, 44.0, 30.0, 10.0), 20.0, t(20))!!
        assertEquals(NightTimeline.Interval(t(0), t(15)), phases.window)
        assertEquals(NightTimeline.Interval(t(0), t(10)), phases.culminationSpan)
        assertNull(phases.rising)
        assertEquals(NightTimeline.Interval(t(10), t(15)), phases.setting)
    }

    @Test
    fun arcPhasesEntireWindowInsideBandHasNoSideSegments() {
        // Peak 24°, threshold 20°: the band floor is the threshold
        // itself, so the whole window is the culmination span.
        val phases = TimelineCursor.arcPhases(samples(10.0, 21.0, 24.0, 22.0, 8.0), 20.0, t(25))!!
        assertEquals(phases.window, phases.culminationSpan)
        assertNull(phases.rising)
        assertNull(phases.setting)
    }

    @Test
    fun arcPhasesCulminationAtWindowEdgeSample() {
        // Peak exactly at the first above-threshold sample.
        val phases = TimelineCursor.arcPhases(samples(15.0, 48.0, 30.0, 21.0, 5.0), 20.0, t(25))!!
        assertEquals(t(5), phases.culmination.instant)
        assertNull(phases.rising)
        assertEquals(NightTimeline.Interval(t(5), t(10)), phases.culminationSpan)
        assertEquals(NightTimeline.Interval(t(10), t(20)), phases.setting)
    }

    @Test
    fun arcPhasesNullWhenNeverAboveThreshold() {
        assertNull(TimelineCursor.arcPhases(samples(5.0, 19.9, 10.0), 20.0, t(15)))
        assertNull(TimelineCursor.arcPhases(emptyList(), 20.0, t(15)))
    }

    @Test
    fun phaseAtClassifiesAroundTheCulminationSpan() {
        val phases = TimelineCursor.arcPhases(
            samples(10.0, 25.0, 38.0, 46.0, 50.0, 47.0, 39.0, 26.0, 12.0), 20.0, t(45),
        )!!
        // Span = [t(15), t(30)).
        assertEquals(TimelineCursor.PassPhase.RISING, TimelineCursor.phaseAt(phases, t(0)))
        assertEquals(TimelineCursor.PassPhase.RISING, TimelineCursor.phaseAt(phases, t(14)))
        assertEquals(TimelineCursor.PassPhase.NEAR_CULMINATION, TimelineCursor.phaseAt(phases, t(15)))
        assertEquals(TimelineCursor.PassPhase.NEAR_CULMINATION, TimelineCursor.phaseAt(phases, t(29)))
        assertEquals(TimelineCursor.PassPhase.SETTING, TimelineCursor.phaseAt(phases, t(30)))
        assertEquals(TimelineCursor.PassPhase.SETTING, TimelineCursor.phaseAt(phases, t(44)))
    }

    @Test
    fun gestureStateMachineWithoutCursorPlacesFreelyButCannotStep() {
        val p = TimelineCursor.CursorPlacement.NO_CURSOR
        assertTrue(TimelineCursor.mayMoveCursor(p, TimelineCursor.CursorGesture.TAP))
        assertTrue(TimelineCursor.mayMoveCursor(p, TimelineCursor.CursorGesture.DRAG))
        assertTrue(TimelineCursor.mayMoveCursor(p, TimelineCursor.CursorGesture.LONG_PRESS_DRAG))
        assertFalse(TimelineCursor.mayMoveCursor(p, TimelineCursor.CursorGesture.STEP))
    }

    @Test
    fun gestureStateMachinePlacedUnlockedNeedsLongPressDrag() {
        val p = TimelineCursor.CursorPlacement.PLACED_UNLOCKED
        assertFalse(TimelineCursor.mayMoveCursor(p, TimelineCursor.CursorGesture.TAP))
        assertFalse(TimelineCursor.mayMoveCursor(p, TimelineCursor.CursorGesture.DRAG))
        assertTrue(TimelineCursor.mayMoveCursor(p, TimelineCursor.CursorGesture.LONG_PRESS_DRAG))
        assertTrue(TimelineCursor.mayMoveCursor(p, TimelineCursor.CursorGesture.STEP))
    }

    @Test
    fun gestureStateMachineLockedAllowsOnlySteps() {
        val p = TimelineCursor.CursorPlacement.PLACED_LOCKED
        assertFalse(TimelineCursor.mayMoveCursor(p, TimelineCursor.CursorGesture.TAP))
        assertFalse(TimelineCursor.mayMoveCursor(p, TimelineCursor.CursorGesture.DRAG))
        assertFalse(TimelineCursor.mayMoveCursor(p, TimelineCursor.CursorGesture.LONG_PRESS_DRAG))
        assertTrue(TimelineCursor.mayMoveCursor(p, TimelineCursor.CursorGesture.STEP))
    }

    private companion object {
        val BASE: Instant = Instant.parse("2026-10-27T22:00:00Z")
    }
}
