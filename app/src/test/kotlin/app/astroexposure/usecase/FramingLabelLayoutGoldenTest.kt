package app.astroexposure.usecase

import app.astroexposure.data.catalog.JsonConstellationCatalog
import app.astroexposure.data.catalog.JsonConstellationLines
import app.astroexposure.data.catalog.JsonMeteorShowerCatalog
import app.astroexposure.data.catalog.JsonObjectCatalog
import app.astroexposure.data.catalog.JsonStarCatalog
import app.astroexposure.data.catalog.JsonWideFieldCatalog
import app.astroexposure.data.ephemeris.AstronomyEngineEphemeris
import app.astroexposure.domain.camera.FramingChart
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.units.Degrees
import app.astroexposure.ui.ChartLabelLevel
import app.astroexposure.ui.ChartModel
import app.astroexposure.ui.LabelPlacement
import app.astroexposure.ui.buildChartModel
import app.astroexposure.ui.framingVerdict
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden cases for the framing chart's label avoidance layout
 * (ui-design §7.10, 0.13.1), evaluated through the shipped path —
 * the real catalogues, the real detail evaluation, the shipped
 * layout function — on the 360x280 dp reference canvas, with the
 * fixture measurement of [approximateLabelSizeDip] injected (the
 * design probe's approximate text model; production measures
 * with the real TextMeasurer, so individual placements can drift
 * slightly by font metrics — the rules and the invariants below
 * are what is pinned, plus the placements this measurement
 * produces).
 *
 * Drift against the 0.13.1 design probe's hand-run numbers,
 * recorded per the batch rule (rules unchanged; placements
 * follow the measurement): the probe counted Orion 60 placed /
 * 54 suppressed and Cygnus 39 / 17; this measurement places
 * 53 / 61 and 37 / 19. The difference is marginal contention in
 * the P2 tail. In Orion's sword the probe had M42 at the default
 * candidate and M43 displaced; here Rigel's placed box (P1,
 * placed earlier) overlaps M42's default box by 0.3 dp under
 * this measurement, so M42 takes candidate 1 and M43 keeps
 * candidate 0 — both placed, both clear, the belt three all
 * placed with Mintaka rescued by the far ring (candidate 8,
 * exactly as the probe had it). M31 and Lyra match the probe
 * case for case.
 */
class FramingLabelLayoutGoldenTest {

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

    private class Case(
        val model: ChartModel,
        val placements: List<LabelPlacement?>,
        val fieldDiagonalDeg: Double,
        val projection: FramingChart.Projection,
    )

    private fun layoutCase(
        objectId: String,
        focalMm: Double,
        portrait: Boolean,
        rotationDeg: Double,
        english: Boolean,
    ): Case {
        val timeline = useCase.execute(alpharetta, date).timeline
        val detail = useCase.evaluateTargetAtCursor(
            alpharetta, date, instant, timeline, null, objectId,
        ) ?: error("no detail for $objectId")
        val chart = detail.framingChart ?: error("no chart for $objectId")
        val field = FramingChart.fieldDiagonalDeg(focalMm)
        val verdict = framingVerdict(
            chart, FramingChart.FrameSpec(focalMm, portrait, rotationDeg),
        )
        val model = buildChartModel(chart, field, verdict, english)
        val projection = FramingChart.projectionFor(field)
        return Case(model, layoutForModel(model, field, projection), field, projection)
    }

    private fun Case.placementOf(text: String): LabelPlacement? {
        val index = model.labels.indexOfFirst { it.text == text }
        check(index >= 0) { "no label '$text' among ${model.labels.map { it.text }}" }
        return placements[index]
    }

    private fun Case.placementByPrefix(prefix: String): LabelPlacement? {
        val index = model.labels.indexOfFirst { it.text.startsWith(prefix) }
        check(index >= 0) { "no label starting '$prefix'" }
        return placements[index]
    }

    private fun Case.anchorOf(text: String): Pair<Float, Float> {
        val index = model.labels.indexOfFirst { it.text == text }
        check(index >= 0) { "no label '$text'" }
        return labelAnchorDip(model, index, fieldDiagonalDeg, projection, 360f, 280f)
    }

    /**
     * The layout invariants (§7.10): every placed box lies fully
     * inside the canvas with its margin, no two placed boxes
     * overlap (clearance included), the far ring only serves
     * on-canvas anchors, and the minimum-overlap fallback never
     * fires in the golden cases.
     */
    private fun Case.assertInvariants() {
        val boxes = mutableListOf<Triple<String, FloatArray, Int>>()
        model.labels.forEachIndexed { index, label ->
            val placement = placements[index] ?: return@forEachIndexed
            val (w, h) = approximateLabelSizeDip(label.text)
            assertTrue(
                "placed box inside canvas: '${label.text}'",
                placement.topLeftXDip >= 2f && placement.topLeftYDip >= 2f &&
                    placement.topLeftXDip + w <= 358f &&
                    placement.topLeftYDip + h <= 278f,
            )
            assertTrue(
                "no P0 fallback in golden cases: '${label.text}'",
                !placement.minOverlapFallback,
            )
            if (placement.candidateIndex >= 8) {
                val (ax, ay) = labelAnchorDip(
                    model, index, fieldDiagonalDeg, projection, 360f, 280f,
                )
                assertTrue(
                    "far ring only for on-canvas anchors: '${label.text}'",
                    ax in 0f..360f && ay in 0f..280f,
                )
            }
            boxes += Triple(
                label.text,
                floatArrayOf(placement.topLeftXDip, placement.topLeftYDip, w, h),
                index,
            )
        }
        for (a in boxes) {
            for (b in boxes) {
                if (a.third >= b.third) continue
                val overlapX = minOf(a.second[0] + a.second[2], b.second[0] + b.second[2] + 2f) -
                    maxOf(a.second[0], b.second[0] - 2f)
                val overlapY = minOf(a.second[1] + a.second[3], b.second[1] + b.second[3] + 2f) -
                    maxOf(a.second[1], b.second[1] - 2f)
                assertTrue(
                    "placed boxes clear: '${a.first}' vs '${b.first}'",
                    !(overlapX > 0f && overlapY > 0f),
                )
            }
        }
    }

    @Test
    fun orionAtFiftyMillimetres() {
        val case = layoutCase("constellation:Ori", 50.0, false, 0.0, english = false)
        assertEquals(114, case.model.labels.size)
        assertEquals(53, case.placements.count { it != null })
        // The two verdict-named out stars (P0) are both placed.
        val p0 = case.model.labels.withIndex().filter { it.value.level == ChartLabelLevel.P0 }
        assertEquals(2, p0.size)
        assertTrue(p0.all { case.placements[it.index] != null })
        // The belt three all survive: Alnilam at the default
        // candidate, Alnitak displaced left, Mintaka rescued by
        // the far ring — without it Mintaka would be suppressed.
        assertEquals(0, case.placementOf("Alnilam")?.candidateIndex)
        assertEquals(1, case.placementOf("Alnitak")?.candidateIndex)
        assertEquals(8, case.placementOf("Mintaka")?.candidateIndex)
        // The sword: M42 and M43 both placed (see the class doc
        // for why their candidates are swapped against the
        // design probe), the four NGC neighbours suppressed.
        assertEquals(1, case.placementByPrefix("M42")?.candidateIndex)
        assertEquals(0, case.placementByPrefix("M43")?.candidateIndex)
        for (prefix in listOf("NGC 1973", "NGC 1975", "NGC 1980", "NGC 1981")) {
            assertNull("$prefix suppressed", case.placementByPrefix(prefix))
        }
        // The three suppressed P1 stars are exactly the ones
        // whose anchors (and star points) are off the canvas.
        val suppressedP1 = case.model.labels.withIndex().filter {
            it.value.level == ChartLabelLevel.P1 && case.placements[it.index] == null
        }
        assertEquals(3, suppressedP1.size)
        for ((index, label) in suppressedP1) {
            val (ax, ay) = labelAnchorDip(
                case.model, index, case.fieldDiagonalDeg, case.projection, 360f, 280f,
            )
            assertTrue(
                "suppressed P1 anchor off canvas: '${label.text}'",
                ax !in 0f..360f || ay !in 0f..280f,
            )
        }
        case.assertInvariants()
    }

    @Test
    fun m31NeighboursKeepTheirDefaultSpots() {
        for ((portrait, rotation, english) in listOf(
                Triple(false, 0.0, true),
                Triple(true, 35.0, false),
            )) {
            val case = layoutCase("M31", 348.0, portrait, rotation, english)
            assertEquals(3, case.model.labels.size)
            // M32 / M110 do not move a single dp: candidate 0 at
            // the pre-0.13.1 anchor offset (6, -14).
            for (text in listOf("M32", "M110")) {
                val placement = case.placementByPrefix(text)
                assertNotNull("$text placed", placement)
                placement!!
                assertEquals(0, placement.candidateIndex)
                val (ax, ay) = case.anchorOf(
                    case.model.labels.first { it.text.startsWith(text) }.text,
                )
                assertEquals(ax + 6f, placement.topLeftXDip, 0.01f)
                assertEquals(ay - 14f, placement.topLeftYDip, 0.01f)
            }
            // NGC 185's anchor sits 88 dp above the canvas: no
            // candidate reaches it, so it is not presented — but
            // its name stays in the description's neighbour set.
            assertNull(case.placementByPrefix("NGC 185"))
            assertTrue(case.model.neighbourNames.contains("NGC 185"))
            case.assertInvariants()
        }
    }

    @Test
    fun cygnusAtEightyFiveMillimetres() {
        val case = layoutCase("constellation:Cyg", 85.0, false, 0.0, english = false)
        assertEquals(56, case.model.labels.size)
        assertEquals(37, case.placements.count { it != null })
        // All four verdict-named out stars (P0) at the default.
        val p0 = case.model.labels.withIndex().filter { it.value.level == ChartLabelLevel.P0 }
        assertEquals(4, p0.size)
        assertTrue(p0.all { case.placements[it.index]?.candidateIndex == 0 })
        // The two suppressed P1 stars are off-canvas anchors.
        val suppressedP1 = case.model.labels.withIndex().filter {
            it.value.level == ChartLabelLevel.P1 && case.placements[it.index] == null
        }
        assertEquals(2, suppressedP1.size)
        // M27 / M57 sort early among P2 (Messier first) yet are
        // suppressed geometrically: their anchors are off the
        // canvas, so no candidate can present them.
        assertNull(case.placementByPrefix("M27"))
        assertNull(case.placementByPrefix("M57"))
        case.assertInvariants()
    }

    @Test
    fun lyraAt101MillimetresTheScreenshotCase() {
        val case = layoutCase("constellation:Lyr", 101.0, false, 0.0, english = true)
        assertEquals(18, case.model.labels.size)
        assertEquals(12, case.placements.count { it != null })
        // Vega at the default candidate; the one displacement is
        // NGC 6830 moving below its anchor, away from NGC 6823.
        assertEquals(0, case.placementOf("Vega")?.candidateIndex)
        assertEquals(2, case.placementByPrefix("NGC 6830")?.candidateIndex)
        assertEquals(0, case.placementByPrefix("NGC 6823")?.candidateIndex)
        // NGC 6871's anchor is 2.3 dp past the left edge, yet its
        // default box lands fully on the canvas — the whole word
        // shows (the pre-0.13.1 chart clipped it).
        val ngc6871 = case.placementByPrefix("NGC 6871")
        assertNotNull(ngc6871)
        assertEquals(0, ngc6871!!.candidateIndex)
        assertTrue(ngc6871.topLeftXDip >= 2f)
        // Every label whose anchor is on the canvas is placed;
        // the six suppressed labels are exactly the ones the
        // screenshot showed colliding or clipped, all with
        // anchors off the canvas (NGC 6871 above is the one
        // exception that proves the rule: anchor 2.3 dp out, yet
        // placed, because its box lands inside).
        case.model.labels.forEachIndexed { index, label ->
            val (ax, ay) = labelAnchorDip(
                case.model, index, case.fieldDiagonalDeg, case.projection, 360f, 280f,
            )
            if (ax in 0f..360f && ay in 0f..280f) {
                assertNotNull("on-canvas anchor placed: '${label.text}'", case.placements[index])
            }
        }
        val suppressed = case.model.labels.withIndex()
            .filter { case.placements[it.index] == null }
            .map { it.value.text }
        assertEquals(6, suppressed.size)
        for (prefix in listOf("M27", "IC 4996", "NGC 6811", "NGC 6866", "NGC 6883", "NGC 6888")) {
            assertTrue(
                "$prefix among the suppressed",
                suppressed.any { it.startsWith(prefix) },
            )
        }
        case.assertInvariants()
    }
}
