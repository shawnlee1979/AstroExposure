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
import app.astroexposure.ui.LabelDrawDecision
import app.astroexposure.ui.buildChartModel
import app.astroexposure.ui.framingVerdict
import app.astroexposure.ui.labelDrawDecision
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.hypot
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
}
