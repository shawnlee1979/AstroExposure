package app.astroexposure.usecase

import app.astroexposure.data.catalog.JsonConstellationCatalog
import app.astroexposure.data.catalog.JsonConstellationLines
import app.astroexposure.data.catalog.JsonMeteorShowerCatalog
import app.astroexposure.data.catalog.JsonObjectCatalog
import app.astroexposure.data.catalog.JsonStarCatalog
import app.astroexposure.data.catalog.JsonWideFieldCatalog
import app.astroexposure.data.ephemeris.AstronomyEngineEphemeris
import app.astroexposure.domain.camera.FramingChart
import app.astroexposure.domain.camera.FullFrameFraming
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.units.Degrees
import app.astroexposure.ui.ChartLabelLevel
import app.astroexposure.ui.LabelDrawDecision
import app.astroexposure.ui.buildChartModel
import app.astroexposure.ui.framingVerdict
import app.astroexposure.ui.labelCandidateOffsetsDip
import app.astroexposure.ui.labelDrawDecision
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full-subject sweep of the framing chart's LABEL constraints
 * (0.11.2). The 0.11.1 device crash was proven by the on-device
 * crash trap to be Compose's DrawScope.drawText(String) overload:
 * with no explicit size it derives the text constraints from the
 * anchor — maxWidth = round(ceil(canvas width - anchor x)) and
 * maxHeight = round(ceil(canvas height - anchor y)), a formula
 * verified against the shipped compose-ui-text bytecode
 * (TextPainterKt.textLayoutConstraints). A label anchored past
 * the right or bottom canvas edge makes the value negative and
 * Constraints throws IllegalArgumentException on the draw thread.
 *
 * This sweep rebuilds every subject's chart model through the
 * same path the section renders (framingChartFor, default focal,
 * framingVerdict, buildChartModel), places every label with the
 * canvas's own place() math on two canvas sizes (1080x840 at
 * density 3 and 360x280 at density 1: the same 360x280 dp chart
 * at two densities), and evaluates the shipped formula per label.
 * Language is not swept: the label SET and its anchors do not
 * depend on it, only the strings do.
 *
 * Red/green contract: a legacy violation (maxWidth or maxHeight
 * negative) is a label the 0.11.0/0.11.1 canvas would crash on.
 * The pre-fix run of this sweep counted them (661 of 661 charted
 * subjects affected somewhere in the swept configurations,
 * 276,642 label instances). Since the 0.11.2 fix the canvas
 * routes every label through labelDrawDecision; the legacy census
 * is still printed (it is the neutralized count now), and the
 * gate is the per-label decision assertion: wherever the legacy
 * formula was legal the decision must draw with its exact values,
 * and wherever it was not, the decision must skip.
 *
 * 0.13.1 extension: the canvas no longer draws labels at the
 * fixed (+6dp, -14dp) anchor the legacy census measures — the
 * avoidance layout (layoutChartLabels) picks each label's
 * placement or suppresses it. The legacy assertions above stay
 * as the decision function's fidelity record; this sweep
 * additionally runs the shipped layout for every configuration
 * (fixture measurement injected, on the 360x280 dp geometry both
 * canvases share) and asserts the layout's invariants: a placed
 * box lies fully inside the canvas, placed boxes are pairwise
 * clear, the far ring only serves on-canvas anchors, the safety
 * decision at every placement is Draw on both canvases, and a
 * suppressed P0 has no fully-inside candidate at all (checked
 * independently of the layout's intersection logic). P0
 * minimum-overlap fallback triggers are counted, printed and
 * asserted to be zero — the fallback is a fuse, not a path
 * (§7.10); if a future batch legitimately needs a trigger,
 * this assertion and the §7.7 record change together.
 */
class FramingLabelConstraintSweepTest {

    private val alpharetta = GeoLocation(
        latitude = Degrees(34.0754),
        longitude = Degrees(-84.2941),
        heightMeters = 0.0,
        zone = ZoneId.of("America/New_York"),
    )
    private val date: LocalDate = LocalDate.of(2026, 10, 29)
    private val instant: Instant = Instant.parse("2026-10-30T03:00:00Z")

    private val useCase = TonightOverviewUseCase(
        ephemeris = AstronomyEngineEphemeris(),
        objectCatalog = JsonObjectCatalog(),
        showerCatalog = JsonMeteorShowerCatalog(),
        constellationCatalog = JsonConstellationCatalog(),
        wideFieldCatalog = JsonWideFieldCatalog(),
        starCatalog = JsonStarCatalog(),
        constellationLines = JsonConstellationLines(),
    )

    /** A canvas size in px and the density its dp offsets use. */
    private data class CanvasSpec(val widthPx: Int, val heightPx: Int, val density: Float)

    private val canvases = listOf(
        CanvasSpec(1080, 840, 3.0f),
        CanvasSpec(360, 280, 1.0f),
    )

    /**
     * The shipped overload's constraint value for one axis:
     * round(ceil(available)), with the integral ceil making the
     * round exact. Negative means Constraints would throw.
     */
    private fun legacyMax(availablePx: Float): Int =
        ceil(availablePx.toDouble()).toInt()

    private var subjectsWithCharts = 0
    private var labelInstances = 0
    private var legacyViolations = 0
    private var legacyWidthViolations = 0
    private var legacyHeightViolations = 0
    private val violatingSubjects = mutableSetOf<String>()
    private val violationsPerSubject = mutableMapOf<String, Int>()
    private var defaultStateViolations = 0
    private val defaultStateViolatingSubjects = mutableSetOf<String>()
    private var minLegacyWidth = Int.MAX_VALUE
    private var minLegacyHeight = Int.MAX_VALUE
    private var layoutPlacements = 0
    private var layoutSuppressions = 0
    private var layoutP0Fallbacks = 0
    private val failures = mutableListOf<String>()

    @Test
    fun labelConstraintsAcrossAllSubjects() {
        val timeline = useCase.execute(alpharetta, date).timeline
        val constellationIds = JsonConstellationCatalog().constellations()
            .map { "constellation:${it.code}" }
        val objectIds = JsonObjectCatalog().allObjects().map { it.id }
        val allIds = constellationIds + objectIds
        println("LABEL SWEEP subjects: ${allIds.size}")

        for (id in allIds) {
            sweepSubject(id, timeline)
        }

        val worst = violationsPerSubject.entries
            .sortedByDescending { it.value }
            .take(8)
        val report = buildString {
            appendLine("subjects with charts: $subjectsWithCharts")
            appendLine("label instances evaluated: $labelInstances")
            appendLine("legacy violations (crash-class): $legacyViolations")
            appendLine("  width-negative: $legacyWidthViolations")
            appendLine("  height-negative: $legacyHeightViolations")
            appendLine("violating subjects: ${violatingSubjects.size}")
            appendLine("default-state violations (default focal, landscape, " +
                "rotation 0): $defaultStateViolations across " +
                "${defaultStateViolatingSubjects.size} subjects")
            appendLine("min legacy maxWidth: " +
                if (minLegacyWidth == Int.MAX_VALUE) "n/a" else "$minLegacyWidth")
            appendLine("min legacy maxHeight: " +
                if (minLegacyHeight == Int.MAX_VALUE) "n/a" else "$minLegacyHeight")
            appendLine("layout placements evaluated: $layoutPlacements")
            appendLine("layout suppressions: $layoutSuppressions")
            appendLine("P0 min-overlap fallbacks triggered (recorded): $layoutP0Fallbacks")
            appendLine("worst subjects: " +
                worst.joinToString { "${it.key}=${it.value}" })
            appendLine("decision failures: ${failures.size}")
            failures.take(10).forEach { appendLine(it) }
        }
        println(report)
        assertTrue(
            "label sweep: ${failures.size} decision failure(s); first: " +
                (failures.firstOrNull() ?: "none"),
            failures.isEmpty(),
        )
        assertEquals(
            "P0 minimum-overlap fallback must stay at zero across the " +
                "whole sweep (§7.10: the fallback is a fuse, not a path; " +
                "§7.7 records zero triggers — if a future batch ever " +
                "legitimately needs a trigger, change this assertion " +
                "and the spec record together)",
            0,
            layoutP0Fallbacks,
        )
    }

    private fun sweepSubject(id: String, timeline: TonightOverviewUseCase.Timeline) {
        val detail = useCase.evaluateTargetAtCursor(
            alpharetta, date, instant, timeline, null, id,
        ) ?: return
        val chart = detail.framingChart ?: return
        if (detail.framing is FullFrameFraming.Advice.NoSizeData) return
        subjectsWithCharts++
        val defaultFocal = FramingChart.defaultFocalMm(detail.framing) ?: return
        val focals = listOf(defaultFocal, 14.0, 2000.0).distinct()
        for (focal in focals) {
            val field = FramingChart.fieldDiagonalDeg(focal)
            val projection = FramingChart.projectionFor(field)
            val radius = FramingChart.projectedFieldRadius(field, projection)
            for (portrait in listOf(false, true)) {
                for (rotation in listOf(0.0, 90.0, 180.0)) {
                    val verdict = framingVerdict(
                        chart, FramingChart.FrameSpec(focal, portrait, rotation),
                    )
                    val model = buildChartModel(chart, field, verdict, english = false)
                    val isDefaultState =
                        focal == defaultFocal && !portrait && rotation == 0.0
                    evaluateLayout(id, model, field, projection)
                    for (canvas in canvases) {
                        evaluateLabels(
                            id, model, projection, radius, canvas, isDefaultState,
                        )
                    }
                }
            }
        }
    }

    private fun evaluateLabels(
        id: String,
        model: app.astroexposure.ui.ChartModel,
        projection: FramingChart.Projection,
        projectedRadius: Double,
        canvas: CanvasSpec,
        isDefaultState: Boolean,
    ) {
        // The canvas's own place() math (FramingChartCanvas),
        // replicated operation for operation.
        val scale = (hypot(canvas.widthPx.toFloat(), canvas.heightPx.toFloat()) /
            2.0 / projectedRadius).toFloat()
        val cx = canvas.widthPx / 2f
        val cy = canvas.heightPx / 2f
        for (label in model.labels) {
            val projected = FramingChart.project(label.point, projection)
            val atX = cx - (projected.xEast * scale).toFloat()
            val atY = cy - (projected.yNorth * scale).toFloat()
            // The label's drawText topLeft: the star's position
            // shifted by (+6dp, -14dp).
            val anchorX = atX + 6f * canvas.density
            val anchorY = atY - 14f * canvas.density
            labelInstances++
            val legacyW = legacyMax(canvas.widthPx - anchorX)
            val legacyH = legacyMax(canvas.heightPx - anchorY)
            minLegacyWidth = minOf(minLegacyWidth, legacyW)
            minLegacyHeight = minOf(minLegacyHeight, legacyH)
            if (legacyW < 0 || legacyH < 0) {
                legacyViolations++
                if (legacyW < 0) legacyWidthViolations++
                if (legacyH < 0) legacyHeightViolations++
                violatingSubjects += id
                violationsPerSubject[id] = (violationsPerSubject[id] ?: 0) + 1
                if (isDefaultState) {
                    defaultStateViolations++
                    defaultStateViolatingSubjects += id
                }
            }
            // The production decision the canvas now takes for
            // this very anchor: it must draw exactly the labels
            // the legacy formula could legally draw, with the
            // formula's own constraint values, and skip the rest.
            val decision = labelDrawDecision(
                anchorX, anchorY,
                canvas.widthPx.toFloat(), canvas.heightPx.toFloat(),
            )
            if (legacyW > 0 && legacyH > 0) {
                if (decision !is LabelDrawDecision.Draw ||
                    decision.maxWidthPx != legacyW ||
                    decision.maxHeightPx != legacyH
                ) {
                    failures += "=== $id @ decision ===\n" +
                        "DRAWABLE LABEL NOT DRAWN IDENTICALLY: " +
                        "legacy=($legacyW, $legacyH) decision=$decision " +
                        "label='${label.text}' canvas=$canvas"
                }
            } else if (decision !is LabelDrawDecision.Skip) {
                failures += "=== $id @ decision ===\n" +
                    "UNDRAWABLE LABEL NOT SKIPPED: " +
                    "legacy=($legacyW, $legacyH) decision=$decision " +
                    "label='${label.text}' canvas=$canvas"
            }
        }
    }

    /**
     * The 0.13.1 layout layer's assertions for one configuration,
     * on the 360x280 dp geometry both swept canvases share (see
     * the class doc). Sizes come from the fixture measurement —
     * the invariants asserted here (inside, pairwise clear, gate
     * respected, safety Draw, P0 suppression justified) are the
     * layout's contract under ANY measurement.
     */
    private fun evaluateLayout(
        id: String,
        model: app.astroexposure.ui.ChartModel,
        fieldDiagonalDeg: Double,
        projection: FramingChart.Projection,
    ) {
        val placements = layoutForModel(model, fieldDiagonalDeg, projection)
        val placedBoxes = mutableListOf<Pair<FloatArray, String>>()
        model.labels.forEachIndexed { index, label ->
            val (anchorX, anchorY) = labelAnchorDip(
                model, index, fieldDiagonalDeg, projection, 360f, 280f,
            )
            val (w, h) = approximateLabelSizeDip(label.text)
            val placement = placements[index]
            if (placement == null) {
                layoutSuppressions++
                if (label.level == ChartLabelLevel.P0) {
                    // Independent of the layout's intersection
                    // logic: a suppressed P0 must have NO
                    // fully-inside candidate (far ring gated on
                    // the anchor, as specified).
                    val anchorInside = anchorX in 0f..360f && anchorY in 0f..280f
                    val offsets = labelCandidateOffsetsDip(w)
                    var anyInside = false
                    for (candidate in offsets.indices) {
                        if (candidate >= 8 && !anchorInside) break
                        val x = anchorX + offsets[candidate].first
                        val y = anchorY + offsets[candidate].second
                        if (x >= 2f && y >= 2f && x + w <= 358f && y + h <= 278f) {
                            anyInside = true
                            break
                        }
                    }
                    if (anyInside) {
                        failures += "=== $id @ layout ===\n" +
                            "P0 SUPPRESSED WITH A FULLY-INSIDE CANDIDATE: " +
                            "label='${label.text}' anchor=($anchorX, $anchorY)"
                    }
                }
                return@forEachIndexed
            }
            layoutPlacements++
            if (placement.minOverlapFallback) layoutP0Fallbacks++
            if (!(placement.topLeftXDip >= 2f && placement.topLeftYDip >= 2f &&
                    placement.topLeftXDip + w <= 358f &&
                    placement.topLeftYDip + h <= 278f)
            ) {
                failures += "=== $id @ layout ===\n" +
                    "PLACED BOX NOT FULLY INSIDE: label='${label.text}' " +
                    "at (${placement.topLeftXDip}, ${placement.topLeftYDip}) " +
                    "size ($w, $h)"
            }
            if (placement.candidateIndex >= 8 &&
                !(anchorX in 0f..360f && anchorY in 0f..280f)
            ) {
                failures += "=== $id @ layout ===\n" +
                    "FAR RING USED FOR AN OFF-CANVAS ANCHOR: " +
                    "label='${label.text}' anchor=($anchorX, $anchorY) " +
                    "candidate=${placement.candidateIndex}"
            }
            for (canvas in canvases) {
                val decision = labelDrawDecision(
                    placement.topLeftXDip * canvas.density,
                    placement.topLeftYDip * canvas.density,
                    canvas.widthPx.toFloat(),
                    canvas.heightPx.toFloat(),
                )
                if (decision !is LabelDrawDecision.Draw) {
                    failures += "=== $id @ layout ===\n" +
                        "PLACED LABEL NOT DRAWN BY THE SAFETY LAYER: " +
                        "label='${label.text}' canvas=$canvas decision=$decision"
                }
            }
            placedBoxes += floatArrayOf(
                placement.topLeftXDip, placement.topLeftYDip, w, h,
            ) to label.text
        }
        // Pairwise clearance, x-sorted so the inner loop can stop
        // once no x-overlap with the current box is possible.
        val sorted = placedBoxes.sortedBy { it.first[0] }
        for (i in sorted.indices) {
            val a = sorted[i].first
            for (j in i + 1 until sorted.size) {
                val b = sorted[j].first
                if (b[0] >= a[0] + a[2] + 2f) break
                val overlapX = minOf(a[0] + a[2], b[0] + b[2] + 2f) -
                    maxOf(a[0], b[0] - 2f)
                val overlapY = minOf(a[1] + a[3], b[1] + b[3] + 2f) -
                    maxOf(a[1], b[1] - 2f)
                if (overlapX > 0f && overlapY > 0f) {
                    failures += "=== $id @ layout ===\n" +
                        "PLACED BOXES OVERLAP: '${sorted[i].second}' vs " +
                        "'${sorted[j].second}'"
                }
            }
        }
    }
}
