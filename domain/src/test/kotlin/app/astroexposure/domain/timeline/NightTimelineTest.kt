package app.astroexposure.domain.timeline

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightTimelineTest {

    private val start: Instant = Instant.parse("2026-10-27T22:00:00Z")

    private fun sample(minutes: Long, sun: Double, moon: Double = -10.0) =
        NightTimeline.Sample(start.plusSeconds(minutes * 60), sun, moon, 0.5)

    @Test
    fun bandThresholdsFollowStandardTwilightDefinitions() {
        assertEquals(NightTimeline.SunBand.DAY, NightTimeline.bandFor(0.1))
        assertEquals(NightTimeline.SunBand.CIVIL_TWILIGHT, NightTimeline.bandFor(-3.0))
        assertEquals(NightTimeline.SunBand.NAUTICAL_TWILIGHT, NightTimeline.bandFor(-9.0))
        assertEquals(NightTimeline.SunBand.ASTRONOMICAL_TWILIGHT, NightTimeline.bandFor(-15.0))
        assertEquals(NightTimeline.SunBand.ASTRONOMICAL_NIGHT, NightTimeline.bandFor(-20.0))
        // Boundary values belong to the deeper band (the limit is "ends at" the angle).
        assertEquals(NightTimeline.SunBand.NAUTICAL_TWILIGHT, NightTimeline.bandFor(-6.0))
        assertEquals(NightTimeline.SunBand.ASTRONOMICAL_NIGHT, NightTimeline.bandFor(-18.0))
    }

    @Test
    fun bandSegmentsMergeAndCoverTheAxis() {
        val samples = listOf(
            sample(0, 2.0), sample(5, -3.0), sample(10, -9.0),
            sample(15, -20.0), sample(20, -20.0),
        )
        val axisEnd = start.plusSeconds(25 * 60)
        val segments = NightTimeline.bandSegments(samples, start, axisEnd)
        assertEquals(4, segments.size)
        assertEquals(NightTimeline.SunBand.DAY, segments[0].band)
        assertEquals(NightTimeline.SunBand.ASTRONOMICAL_NIGHT, segments[3].band)
        assertEquals(start, segments.first().start)
        assertEquals(axisEnd, segments.last().end)
        for (i in 0 until segments.size - 1) {
            assertEquals(segments[i].end, segments[i + 1].start)
        }
    }

    @Test
    fun bandSegmentsDegradeToOneBandWithoutSunEvents() {
        // Polar-day style series: the Sun never sets, so only the day band exists.
        val samples = listOf(sample(0, 5.0), sample(5, 6.0))
        val segments = NightTimeline.bandSegments(samples, start, start.plusSeconds(600))
        assertEquals(1, segments.size)
        assertEquals(NightTimeline.SunBand.DAY, segments[0].band)
        assertTrue(NightTimeline.bandSegments(emptyList(), start, start.plusSeconds(600)).isEmpty())
    }

    @Test
    fun moonAboveIntervalsFollowSampleRuns() {
        val samples = listOf(
            sample(0, -20.0, -5.0), sample(5, -20.0, 3.0), sample(10, -20.0, 8.0),
            sample(15, -20.0, -1.0), sample(20, -20.0, 2.0),
        )
        val axisEnd = start.plusSeconds(25 * 60)
        val intervals = NightTimeline.moonAboveIntervals(samples, start, axisEnd)
        assertEquals(2, intervals.size)
        assertEquals(start.plusSeconds(5 * 60), intervals[0].start)
        assertEquals(start.plusSeconds(15 * 60), intervals[0].end)
        // A run still open at the last sample closes at the axis end.
        assertEquals(axisEnd, intervals[1].end)
    }

    @Test
    fun clampToAxisHandlesOpenEdgesAndMisses() {
        val axisEnd = start.plusSeconds(3600)
        val open = NightTimeline.clampToAxis(null, null, start, axisEnd)
        assertEquals(NightTimeline.Interval(start, axisEnd), open)
        val partial = NightTimeline.clampToAxis(start.minusSeconds(600), start.plusSeconds(600), start, axisEnd)
        assertEquals(NightTimeline.Interval(start, start.plusSeconds(600)), partial)
        assertNull(NightTimeline.clampToAxis(axisEnd.plusSeconds(60), axisEnd.plusSeconds(120), start, axisEnd))
    }

    @Test
    fun isWithinAxisIncludesEndpointsAndRejectsOutside() {
        val axisEnd = start.plusSeconds(3600)
        assertTrue(NightTimeline.isWithinAxis(start.plusSeconds(1800), start, axisEnd))
        assertTrue(NightTimeline.isWithinAxis(start, start, axisEnd))
        assertTrue(NightTimeline.isWithinAxis(axisEnd, start, axisEnd))
        // An instant before the axis (e.g. an afternoon moonrise on an
        // axis that starts an hour before sunset) is NOT on the axis:
        // presenting it would pin its label to the left edge.
        assertFalse(NightTimeline.isWithinAxis(start.minusSeconds(1), start, axisEnd))
        assertFalse(NightTimeline.isWithinAxis(axisEnd.plusSeconds(1), start, axisEnd))
    }

    @Test
    fun fractionOfClampsOutsideTheAxis() {
        val axisEnd = start.plusSeconds(3600)
        assertEquals(0.5, NightTimeline.fractionOf(start.plusSeconds(1800), start, axisEnd), 1e-9)
        assertEquals(0.0, NightTimeline.fractionOf(start.minusSeconds(5), start, axisEnd), 1e-9)
        assertEquals(1.0, NightTimeline.fractionOf(axisEnd.plusSeconds(5), start, axisEnd), 1e-9)
    }

    @Test
    fun instantAtFractionClampsOutsideTheAxis() {
        // The inverse of fractionOf clamps the same way: a raw
        // out-of-range fraction lands on the nearer axis end rather
        // than extrapolating past it.
        val axisEnd = start.plusSeconds(3600)
        assertEquals(start.plusSeconds(1800), NightTimeline.instantAtFraction(0.5, start, axisEnd))
        assertEquals(start, NightTimeline.instantAtFraction(-0.25, start, axisEnd))
        assertEquals(axisEnd, NightTimeline.instantAtFraction(1.25, start, axisEnd))
    }

    // -- Whole-hour axis stepping (ui-design §7.4, 0.8.10) --

    private val nyZone: ZoneId = ZoneId.of("America/New_York")

    private fun local(year: Int, month: Int, day: Int, hour: Int, minute: Int): Instant =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, nyZone).toInstant()

    private fun hourLabels(axisStart: Instant, axisEnd: Instant, step: Int): List<String> =
        NightTimeline.hourAxisLabels(axisStart, axisEnd, nyZone, step).map {
            DateTimeFormatter.ofPattern("HH:mm").format(it.atZone(nyZone))
        }

    /** The UI's estimate for an "HH:mm" label at the axis size (10.5sp). */
    private val axisLabelWidthDp = 5 * 0.55 * 10.5

    @Test
    fun hourAxisOnBoardLengthAxisTakesThreeHourStep() {
        // The comparison board's axis: 17:35–08:47 (15.2 h). Hourly
        // labels would collide, and a 2h step — wide enough on paper —
        // breaks the density cap, so 3h wins and the label set is the
        // spec §7.4 example exactly.
        val axisStart = local(2026, 10, 28, 17, 35)
        val axisEnd = local(2026, 10, 29, 8, 47)
        val step = NightTimeline.hourAxisStepHours(axisStart, axisEnd, 344.0, axisLabelWidthDp)
        assertEquals(3, step)
        assertEquals(
            listOf("18:00", "21:00", "00:00", "03:00", "06:00"),
            hourLabels(axisStart, axisEnd, step),
        )
    }

    @Test
    fun hourAxisOnShortSummerAxisTakesOneHourStep() {
        // A 5⅙-hour axis: an hour spans ~67dp of plot, far clear of
        // the label width, and the density cap allows hourly labels.
        val axisStart = local(2026, 6, 15, 21, 10)
        val axisEnd = local(2026, 6, 16, 2, 20)
        val step = NightTimeline.hourAxisStepHours(axisStart, axisEnd, 344.0, axisLabelWidthDp)
        assertEquals(1, step)
        assertEquals(
            listOf("22:00", "23:00", "00:00", "01:00", "02:00"),
            hourLabels(axisStart, axisEnd, step),
        )
    }

    @Test
    fun hourAxisOnMediumAxisTakesTwoHourStep() {
        // An 11-hour axis: hourly spacing (~31dp) is just under the
        // label width plus gap, so 2h is the smallest clearing step.
        val axisStart = local(2026, 9, 1, 19, 20)
        val axisEnd = local(2026, 9, 2, 6, 20)
        val step = NightTimeline.hourAxisStepHours(axisStart, axisEnd, 344.0, axisLabelWidthDp)
        assertEquals(2, step)
        assertEquals(
            listOf("20:00", "22:00", "00:00", "02:00", "04:00", "06:00"),
            hourLabels(axisStart, axisEnd, step),
        )
    }

    @Test
    fun hourAxisStepWidensWhenLabelsWouldCollide() {
        // A 9-hour axis squeezed onto a 140dp plot (~15.6dp per
        // hour): even 2h stays under the label width plus gap, so the
        // non-overlap rule — not the density cap — forces 3h.
        val axisStart = local(2026, 12, 1, 20, 0)
        val axisEnd = local(2026, 12, 2, 5, 0)
        assertEquals(
            3,
            NightTimeline.hourAxisStepHours(axisStart, axisEnd, 140.0, axisLabelWidthDp),
        )
    }

    @Test
    fun hourAxisStepFallsBackToCoarsestStep() {
        // An extreme 30-hour span on a 100dp plot: no candidate
        // clears the labels, and the documented fallback is the
        // coarsest step (the UI's omission backstop then decides).
        val axisStart = local(2026, 12, 21, 12, 0)
        val axisEnd = axisStart.plusSeconds(30 * 3600)
        assertEquals(
            6,
            NightTimeline.hourAxisStepHours(axisStart, axisEnd, 100.0, axisLabelWidthDp),
        )
    }

    // --- Track band vertical mapping (0.8.11) -----------------------------------

    @Test
    fun altitudeFractionMapsTheTrackDomainLinearly() {
        // Domain −36°…+90° (ui-design §7.4): the ends map to 0 and 1,
        // the horizon sits at 36/126, and the mid-domain is linear.
        assertEquals(0.0, NightTimeline.altitudeFraction(-36.0), 1e-9)
        assertEquals(1.0, NightTimeline.altitudeFraction(90.0), 1e-9)
        assertEquals(36.0 / 126.0, NightTimeline.altitudeFraction(0.0), 1e-9)
        assertEquals(0.5, NightTimeline.altitudeFraction(27.0), 1e-9)
        assertEquals(
            2.0 / 3.0, NightTimeline.altitudeFraction(48.0), 1e-9,
        )
    }

    @Test
    fun altitudeFractionClampsOutsideTheTrackDomain() {
        assertEquals(0.0, NightTimeline.altitudeFraction(-90.0), 1e-9)
        assertEquals(1.0, NightTimeline.altitudeFraction(120.0), 1e-9)
    }

    // --- Track band curve runs (0.8.13) -----------------------------------------
    // Points are (axis fraction, altitude in degrees); the band fraction of
    // an altitude is (altitude + 36) / 126.

    private fun assertRunEquals(
        expected: List<Pair<Double, Double>>,
        actual: List<Pair<Double, Double>>,
    ) {
        assertEquals(expected.size, actual.size)
        for ((e, a) in expected zip actual) {
            assertEquals(e.first, a.first, 1e-9)
            assertEquals(e.second, a.second, 1e-9)
        }
    }

    @Test
    fun trackCurveRunsKeepsAnInsideCurveWhole() {
        val runs = NightTimeline.trackCurveRuns(
            listOf(0.0 to 27.0, 0.5 to 48.0, 1.0 to 27.0),
        )
        assertEquals(1, runs.size)
        assertRunEquals(
            listOf(0.0 to 0.5, 0.5 to 2.0 / 3.0, 1.0 to 0.5),
            runs[0],
        )
    }

    @Test
    fun trackCurveRunsBreaksBelowTheDomainAndResumesAtTheCrossings() {
        // Down through the bottom edge between the first two samples,
        // back in between the third and fourth (altitude -99° is
        // band fraction -0.5; 0° is 36/126).
        val runs = NightTimeline.trackCurveRuns(
            listOf(
                0.0 to 0.0, 0.25 to -99.0, 0.5 to -99.0,
                0.75 to 0.0, 1.0 to 27.0,
            ),
        )
        assertEquals(2, runs.size)
        val exitT = (36.0 / 126.0) / (36.0 / 126.0 + 0.5)
        assertRunEquals(
            listOf(0.0 to 36.0 / 126.0, 0.25 * exitT to 0.0),
            runs[0],
        )
        val entryT = 0.5 / (0.5 + 36.0 / 126.0)
        assertRunEquals(
            listOf(
                0.5 + 0.25 * entryT to 0.0,
                0.75 to 36.0 / 126.0,
                1.0 to 0.5,
            ),
            runs[1],
        )
    }

    @Test
    fun trackCurveRunsBreaksAboveTheDomain() {
        val runs = NightTimeline.trackCurveRuns(
            listOf(0.0 to 27.0, 0.5 to 153.0, 1.0 to 27.0),
        )
        assertEquals(2, runs.size)
        assertRunEquals(listOf(0.0 to 0.5, 0.25 to 1.0), runs[0])
        assertRunEquals(listOf(0.75 to 1.0, 1.0 to 0.5), runs[1])
    }

    @Test
    fun trackCurveRunsPassesThroughTheWholeDomainInOneStep() {
        val runs = NightTimeline.trackCurveRuns(
            listOf(0.0 to -99.0, 1.0 to 153.0),
        )
        assertEquals(1, runs.size)
        assertRunEquals(listOf(0.25 to 0.0, 0.75 to 1.0), runs[0])
    }

    @Test
    fun trackCurveRunsTreatsBoundarySamplesAsInside() {
        val runs = NightTimeline.trackCurveRuns(
            listOf(0.0 to -36.0, 1.0 to 90.0),
        )
        assertEquals(1, runs.size)
        assertRunEquals(listOf(0.0 to 0.0, 1.0 to 1.0), runs[0])
    }

    @Test
    fun trackCurveRunsDropsEmptyOutsideAndSingletonInput() {
        assertTrue(NightTimeline.trackCurveRuns(emptyList()).isEmpty())
        assertTrue(NightTimeline.trackCurveRuns(listOf(0.0 to 27.0)).isEmpty())
        assertTrue(
            NightTimeline.trackCurveRuns(
                listOf(0.0 to -99.0, 1.0 to -90.0),
            ).isEmpty(),
        )
    }
}
