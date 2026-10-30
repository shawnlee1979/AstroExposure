package app.astroexposure.domain.lens

import app.astroexposure.domain.camera.FullFrameFraming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The framing-chart candidate rule (ui-design §7.10, 0.13.0):
 * the suggested interval itself is the band, inclusion is focal
 * segment intersection (endpoint touch counts), order is log2
 * distance to the reference. The Triangulum shape (8° major axis,
 * 85–171 mm suggested, 128 mm reference) is the worked example
 * the rule was stated with; its full-library outcome is pinned
 * against the real catalogue in the app module's golden test.
 */
class FramingLensCandidatesTest {

    private fun prime(id: String, name: String, focal: Double) =
        Lens(id, name, "Sony E", focal, focal, 1.8)

    private fun zoom(id: String, name: String, min: Double, max: Double) =
        Lens(id, name, "Sony E", min, max, 2.8)

    private val triangulumSize = 480.0 // 8 degrees, in arcminutes
    private val triangulumReference =
        FullFrameFraming.focalForShare(triangulumSize, FullFrameFraming.SHARE_REFERENCE)

    @Test
    fun `interval is the suggested segment intersected with the slider domain`() {
        val selection = FramingLensCandidates.select(
            triangulumSize, triangulumReference, 14.0, 2000.0, emptyList(),
        )
        assertEquals(
            FullFrameFraming.focalForShare(triangulumSize, FullFrameFraming.SHARE_LOW),
            selection.intervalLowMm!!, 1e-9,
        )
        assertEquals(
            FullFrameFraming.focalForShare(triangulumSize, FullFrameFraming.SHARE_HIGH),
            selection.intervalHighMm!!, 1e-9,
        )
        // The design's worked example: 84.6–171.2 mm, reference 128 mm.
        assertEquals(84.6, selection.intervalLowMm!!, 0.1)
        assertEquals(171.2, selection.intervalHighMm!!, 0.1)
        assertEquals(128.0, triangulumReference, 0.2)
    }

    @Test
    fun `selection orders by log distance and excludes lenses outside the band`() {
        val lenses = listOf(
            prime("sony-11", "Sony E 11mm F1.8", 11.0),
            zoom("sony-12-24", "Sony FE 12-24mm F2.8 GM", 12.0, 24.0),
            prime("sony-50", "Sony FE 50mm F1.4 GM", 50.0),
            prime("sony-85", "Sony FE 85mm F1.4 GM II", 85.0),
            prime("sigma-85", "Sigma 85mm F1.4 DG DN Art", 85.0),
            prime("sony-135", "Sony FE 135mm F1.8 GM", 135.0),
            zoom("tamron-35-150", "Tamron 35-150mm F2-2.8 Di III VXD", 35.0, 150.0),
        )
        val selection = FramingLensCandidates.select(
            triangulumSize, triangulumReference, 14.0, 2000.0, lenses,
        )
        assertEquals(
            listOf(
                "Tamron 35-150mm F2-2.8 Di III VXD",
                "Sony FE 135mm F1.8 GM",
                "Sigma 85mm F1.4 DG DN Art",
                "Sony FE 85mm F1.4 GM II",
            ),
            selection.candidates.map { it.lens.name },
        )
        assertEquals(0.0, selection.candidates[0].distanceStops, 1e-12)
        assertTrue(selection.candidates[1].distanceStops < selection.candidates[2].distanceStops)
    }

    @Test
    fun `endpoint touch counts as inclusion on both sides`() {
        val low = FullFrameFraming.focalForShare(triangulumSize, FullFrameFraming.SHARE_LOW)
        val high = FullFrameFraming.focalForShare(triangulumSize, FullFrameFraming.SHARE_HIGH)
        val lenses = listOf(
            prime("at-low", "At low", low),
            prime("below-low", "Below low", low * 0.999),
            zoom("touch-low", "Touch low", 24.0, low),
            zoom("touch-high", "Touch high", high, 400.0),
            prime("at-high", "At high", high),
            prime("above-high", "Above high", high * 1.001),
        )
        val selection = FramingLensCandidates.select(
            triangulumSize, triangulumReference, 14.0, 2000.0, lenses,
        )
        assertEquals(
            setOf("at-low", "touch-low", "touch-high", "at-high"),
            selection.candidates.map { it.lens.id }.toSet(),
        )
    }

    @Test
    fun `empty candidate set keeps the interval`() {
        // M31's shape: suggested 232–466 mm; a library topping out
        // at 150 mm reaches none of it — the interval still stands.
        val selection = FramingLensCandidates.select(
            177.0, FullFrameFraming.focalForShare(177.0, 0.5), 14.0, 2000.0,
            listOf(
                prime("sony-135", "Sony FE 135mm F1.8 GM", 135.0),
                zoom("tamron-35-150", "Tamron 35-150mm F2-2.8 Di III VXD", 35.0, 150.0),
            ),
        )
        assertTrue(selection.candidates.isEmpty())
        assertEquals(232.0, selection.intervalLowMm!!, 1.0)
        assertEquals(466.0, selection.intervalHighMm!!, 1.0)
    }

    @Test
    fun `capped subject has no interval and no candidates`() {
        // A 5-arcminute subject: even the band's low end sits far
        // beyond the slider's 2000 mm ceiling.
        val selection = FramingLensCandidates.select(
            5.0, 2000.0, 14.0, 2000.0,
            listOf(zoom("long", "100-400", 100.0, 400.0)),
        )
        assertNull(selection.intervalLowMm)
        assertNull(selection.intervalHighMm)
        assertTrue(selection.candidates.isEmpty())
    }

    @Test
    fun `oversized subject interval starts at the slider floor`() {
        // A 40-degree subject: the geometric segment starts below
        // the slider floor (f(1/3) = 10.4 mm), so the interval
        // clamps to 14 mm; its capped reference is the 24 mm
        // floor, which the 14-24 zoom covers exactly (distance 0,
        // ahead of the 14 mm prime).
        val selection = FramingLensCandidates.select(
            40.0 * 60.0, 24.0, 14.0, 2000.0,
            listOf(
                prime("p14", "14mm", 14.0),
                zoom("z14-24", "14-24mm", 14.0, 24.0),
                prime("p50", "50mm", 50.0),
            ),
        )
        assertEquals(14.0, selection.intervalLowMm!!, 1e-9)
        assertEquals(31.2, selection.intervalHighMm!!, 0.1)
        assertEquals(listOf("z14-24", "p14"), selection.candidates.map { it.lens.id })
    }

    @Test
    fun `distance of a zoom is zero when it covers the reference`() {
        val zoom = zoom("z", "35-150", 35.0, 150.0)
        assertEquals(0.0, FramingLensCandidates.distanceStops(zoom, 128.0), 1e-12)
        // Reference exactly on an endpoint still counts as covered.
        assertEquals(0.0, FramingLensCandidates.distanceStops(zoom, 150.0), 1e-12)
    }

    @Test
    fun `distance of a zoom outside the reference uses the nearer endpoint`() {
        val zoom = zoom("z", "35-150", 35.0, 150.0)
        assertEquals(
            kotlin.math.abs(kotlin.math.log2(150.0 / 200.0)),
            FramingLensCandidates.distanceStops(zoom, 200.0), 1e-12,
        )
        assertEquals(
            kotlin.math.abs(kotlin.math.log2(35.0 / 24.0)),
            FramingLensCandidates.distanceStops(zoom, 24.0), 1e-12,
        )
    }

    @Test
    fun `pick focal clamps the reference into a zoom segment`() {
        val zoom = zoom("z", "35-150", 35.0, 150.0)
        // Reference inside the segment: lands on the reference.
        assertEquals(128.0, FramingLensCandidates.pickFocalMm(zoom, 128.0), 1e-12)
        // Reference beyond the tele end: lands on the tele end.
        assertEquals(150.0, FramingLensCandidates.pickFocalMm(zoom, 200.0), 1e-12)
        // Reference below the wide end: lands on the wide end.
        assertEquals(35.0, FramingLensCandidates.pickFocalMm(zoom, 24.0), 1e-12)
    }

    @Test
    fun `pick focal of a prime is its own focal length`() {
        val prime = prime("p", "135mm", 135.0)
        assertEquals(135.0, FramingLensCandidates.pickFocalMm(prime, 128.0), 1e-12)
        assertEquals(135.0, FramingLensCandidates.pickFocalMm(prime, 600.0), 1e-12)
    }

    @Test
    fun `invalid size yields no interval`() {
        val selection = FramingLensCandidates.select(
            0.0, 128.0, 14.0, 2000.0, listOf(prime("p", "135mm", 135.0)),
        )
        assertNull(selection.intervalLowMm)
        assertTrue(selection.candidates.isEmpty())
    }
}
