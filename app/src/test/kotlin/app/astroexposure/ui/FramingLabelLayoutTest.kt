package app.astroexposure.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the framing chart's label avoidance layout
 * ([layoutChartLabels], ui-design §7.10, 0.13.1): candidate
 * table, priority order, edge flips, the far ring's anchor gate,
 * suppression, the P0 minimum-overlap fallback, and determinism.
 * All geometry is synthetic, on a 360x280 dp canvas.
 */
class FramingLabelLayoutTest {

    private fun item(
        text: String,
        level: ChartLabelLevel,
        anchorX: Float,
        anchorY: Float,
        width: Float = 40f,
        height: Float = 13f,
        magnitude: Double = 1.0,
        messier: Boolean = false,
        majorArcmin: Double = 0.0,
        separationDeg: Double = 0.0,
    ) = LabelLayoutItem(
        text = text,
        level = level,
        magnitude = magnitude,
        messier = messier,
        majorArcmin = majorArcmin,
        separationDeg = separationDeg,
        anchorXDip = anchorX,
        anchorYDip = anchorY,
        widthDip = width,
        heightDip = height,
    )

    @Test
    fun candidateTableMatchesTheSpec() {
        val offsets = labelCandidateOffsetsDip(40f)
        assertEquals(
            listOf(
                6f to -14f, -46f to -14f, 6f to 2f, -46f to 2f,
                -20f to -16f, -20f to 4f, 6f to -6f, -46f to -6f,
                6f to -30f, -46f to -30f, 6f to 18f, -46f to 18f,
            ),
            offsets,
        )
    }

    @Test
    fun uncontestedLabelKeepsTheDefaultCandidate() {
        val placements = layoutChartLabels(
            listOf(item("A", ChartLabelLevel.P1, 150f, 140f)), 360f, 280f,
        )
        val placement = placements.single()
        check(placement != null)
        assertEquals(0, placement.candidateIndex)
        assertEquals(156f, placement.topLeftXDip, 0f)
        assertEquals(126f, placement.topLeftYDip, 0f)
        assertTrue(!placement.minOverlapFallback)
    }

    @Test
    fun priorityBeatsInputOrder() {
        // The P2 label is fed first; the P0 label must still win
        // the default candidate at the shared anchor.
        val placements = layoutChartLabels(
            listOf(
                item("neighbour", ChartLabelLevel.P2, 150f, 140f),
                item("out star", ChartLabelLevel.P0, 150f, 140f),
            ),
            360f, 280f,
        )
        assertEquals(1, placements[0]?.candidateIndex)
        assertEquals(0, placements[1]?.candidateIndex)
    }

    @Test
    fun p1OrderIsByMagnitude() {
        val placements = layoutChartLabels(
            listOf(
                item("faint", ChartLabelLevel.P1, 150f, 140f, magnitude = 2.0),
                item("bright", ChartLabelLevel.P1, 150f, 140f, magnitude = 0.5),
            ),
            360f, 280f,
        )
        assertEquals(1, placements[0]?.candidateIndex)
        assertEquals(0, placements[1]?.candidateIndex)
    }

    @Test
    fun p2OrderIsMessierThenMajorThenSeparation() {
        // Four neighbours at one anchor take candidates in the
        // P2 order: Messier before the rest regardless of size,
        // larger major axis first within a group, nearer first
        // on a tie.
        val placements = layoutChartLabels(
            listOf(
                item("plain far", ChartLabelLevel.P2, 150f, 140f,
                    majorArcmin = 500.0, separationDeg = 20.0),
                item("messier small", ChartLabelLevel.P2, 150f, 140f,
                    messier = true, majorArcmin = 10.0, separationDeg = 5.0),
                item("plain near", ChartLabelLevel.P2, 150f, 140f,
                    majorArcmin = 500.0, separationDeg = 5.0),
                item("messier big", ChartLabelLevel.P2, 150f, 140f,
                    messier = true, majorArcmin = 300.0, separationDeg = 10.0),
            ),
            360f, 280f,
        )
        assertEquals(3, placements[0]?.candidateIndex)
        assertEquals(1, placements[1]?.candidateIndex)
        assertEquals(2, placements[2]?.candidateIndex)
        assertEquals(0, placements[3]?.candidateIndex)
    }

    @Test
    fun rightEdgeAnchorFlipsToALeftCandidate() {
        val placements = layoutChartLabels(
            listOf(item("edge", ChartLabelLevel.P1, 355f, 100f, width = 50f)),
            360f, 280f,
        )
        val placement = placements.single()
        check(placement != null)
        assertEquals(1, placement.candidateIndex)
        assertEquals(299f, placement.topLeftXDip, 0f)
        assertEquals(86f, placement.topLeftYDip, 0f)
    }

    @Test
    fun topEdgeAnchorFlipsBelow() {
        val placements = layoutChartLabels(
            listOf(item("top", ChartLabelLevel.P1, 100f, 4f)),
            360f, 280f,
        )
        val placement = placements.single()
        check(placement != null)
        assertEquals(2, placement.candidateIndex)
        assertEquals(106f, placement.topLeftXDip, 0f)
        assertEquals(6f, placement.topLeftYDip, 0f)
    }

    @Test
    fun farRingIsDeniedToOffCanvasAnchors() {
        // Anchor above the canvas: every near candidate's box
        // starts above the top margin, and the far ring — which
        // could drop the box into view — is gated off, because
        // the label's referent is not on the chart.
        val placements = layoutChartLabels(
            listOf(item("ghost", ChartLabelLevel.P1, 100f, -5f, width = 30f)),
            360f, 280f,
        )
        assertNull(placements.single())
    }

    @Test
    fun exactlyTheClearanceApartIsNotAnIntersection() {
        // B's default box starts exactly 2 dp right of A's placed
        // box: touching at the clearance distance is allowed.
        val placements = layoutChartLabels(
            listOf(
                item("A", ChartLabelLevel.P1, 150f, 140f, magnitude = 0.5),
                item("B", ChartLabelLevel.P1, 192f, 140f, magnitude = 1.5),
            ),
            360f, 280f,
        )
        assertEquals(0, placements[0]?.candidateIndex)
        assertEquals(0, placements[1]?.candidateIndex)
    }

    @Test
    fun p0FallsBackToLeastOverlapP1Suppresses() {
        // One huge early P0 box covers every candidate of the
        // shared anchor: a later P0 still places (minimum
        // overlap, all candidates tie so the lowest index wins),
        // a P1 in the same spot suppresses.
        val placements = layoutChartLabels(
            listOf(
                item("blocker", ChartLabelLevel.P0, 60f, 60f,
                    width = 240f, height = 160f, magnitude = 0.1),
                item("vital", ChartLabelLevel.P0, 150f, 140f, magnitude = 1.0),
                item("ordinary", ChartLabelLevel.P1, 150f, 140f, magnitude = 0.5),
            ),
            360f, 280f,
        )
        assertEquals(0, placements[0]?.candidateIndex)
        val vital = placements[1]
        check(vital != null)
        assertEquals(0, vital.candidateIndex)
        assertTrue(vital.minOverlapFallback)
        assertNull(placements[2])
    }

    @Test
    fun p0WithoutAnyInsideCandidateIsSuppressed() {
        val placements = layoutChartLabels(
            listOf(item("far out", ChartLabelLevel.P0, -50f, -50f)),
            360f, 280f,
        )
        assertNull(placements.single())
    }

    @Test
    fun tooWideLabelIsSuppressedEvenForP0() {
        // A single line wider than the canvas minus margins has
        // no fully-inside candidate anywhere.
        val placements = layoutChartLabels(
            listOf(item("huge", ChartLabelLevel.P0, 150f, 140f, width = 400f)),
            360f, 280f,
        )
        assertNull(placements.single())
    }

    @Test
    fun layoutIsDeterministicUnderInputShuffle() {
        val items = listOf(
            item("one", ChartLabelLevel.P2, 150f, 140f,
                messier = true, majorArcmin = 90.0, separationDeg = 3.0),
            item("two", ChartLabelLevel.P1, 152f, 141f, magnitude = 1.2),
            item("three", ChartLabelLevel.P0, 148f, 139f, magnitude = 0.8),
            item("four", ChartLabelLevel.P2, 30f, 30f,
                majorArcmin = 30.0, separationDeg = 40.0),
            item("five", ChartLabelLevel.P1, 320f, 250f, magnitude = 2.2),
            item("six", ChartLabelLevel.P2, 151f, 142f,
                majorArcmin = 20.0, separationDeg = 3.5),
        )
        fun byText(list: List<LabelLayoutItem>): Map<String, LabelPlacement?> {
            val result = layoutChartLabels(list, 360f, 280f)
            return list.mapIndexed { index, it -> it.text to result[index] }.toMap()
        }
        val forward = byText(items)
        assertEquals(forward, byText(items.reversed()))
        assertEquals(forward, byText(items.drop(3) + items.take(3)))
        // And the crowded cluster came out pairwise clear.
        val boxes = items.mapIndexedNotNull { index, it ->
            val p = layoutChartLabels(items, 360f, 280f)[index] ?: return@mapIndexedNotNull null
            floatArrayOf(p.topLeftXDip, p.topLeftYDip, it.widthDip, it.heightDip)
        }
        for (a in boxes) {
            for (b in boxes) {
                if (a === b) continue
                val overlapX = minOf(a[0] + a[2], b[0] + b[2] + 2f) - maxOf(a[0], b[0] - 2f)
                val overlapY = minOf(a[1] + a[3], b[1] + b[3] + 2f) - maxOf(a[1], b[1] - 2f)
                assertTrue(!(overlapX > 0f && overlapY > 0f))
            }
        }
    }
}
