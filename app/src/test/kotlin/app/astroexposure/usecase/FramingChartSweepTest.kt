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
import app.astroexposure.ui.FramingVerdict
import app.astroexposure.ui.buildChartModel
import app.astroexposure.ui.framingSegments
import app.astroexposure.ui.framingVerdict
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.tan
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Standing full-subject sweep of the framing-chart path. Born as
 * the 0.11.1 reproduction sweep for the device crash on 0.11.0 and
 * kept as a regression: it runs the exact code path the framing
 * section renders — detail assembly (framingChartFor), the default
 * focal derivation, the log-slider mapping, framingVerdict and
 * buildChartModel — for EVERY constellation (88) and EVERY
 * deep-sky object (576), at the default state and at the control
 * extremes (focal 14 / 2000 mm, portrait, rotations 0 / 90 / 180).
 * Every failure is collected with its stack trace; the test fails
 * if the collection is non-empty.
 *
 * Since 0.11.1 it also asserts the line-clipping invariant: every
 * line point in every built model lies inside the field square
 * (|x|, |y| <= tan(field radius), the model layer's clip bound),
 * and it reports the largest pixel coordinate the lines would
 * produce on a 1080x840 canvas (the unclipped 0.11.0 model peaked
 * at 350,284 px).
 */
class FramingChartSweepTest {

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

    private val failures = mutableListOf<String>()
    private var subjectsSwept = 0
    private var chartsSwept = 0
    private var verdictCombos = 0
    private var modelCombos = 0
    private var maxLineAbsPx = 0.0

    private fun record(subjectId: String, stage: String, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        failures += "=== $subjectId @ $stage ===\n$sw"
    }

    private fun recordValue(subjectId: String, stage: String, what: String, value: Double) {
        failures += "=== $subjectId @ $stage ===\nNON-FINITE: $what = $value"
    }

    private fun checkFinite(subjectId: String, stage: String, what: String, value: Double) {
        if (!value.isFinite()) recordValue(subjectId, stage, what, value)
    }

    // The slider mapping is private in the section file; the sweep
    // reaches the SHIPPED functions by reflection rather than
    // re-implementing the formula (a copy could pass while the
    // shipped one crashes).
    private val sectionKt: Class<*> =
        Class.forName("app.astroexposure.ui.FramingChartSectionKt")
    private val focalToSliderMethod = sectionKt
        .getDeclaredMethod("focalToSlider", Double::class.javaPrimitiveType)
        .apply { isAccessible = true }
    private val sliderToFocalMethod = sectionKt
        .getDeclaredMethod("sliderToFocal", Float::class.javaPrimitiveType)
        .apply { isAccessible = true }

    private fun focalToSlider(focalMm: Double): Float =
        focalToSliderMethod.invoke(null, focalMm) as Float

    private fun sliderToFocal(position: Float): Double =
        sliderToFocalMethod.invoke(null, position) as Double

    @Test
    fun sweepEverySubjectThroughTheFramingPath() {
        val timeline = useCase.execute(alpharetta, date).timeline
        val constellationIds = JsonConstellationCatalog().constellations()
            .map { "constellation:${it.code}" }
        val objectIds = JsonObjectCatalog().allObjects().map { it.id }
        val allIds = constellationIds + objectIds
        println("SWEEP subjects: ${allIds.size} " +
            "(${constellationIds.size} constellations, ${objectIds.size} objects)")

        for (id in allIds) {
            sweepSubject(id, timeline)
        }

        val report = buildString {
            appendLine("subjects swept: $subjectsSwept")
            appendLine("charts swept: $chartsSwept")
            appendLine("verdict combos: $verdictCombos")
            appendLine("model combos: $modelCombos")
            appendLine("max line |px| at 1080x840: $maxLineAbsPx")
            appendLine("failures: ${failures.size}")
            appendLine()
            failures.forEach { appendLine(it) }
        }
        println(report.lineSequence().takeWhile { it.isNotEmpty() }.joinToString("\n"))
        assertTrue(
            "sweep found ${failures.size} failure(s); first:\n" +
                failures.firstOrNull()?.lineSequence()?.take(14)?.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    private fun sweepSubject(id: String, timeline: TonightOverviewUseCase.Timeline) {
        subjectsSwept++
        val detail = try {
            useCase.evaluateTargetAtCursor(alpharetta, date, instant, timeline, null, id)
        } catch (t: Throwable) {
            record(id, "evaluateTargetAtCursor", t)
            return
        }
        if (detail == null) {
            failures += "=== $id @ evaluateTargetAtCursor ===\nDETAIL NULL (id did not resolve)"
            return
        }
        val chart = detail.framingChart
        if (chart == null) {
            // A missing chart is legitimate only for a size-less
            // subject; anything else means the section silently
            // vanished (not the reported crash, but recorded).
            if (detail.framing !is FullFrameFraming.Advice.NoSizeData) {
                failures += "=== $id @ framingChartFor ===\n" +
                    "CHART NULL but framing advice is ${detail.framing}"
            }
            return
        }
        chartsSwept++
        val subjectMajorArcmin = detail.subject.sizeMajorDeg?.times(60.0) ?: 0.0

        // -- Default focal derivation + slider mapping -------------
        var defaultFocal: Double? = null
        try {
            defaultFocal = FramingChart.defaultFocalMm(detail.framing)
            if (defaultFocal == null) {
                failures += "=== $id @ defaultFocal ===\n" +
                    "NULL default focal though a chart exists (advice ${detail.framing})"
                return
            }
            checkFinite(id, "defaultFocal", "defaultFocalMm", defaultFocal)
            val fraction = focalToSlider(defaultFocal)
            if (!fraction.isFinite() || fraction < 0f || fraction > 1f) {
                recordValue(id, "focalToSlider", "slider fraction", fraction.toDouble())
            }
            checkFinite(id, "sliderToFocal(0)", "focal", sliderToFocal(0f))
            checkFinite(id, "sliderToFocal(1)", "focal", sliderToFocal(1f))
            checkFinite(id, "sliderToFocal(mid)", "focal", sliderToFocal(fraction))
        } catch (t: Throwable) {
            record(id, "defaultFocal/slider", t)
            return
        }

        // -- Verdict over the control extremes ----------------------
        val focals = listOf(defaultFocal, 14.0, 2000.0).distinct()
        for (focal in focals) {
            try {
                checkFinite(id, "field", "frameDiagonalDeg($focal)",
                    FramingChart.frameDiagonalDeg(focal))
                val field = FramingChart.fieldDiagonalDeg(focal)
                checkFinite(id, "field", "fieldDiagonalDeg($focal)", field)
                val projection = FramingChart.projectionFor(field)
                val radius = FramingChart.projectedFieldRadius(field, projection)
                checkFinite(id, "field", "projectedFieldRadius", radius)
                if (radius <= 0.0) {
                    recordValue(id, "field", "projectedFieldRadius (<= 0)", radius)
                }
                checkFinite(id, "quantities", "longSideFovDeg",
                    FullFrameFraming.longSideFovDeg(focal))
                checkFinite(id, "quantities", "shortSideFovDeg",
                    FullFrameFraming.shortSideFovDeg(focal))
                checkFinite(id, "quantities", "shareAtFocal",
                    FullFrameFraming.shareAtFocal(subjectMajorArcmin, focal))
            } catch (t: Throwable) {
                record(id, "field/quantities focal=$focal", t)
            }
            for (portrait in listOf(false, true)) {
                for (rotation in listOf(0.0, 90.0, 180.0)) {
                    try {
                        val spec = FramingChart.FrameSpec(focal, portrait, rotation)
                        val verdict = framingVerdict(chart, spec)
                        verdictCombos++
                        when (verdict) {
                            is FramingVerdict.Skeleton -> {
                                if (verdict.out.size > verdict.totalVertices) {
                                    failures += "=== $id @ verdict ===\n" +
                                        "OUT > TOTAL: ${verdict.out.size} > ${verdict.totalVertices}"
                                }
                                framingSegments(verdict.out) { star ->
                                    star.nameZh ?: star.properName ?: star.bayerName
                                }
                                framingSegments(verdict.out) { star ->
                                    star.properName ?: star.bayerName
                                }
                            }
                            is FramingVerdict.Ellipse -> Unit
                        }
                    } catch (t: Throwable) {
                        record(id, "verdict focal=$focal portrait=$portrait rot=$rotation", t)
                    }
                }
            }
            // -- Chart model (what the canvas consumes) -------------
            for (english in listOf(false, true)) {
                try {
                    val field = FramingChart.fieldDiagonalDeg(focal)
                    val verdict = framingVerdict(
                        chart, FramingChart.FrameSpec(focal, false, 0.0),
                    )
                    val model = buildChartModel(chart, field, verdict, english)
                    modelCombos++
                    val projection = FramingChart.projectionFor(field)
                    for (plot in model.stars) {
                        checkFinite(id, "model", "star x", plot.point.xEast)
                        checkFinite(id, "model", "star y", plot.point.yNorth)
                        val p = FramingChart.project(plot.point, projection)
                        checkFinite(id, "model", "projected star x", p.xEast)
                        checkFinite(id, "model", "projected star y", p.yNorth)
                    }
                    // The 0.11.1 clipping invariant: every line
                    // point lies inside the field square, and the
                    // pixel coordinates it maps to (the canvas's
                    // own place() math on 1080x840) stay bounded.
                    val halfSide = tan(Math.toRadians(field / 2.0))
                    val projectedRadius = FramingChart.projectedFieldRadius(field, projection)
                    val pxScale = hypot(1080.0, 840.0) / 2.0 / projectedRadius
                    for (line in model.lines) {
                        for (point in line.points) {
                            checkFinite(id, "model", "line x", point.xEast)
                            checkFinite(id, "model", "line y", point.yNorth)
                            if (abs(point.xEast) > halfSide + 1e-9 ||
                                abs(point.yNorth) > halfSide + 1e-9
                            ) {
                                failures += "=== $id @ model bounds ===\n" +
                                    "LINE POINT OUTSIDE FIELD SQUARE: " +
                                    "(${point.xEast}, ${point.yNorth}) " +
                                    "halfSide=$halfSide focal=$focal"
                            }
                            val p = FramingChart.project(point, projection)
                            val px = 540.0 - p.xEast * pxScale
                            val py = 420.0 - p.yNorth * pxScale
                            maxLineAbsPx = maxOf(maxLineAbsPx, abs(px), abs(py))
                        }
                    }
                    for (plot in model.outStars) {
                        checkFinite(id, "model", "out star x", plot.point.xEast)
                        checkFinite(id, "model", "out star y", plot.point.yNorth)
                    }
                    for (label in model.labels) {
                        checkFinite(id, "model", "label x", label.point.xEast)
                        checkFinite(id, "model", "label y", label.point.yNorth)
                    }
                } catch (t: Throwable) {
                    record(id, "model focal=$focal english=$english", t)
                }
            }
        }
    }
}
