package app.astroexposure.usecase

import app.astroexposure.domain.camera.FramingChart
import app.astroexposure.ui.ChartModel
import app.astroexposure.ui.LabelLayoutItem
import app.astroexposure.ui.LabelPlacement
import app.astroexposure.ui.layoutChartLabels
import kotlin.math.hypot

/**
 * Test-side label measurement for the framing chart's layout
 * tests (0.13.1). Production measures every label with the real
 * TextMeasurer at 10 sp, single-line; a JVM test has no device
 * font, so this fixture injects a per-character width model of
 * that measurement (CJK 10 dp, Latin capitals 7 dp, lowercase
 * 5.3 dp, digits 5.6 dp, line height 13 dp — the model the
 * 0.13.1 design probe used). Placements under this fixture can
 * drift slightly from a given device's font metrics; the layout
 * RULES do not depend on it, and the golden assertions pin the
 * placements this measurement produces.
 */
internal fun approximateLabelSizeDip(text: String): Pair<Float, Float> {
    var width = 0f
    for (ch in text) {
        width += when {
            ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF -> 10f
            ch in 'A'..'Z' -> 7f
            ch in 'a'..'z' -> 5.3f
            ch in '0'..'9' -> 5.6f
            ch.code in 0x0370..0x03FF -> 6.3f
            ch in "⁰¹²³⁴⁵⁶⁷⁸⁹" -> 4f
            ch == ' ' || ch == '·' -> 3f
            else -> 5.6f
        }
    }
    return width to 13f
}

/** One label's anchor in dp on a canvas of the given dp size: the canvas's place() math, in dp. */
internal fun labelAnchorDip(
    model: ChartModel,
    labelIndex: Int,
    fieldDiagonalDeg: Double,
    projection: FramingChart.Projection,
    canvasWidthDip: Float,
    canvasHeightDip: Float,
): Pair<Float, Float> {
    val radius = FramingChart.projectedFieldRadius(fieldDiagonalDeg, projection)
    val scale = hypot(canvasWidthDip, canvasHeightDip) / 2.0 / radius
    val projected = FramingChart.project(model.labels[labelIndex].point, projection)
    return (canvasWidthDip / 2.0 - projected.xEast * scale).toFloat() to
        (canvasHeightDip / 2.0 - projected.yNorth * scale).toFloat()
}

/**
 * Runs the production layout for one chart model on the
 * reference canvas (360x280 dp unless stated), with the fixture
 * measurement injected. The result aligns with model.labels.
 */
internal fun layoutForModel(
    model: ChartModel,
    fieldDiagonalDeg: Double,
    projection: FramingChart.Projection,
    canvasWidthDip: Float = 360f,
    canvasHeightDip: Float = 280f,
): List<LabelPlacement?> {
    val items = model.labels.mapIndexed { index, label ->
        val (anchorX, anchorY) = labelAnchorDip(
            model, index, fieldDiagonalDeg, projection, canvasWidthDip, canvasHeightDip,
        )
        val (width, height) = approximateLabelSizeDip(label.text)
        LabelLayoutItem(
            text = label.text,
            level = label.level,
            magnitude = label.magnitude,
            messier = label.messier,
            majorArcmin = label.majorArcmin,
            separationDeg = label.separationDeg,
            anchorXDip = anchorX,
            anchorYDip = anchorY,
            widthDip = width,
            heightDip = height,
        )
    }
    return layoutChartLabels(items, canvasWidthDip, canvasHeightDip)
}
