package app.astroexposure.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Boundary tests for [labelDrawDecision] (0.11.2), the pure form
 * of the constraint formula Compose's drawText(String) overload
 * applies to a label anchor: maxWidth = round(ceil(canvas width
 * - anchor x)), maxHeight likewise. The device crash was the
 * formula going negative (the trap recorded maxWidth(-55)); the
 * decision must hand back the formula's own positive values
 * untouched and skip exactly when there is no room left.
 */
class FramingLabelDecisionTest {

    private fun draw(
        anchorX: Float,
        anchorY: Float,
        width: Float = 1080f,
        height: Float = 840f,
    ): LabelDrawDecision.Draw {
        val decision = labelDrawDecision(anchorX, anchorY, width, height)
        check(decision is LabelDrawDecision.Draw) {
            "expected Draw for anchor ($anchorX, $anchorY) on ${width}x$height, got $decision"
        }
        return decision
    }

    @Test
    fun wellInsideAnchorKeepsTheFormulaValues() {
        val d = draw(100f, 100f)
        assertEquals(980, d.maxWidthPx)
        assertEquals(740, d.maxHeightPx)
    }

    @Test
    fun anchorExactlyOnTheRightEdgeSkips() {
        // No room left: the formula yields maxWidth 0. (Legal for
        // Constraints, but the label would draw nothing.)
        assertEquals(
            LabelDrawDecision.Skip,
            labelDrawDecision(1080f, 100f, 1080f, 840f),
        )
    }

    @Test
    fun anchorOnePixelInsideTheRightEdgeDrawsWithOnePixel() {
        val d = draw(1079f, 100f)
        assertEquals(1, d.maxWidthPx)
        assertEquals(740, d.maxHeightPx)
    }

    @Test
    fun anchorBeyondTheRightEdgeSkips() {
        assertEquals(
            LabelDrawDecision.Skip,
            labelDrawDecision(1081f, 100f, 1080f, 840f),
        )
        // The crash trap's recorded case: 55 px of negative room.
        assertEquals(
            LabelDrawDecision.Skip,
            labelDrawDecision(1135f, 100f, 1080f, 840f),
        )
    }

    @Test
    fun fractionalRoomRoundsUpLikeTheShippedFormula() {
        // 0.2 px of room: ceil gives a legal 1 px constraint.
        val d = draw(1079.8f, 100f)
        assertEquals(1, d.maxWidthPx)
        // -0.4 px of room: ceil gives 0 — no crash in the shipped
        // formula either, and nothing could be drawn; Skip.
        assertEquals(
            LabelDrawDecision.Skip,
            labelDrawDecision(1080.4f, 100f, 1080f, 840f),
        )
    }

    @Test
    fun bottomEdgeBehavesLikeTheRightEdge() {
        assertEquals(
            LabelDrawDecision.Skip,
            labelDrawDecision(100f, 840f, 1080f, 840f),
        )
        val d = draw(100f, 839f)
        assertEquals(980, d.maxWidthPx)
        assertEquals(1, d.maxHeightPx)
        assertEquals(
            LabelDrawDecision.Skip,
            labelDrawDecision(100f, 900f, 1080f, 840f),
        )
    }

    @Test
    fun anchorOutsideTheTopOrLeftDrawsWithEnlargedConstraints() {
        // Negative anchors never made the formula negative: the
        // room grows past the canvas size and the canvas clips.
        val d = draw(-50f, -20f)
        assertEquals(1130, d.maxWidthPx)
        assertEquals(860, d.maxHeightPx)
    }

    @Test
    fun tinyCanvasBoundaries() {
        val d = draw(5f, 4f, width = 10f, height = 8f)
        assertEquals(5, d.maxWidthPx)
        assertEquals(4, d.maxHeightPx)
        val full = draw(0f, 0f, width = 10f, height = 8f)
        assertEquals(10, full.maxWidthPx)
        assertEquals(8, full.maxHeightPx)
        assertEquals(
            LabelDrawDecision.Skip,
            labelDrawDecision(10f, 8f, 10f, 8f),
        )
    }

    @Test
    fun fractionalCanvasAndAnchor() {
        val d = labelDrawDecision(100.25f, 50.5f, 360.75f, 280.25f)
        check(d is LabelDrawDecision.Draw)
        // ceil(260.5) = 261, ceil(229.75) = 230.
        assertEquals(261, d.maxWidthPx)
        assertEquals(230, d.maxHeightPx)
    }
}
