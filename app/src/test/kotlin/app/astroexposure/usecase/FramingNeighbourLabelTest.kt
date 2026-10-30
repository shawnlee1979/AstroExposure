package app.astroexposure.usecase

import app.astroexposure.domain.camera.FramingChart
import app.astroexposure.domain.catalog.ChartStar
import app.astroexposure.ui.FramingVerdict
import app.astroexposure.ui.buildChartModel
import app.astroexposure.ui.framingFitsAlsoAfterSwap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The framing chart's neighbour labelling and equivalence note
 * (ui-design §7.10, aligned in the 0.11.2 pre-release revision):
 *
 * - The neighbour label set is the neighbour DRAWN set. A large
 *   neighbour whose centre sits in the band between the field
 *   radius and its reach (radius + half its major axis) is drawn
 *   as a faint ellipse, so it must carry a label too — before the
 *   alignment it did not (labels stopped at the field radius),
 *   leaving nameless ellipses on the chart. A centre outside the
 *   field square has its label anchor clamped onto the square.
 * - The fits equivalence note applies exactly when the current
 *   orientation fits AND the swapped one does — both verdict
 *   shapes, and never for a cut verdict.
 */
class FramingNeighbourLabelTest {

    private val centerRa = 10.0
    private val centerDec = 40.0
    private val fieldDiagonal = 20.0
    private val radiusDeg = fieldDiagonal / 2.0

    private fun chartData(
        neighbours: List<TonightOverviewUseCase.FramingNeighbour>,
    ): TonightOverviewUseCase.FramingChartData =
        TonightOverviewUseCase.FramingChartData(
            centerRaDeg = centerRa,
            centerDecDeg = centerDec,
            ellipse = FramingChart.EllipseSpec(60.0, 30.0, 0.0),
            skeletonCode = null,
            emphasisCode = null,
            stars = emptyList(),
            lines = emptyMap(),
            neighbours = neighbours,
        )

    private val fitsVerdict = FramingVerdict.Ellipse(
        verdict = FramingChart.EllipseVerdict(fits = true, overflow = null),
        fitsAfterSwap = false,
    )

    /** A neighbour due east of the centre at the given separation. */
    private fun eastNeighbour(
        id: String,
        separationDeg: Double,
        sizeMajorArcmin: Double,
    ): TonightOverviewUseCase.FramingNeighbour {
        val deltaRa = separationDeg / kotlin.math.cos(Math.toRadians(centerDec))
        return TonightOverviewUseCase.FramingNeighbour(
            id = id,
            name = id,
            nameZh = null,
            raDeg = centerRa + deltaRa,
            decDeg = centerDec,
            sizeMajorArcmin = sizeMajorArcmin,
            sizeMinorArcmin = sizeMajorArcmin / 2.0,
            positionAngleDeg = null,
        )
    }

    @Test
    fun bandNeighbourIsDrawnLabelledAndNamed() {
        // Centre at 10.6 degrees: past the 10-degree field radius,
        // inside the reach of a 180-arcmin neighbour (10 + 1.5).
        val band = eastNeighbour("NGC9999", separationDeg = 10.6, sizeMajorArcmin = 180.0)
        val separation = FramingChart.angularSeparationDeg(
            centerRa, centerDec, band.raDeg, band.decDeg,
        )
        assertTrue("separation $separation", separation > radiusDeg)
        assertTrue("separation $separation", separation < radiusDeg + 180.0 / 120.0)

        val model = buildChartModel(chartData(listOf(band)), fieldDiagonal, fitsVerdict, false)
        assertEquals(1, model.neighbours.size)
        assertEquals(listOf("NGC 9999"), model.neighbourNames)
        val label = model.labels.single { it.text == "NGC 9999" }
        // The centre's tangent x lies past the field square, so
        // the anchor is clamped onto the square's edge.
        val halfSide = kotlin.math.tan(Math.toRadians(radiusDeg))
        assertEquals(halfSide, label.point.xEast, 1e-9)
    }

    @Test
    fun inFieldNeighbourLabelAnchorIsItsCentre() {
        val inside = eastNeighbour("NGC8888", separationDeg = 5.0, sizeMajorArcmin = 60.0)
        val model = buildChartModel(chartData(listOf(inside)), fieldDiagonal, fitsVerdict, false)
        assertEquals(1, model.neighbours.size)
        val label = model.labels.single { it.text == "NGC 8888" }
        val centre = FramingChart.tangentPoint(centerRa, centerDec, inside.raDeg, inside.decDeg)!!
        assertEquals(centre.xEast, label.point.xEast, 0.0)
        assertEquals(centre.yNorth, label.point.yNorth, 0.0)
    }

    @Test
    fun equivalenceNoteAppliesOnlyWhenBothOrientationsFit() {
        val ellipseFits = FramingChart.EllipseVerdict(fits = true, overflow = null)
        val ellipseCut = FramingChart.EllipseVerdict(
            fits = false, overflow = FramingChart.EllipseOverflow.LONG_AXIS,
        )
        assertTrue(
            framingFitsAlsoAfterSwap(
                FramingVerdict.Ellipse(ellipseFits, fitsAfterSwap = true),
            ),
        )
        assertFalse(
            framingFitsAlsoAfterSwap(
                FramingVerdict.Ellipse(ellipseFits, fitsAfterSwap = false),
            ),
        )
        assertFalse(
            framingFitsAlsoAfterSwap(
                FramingVerdict.Ellipse(ellipseCut, fitsAfterSwap = true),
            ),
        )
        assertTrue(
            framingFitsAlsoAfterSwap(
                FramingVerdict.Skeleton(
                    out = emptyList(), totalVertices = 9, fitsAfterSwap = true,
                ),
            ),
        )
        assertFalse(
            framingFitsAlsoAfterSwap(
                FramingVerdict.Skeleton(
                    out = emptyList(), totalVertices = 9, fitsAfterSwap = false,
                ),
            ),
        )
        val outStar = FramingChart.OutPoint(
            ChartStar(1, 0.0, 0.0, 1.0, "Out", null, null),
            FramingChart.Edge.NORTH,
        )
        assertFalse(
            framingFitsAlsoAfterSwap(
                FramingVerdict.Skeleton(
                    out = listOf(outStar), totalVertices = 9, fitsAfterSwap = true,
                ),
            ),
        )
    }
}
