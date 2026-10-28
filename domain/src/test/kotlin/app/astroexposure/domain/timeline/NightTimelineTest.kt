package app.astroexposure.domain.timeline

import java.time.Instant
import org.junit.Assert.assertEquals
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
    fun axisMinFollowsTheDeepestSampleWithMargin() {
        // Deepest sample -12° -> margin and 5° step give -20°.
        val shallow = listOf(sample(0, -12.0, -3.0), sample(5, -5.0, -8.0))
        assertEquals(-20.0, NightTimeline.axisMinAltitudeDeg(shallow), 1e-9)
        // A Moon well below the horizon (routine at mid latitudes: the
        // Moon can reach about -60° to -78°) is drawn in full, not
        // clipped at the former fixed -20° floor.
        val deep = listOf(sample(0, -30.0, -78.0), sample(5, -20.0, -40.0))
        assertEquals(-85.0, NightTimeline.axisMinAltitudeDeg(deep), 1e-9)
    }

    @Test
    fun axisMinClampsAtMinusNinetyAndKeepsTheHorizonReadable() {
        // Nothing can be sampled below -90° in practice, but the clamp
        // is pinned so the axis can never pass the floor.
        val extreme = listOf(sample(0, -95.0, -92.0))
        assertEquals(-90.0, NightTimeline.axisMinAltitudeDeg(extreme), 1e-9)
        // Polar-day style series that never set: the lower bound still
        // reaches below 0° so the horizon line stays inside the chart.
        val polarDay = listOf(sample(0, 8.0, 12.0), sample(5, 4.0, 20.0))
        assertEquals(-5.0, NightTimeline.axisMinAltitudeDeg(polarDay), 1e-9)
        // No samples: the documented fallback.
        assertEquals(-20.0, NightTimeline.axisMinAltitudeDeg(emptyList()), 1e-9)
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
}
