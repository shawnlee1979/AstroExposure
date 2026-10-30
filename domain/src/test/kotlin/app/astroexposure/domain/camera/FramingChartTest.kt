package app.astroexposure.domain.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Framing-chart geometry (ui-design §7.10): projection, frame
 * placement, point and ellipse verdicts, default focal mapping.
 * The worked figures are the design samples' own: 50 mm frames
 * Orion, 348 mm frames M31.
 */
class FramingChartTest {

    // -- Projection ------------------------------------------------------

    @Test
    fun `tangent point of the centre is the origin and east is positive`() {
        val centre = FramingChart.tangentPoint(83.7, 2.0, 83.7, 2.0)!!
        assertEquals(0.0, centre.xEast, 1e-12)
        assertEquals(0.0, centre.yNorth, 1e-12)
        val east = FramingChart.tangentPoint(0.0, 0.0, 10.0, 0.0)!!
        assertEquals(Math.tan(Math.toRadians(10.0)), east.xEast, 1e-9)
        assertEquals(0.0, east.yNorth, 1e-12)
        val north = FramingChart.tangentPoint(0.0, 0.0, 0.0, 10.0)!!
        assertEquals(0.0, north.xEast, 1e-12)
        assertTrue(north.yNorth > 0.0)
    }

    @Test
    fun `tangent point beyond ninety degrees is null`() {
        assertNull(FramingChart.tangentPoint(0.0, 0.0, 180.0, 0.0))
    }

    @Test
    fun `stereographic compresses the radius and agrees with gnomonic nearby`() {
        val far = FramingChart.TangentPoint(Math.tan(Math.toRadians(60.0)), 0.0)
        val projected = FramingChart.project(far, FramingChart.Projection.STEREOGRAPHIC)
        assertEquals(2.0 * Math.tan(Math.toRadians(30.0)), projected.xEast, 1e-9)
        assertEquals(
            far.xEast,
            FramingChart.project(far, FramingChart.Projection.GNOMONIC).xEast,
            0.0,
        )
        val near = FramingChart.TangentPoint(Math.tan(Math.toRadians(1.0)), 0.0)
        val nearProjected = FramingChart.project(near, FramingChart.Projection.STEREOGRAPHIC)
        assertEquals(near.xEast, nearProjected.xEast, 2e-6)
    }

    // -- Field extent ------------------------------------------------------

    @Test
    fun `field diagonal follows the approved rule with its clamps`() {
        // 50 mm: frame diagonal sqrt(39.6^2 + 27.0^2) = 47.9, chart 76.7.
        assertEquals(76.7, FramingChart.fieldDiagonalDeg(50.0), 0.15)
        // 348 mm: 1.6x the 7.1-degree frame diagonal is under the floor.
        assertEquals(14.0, FramingChart.fieldDiagonalDeg(348.0), 0.0)
        // 14 mm: past the ceiling.
        assertEquals(150.0, FramingChart.fieldDiagonalDeg(14.0), 0.0)
        assertEquals(
            FramingChart.Projection.GNOMONIC,
            FramingChart.projectionFor(FramingChart.fieldDiagonalDeg(50.0)),
        )
        assertEquals(
            FramingChart.Projection.STEREOGRAPHIC,
            FramingChart.projectionFor(FramingChart.fieldDiagonalDeg(14.0)),
        )
        assertEquals(
            FramingChart.Projection.GNOMONIC,
            FramingChart.projectionFor(90.0),
        )
    }

    // -- Frame placement and point verdicts --------------------------------

    @Test
    fun `frame half extents at fifty millimetres are the sensor halves over f`() {
        val (right, up) = FramingChart.frameHalfExtents(
            FramingChart.FrameSpec(50.0, portrait = false, positionAngleDeg = 0.0),
        )
        assertEquals(0.36, right, 1e-12)
        assertEquals(0.24, up, 1e-12)
        val (pRight, pUp) = FramingChart.frameHalfExtents(
            FramingChart.FrameSpec(50.0, portrait = true, positionAngleDeg = 0.0),
        )
        assertEquals(0.24, pRight, 1e-12)
        assertEquals(0.36, pUp, 1e-12)
    }

    @Test
    fun `points are judged with compass edges and the boundary counts as in`() {
        val centre = Pair(0.0, 0.0)
        fun point(ra: Double, dec: Double) = FramingChart.tangentPoint(
            centre.first, centre.second, ra, dec,
        )!!
        val spec = FramingChart.FrameSpec(50.0, portrait = false, positionAngleDeg = 0.0)
        val north = point(0.0, 15.0)
        val east = point(25.0, 0.0)
        val inside = point(10.0, 5.0)
        // Exactly on the edge (tangent units) counts as in; a hair
        // past it is out — stated boundary rule, no fuzzy band.
        val onEdge = FramingChart.TangentPoint(0.0, 0.24)
        val pastEdge = FramingChart.TangentPoint(0.0, 0.2400001)
        assertFalse(FramingChart.pointInFrame(north, spec))
        assertTrue(FramingChart.pointInFrame(inside, spec))
        assertTrue(FramingChart.pointInFrame(onEdge, spec))
        assertFalse(FramingChart.pointInFrame(pastEdge, spec))
        val out = FramingChart.judgePoints(
            listOf("n" to north, "e" to east, "i" to inside),
            spec,
        )
        assertEquals(
            listOf("n" to FramingChart.Edge.NORTH, "e" to FramingChart.Edge.EAST),
            out.map { it.item to it.edge },
        )
    }

    @Test
    fun `a ninety degree rotation judges the same rectangle as the swap`() {
        fun point(ra: Double, dec: Double) =
            FramingChart.tangentPoint(0.0, 0.0, ra, dec)!!
        val points = listOf(
            "a" to point(12.0, 3.0),
            "b" to point(3.0, 12.0),
            "c" to point(-8.0, -9.0),
            "d" to point(20.0, 20.0),
        )
        val rotated = FramingChart.judgePoints(
            points,
            FramingChart.FrameSpec(50.0, portrait = false, positionAngleDeg = 90.0),
        ).map { it.item }.toSet()
        val swapped = FramingChart.judgePoints(
            points,
            FramingChart.FrameSpec(50.0, portrait = true, positionAngleDeg = 0.0),
        ).map { it.item }.toSet()
        assertEquals(swapped, rotated)
    }

    // -- Ellipse verdicts ----------------------------------------------------

    private val m31 = FramingChart.EllipseSpec(
        majorArcmin = 177.83, minorArcmin = 69.66, positionAngleDeg = 35.0,
    )

    @Test
    fun `M31 fits aligned in portrait and unrotated in landscape at 348`() {
        assertTrue(
            FramingChart.judgeEllipse(
                m31, FramingChart.FrameSpec(348.0, portrait = true, positionAngleDeg = 35.0),
            ).fits,
        )
        assertTrue(
            FramingChart.judgeEllipse(
                m31, FramingChart.FrameSpec(348.0, portrait = false, positionAngleDeg = 0.0),
            ).fits,
        )
    }

    @Test
    fun `ellipse overflow names the responsible axis`() {
        val longOverflow = FramingChart.judgeEllipse(
            FramingChart.EllipseSpec(300.0, 60.0, 0.0),
            FramingChart.FrameSpec(500.0, portrait = false, positionAngleDeg = 0.0),
        )
        assertFalse(longOverflow.fits)
        assertEquals(FramingChart.EllipseOverflow.LONG_AXIS, longOverflow.overflow)
        val shortOverflow = FramingChart.judgeEllipse(
            FramingChart.EllipseSpec(200.0, 190.0, 0.0),
            FramingChart.FrameSpec(500.0, portrait = true, positionAngleDeg = 0.0),
        )
        assertFalse(shortOverflow.fits)
        assertEquals(FramingChart.EllipseOverflow.SHORT_AXIS, shortOverflow.overflow)
    }

    @Test
    fun `an ellipse without a position angle runs its major axis along RA`() {
        val noAngle = FramingChart.EllipseSpec(177.83, 69.66, null)
        assertTrue(
            FramingChart.judgeEllipse(
                noAngle, FramingChart.FrameSpec(348.0, portrait = true, positionAngleDeg = 90.0),
            ).fits,
        )
        // A missing minor axis is circular, never zero-width.
        val circular = FramingChart.EllipseSpec(120.0, null, null)
        assertTrue(
            FramingChart.judgeEllipse(
                circular, FramingChart.FrameSpec(100.0, portrait = false, positionAngleDeg = 0.0),
            ).fits,
        )
    }

    // -- Defaults and separation ----------------------------------------------

    @Test
    fun `default focal maps the four advice forms`() {
        assertEquals(
            49.5,
            FramingChart.defaultFocalMm(
                FullFrameFraming.Advice.Range(31.2, 67.2, 49.5, 0.5),
            )!!,
            0.0,
        )
        assertEquals(
            2000.0,
            FramingChart.defaultFocalMm(FullFrameFraming.Advice.CappedAtMaxFocal(0.01))!!,
            0.0,
        )
        assertEquals(
            24.0,
            FramingChart.defaultFocalMm(FullFrameFraming.Advice.CappedAtMinFocal(1.4))!!,
            0.0,
        )
        assertNull(FramingChart.defaultFocalMm(FullFrameFraming.Advice.NoSizeData))
    }

    @Test
    fun `angular separation is the great-circle distance`() {
        assertEquals(90.0, FramingChart.angularSeparationDeg(0.0, 0.0, 0.0, 90.0), 1e-9)
        assertEquals(0.0, FramingChart.angularSeparationDeg(10.0, 20.0, 10.0, 20.0), 1e-12)
        val betelgeuseToOrionCentre =
            FramingChart.angularSeparationDeg(88.7929, 7.4071, 83.7, 2.0)
        assertTrue(betelgeuseToOrionCentre in 7.0..7.6)
    }

    @Test
    fun `short side field of view uses the same formula on the short side`() {
        assertEquals(27.0, FullFrameFraming.shortSideFovDeg(50.0), 0.05)
        assertEquals(39.6, FullFrameFraming.longSideFovDeg(50.0), 0.05)
    }
}
