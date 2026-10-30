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
import app.astroexposure.ui.framingSegments
import app.astroexposure.ui.framingVerdict
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The framing chart's golden cases (ui-design §7.10), evaluated
 * through the shipped path — the real catalogues, the real detail
 * evaluation, and the same verdict helper the section renders:
 *
 * - Orion at 50 mm landscape: the club stars chi-1 / chi-2 and two
 *   unnamed skeleton stars fall out the north edge; portrait fits
 *   the whole figure, so the flip hint applies.
 * - M31 at 348 mm: share 50%, the catalogue ellipse (position angle
 *   35 degrees) fits aligned in portrait and unrotated in landscape;
 *   M32 and M110 are in the chart as neighbours.
 * - Cygnus at 85 mm landscape: Albireo out south, kappa / iota-2
 *   out north, zeta out south; neither orientation fits, so no
 *   flip hint. Zeta's edge is the measured one: the design sample
 *   board called it the east edge, but zeta sits 11.3 degrees south
 *   of the chart centre against an 8.1-degree frame half height —
 *   exact geometry puts it out the south edge, and the out SET
 *   (the four named stars) agrees with the sample.
 *
 * Counting note: the samples speak of Orion's "28 vertices, 24 in
 * frame" — that counts polyline vertex ENTRIES (28 stored, the 4
 * out stars among them). The domain judges unique stars: 23 vertex
 * stars, 4 out, 19 in. Both counts are pinned where each belongs:
 * entries at the data level below, stars in the verdicts.
 */
class FramingChartGoldenTest {

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

    private fun detail(objectId: String): TonightOverviewUseCase.TargetDetail {
        val timeline = useCase.execute(alpharetta, date).timeline
        return useCase.evaluateTargetAtCursor(
            alpharetta, date, instant, timeline, null, objectId,
        ) ?: error("no detail for $objectId")
    }

    @Test
    fun orionSkeletonAtFiftyMillimetres() {
        val detail = detail("constellation:Ori")
        val chart = detail.framingChart
        assertNotNull(chart)
        chart!!
        assertEquals("Ori", chart.skeletonCode)
        assertNull(chart.ellipse)
        // The data-level count: 28 stored vertex entries.
        assertEquals(28, chart.lines.getValue("Ori").sumOf { it.size })

        val verdict = framingVerdict(
            chart, FramingChart.FrameSpec(50.0, portrait = false, positionAngleDeg = 0.0),
        ) as FramingVerdict.Skeleton
        assertEquals(23, verdict.totalVertices)
        assertEquals(4, verdict.out.size)
        val named = verdict.out.mapNotNull { it.item.properName ?: it.item.bayerName }
        assertEquals(setOf("χ¹", "χ²"), named.toSet())
        assertEquals(2, verdict.out.size - named.size)
        assertTrue(verdict.out.all { it.edge == FramingChart.Edge.NORTH })
        assertTrue(verdict.fitsAfterSwap)

        // The conclusion's segment assembly: one north segment,
        // the two club stars named, the two unnamed counted.
        val segments = framingSegments(verdict.out) { star ->
            star.nameZh ?: star.properName ?: star.bayerName
        }
        assertEquals(1, segments.segments.size)
        assertEquals(FramingChart.Edge.NORTH, segments.segments[0].edge)
        assertEquals(setOf("χ¹", "χ²"), segments.segments[0].names.toSet())
        assertEquals(2, segments.segments[0].unnamedCount)
        assertNull(segments.hiddenTotal)

        // The default focal is the grid's own reference focal.
        val advice = detail.framing as FullFrameFraming.Advice.Range
        assertEquals(49.5, advice.referenceFocalMm, 0.75)
        assertEquals(
            advice.referenceFocalMm,
            FramingChart.defaultFocalMm(detail.framing)!!,
            0.0,
        )
    }

    @Test
    fun m31EllipseAt348() {
        val detail = detail("M31")
        val chart = detail.framingChart
        assertNotNull(chart)
        chart!!
        val ellipse = chart.ellipse
        assertNotNull(ellipse)
        ellipse!!
        assertEquals(177.83, ellipse.majorArcmin, 0.02)
        assertEquals(69.66, ellipse.minorArcmin!!, 0.02)
        assertEquals(35.0, ellipse.positionAngleDeg!!, 0.0)
        assertEquals("And", chart.emphasisCode)
        assertTrue(chart.neighbours.any { it.id == "M32" })
        assertTrue(chart.neighbours.any { it.id == "M110" })

        val aligned = framingVerdict(
            chart, FramingChart.FrameSpec(348.0, portrait = true, positionAngleDeg = 35.0),
        ) as FramingVerdict.Ellipse
        assertTrue(aligned.verdict.fits)
        val plain = framingVerdict(
            chart, FramingChart.FrameSpec(348.0, portrait = false, positionAngleDeg = 0.0),
        ) as FramingVerdict.Ellipse
        assertTrue(plain.verdict.fits)

        val share = FullFrameFraming.shareAtFocal(177.83, 348.0)
        assertTrue("share $share", share in 0.495..0.505)
        val advice = detail.framing as FullFrameFraming.Advice.Range
        assertEquals(347.7, advice.referenceFocalMm, 1.5)
    }

    @Test
    fun cygnusSkeletonAtEightyFiveMillimetres() {
        val detail = detail("constellation:Cyg")
        val chart = detail.framingChart
        assertNotNull(chart)
        chart!!
        val verdict = framingVerdict(
            chart, FramingChart.FrameSpec(85.0, portrait = false, positionAngleDeg = 0.0),
        ) as FramingVerdict.Skeleton
        assertEquals(9, verdict.totalVertices)
        assertEquals(4, verdict.out.size)
        val byName = verdict.out.associate {
            (it.item.properName ?: it.item.bayerName) to it.edge
        }
        assertEquals(
            mapOf(
                "Albireo" to FramingChart.Edge.SOUTH,
                "κ" to FramingChart.Edge.NORTH,
                "ι²" to FramingChart.Edge.NORTH,
                "ζ" to FramingChart.Edge.SOUTH,
            ),
            byName,
        )
        assertFalse(verdict.fitsAfterSwap)
        val share = FullFrameFraming.shareAtFocal(38.0 * 60.0, 85.0)
        assertTrue("share $share", share in 1.57..1.61)
    }

    @Test
    fun subjectsWithoutAChartGetNone() {
        assertNull(detail("viewpoint:galactic-centre").framingChart)
        assertNull(detail("shower:STA").framingChart)
        val sizeless = detail("M40")
        assertNull(sizeless.framingChart)
        assertTrue(sizeless.framing is FullFrameFraming.Advice.NoSizeData)
    }

    @Test
    fun shippedDataProductsCarryThePinnedFacts() {
        val stars = JsonStarCatalog().stars()
        assertEquals(8920, stars.size)
        val betelgeuse = stars.first { it.properName == "Betelgeuse" }
        assertEquals(88.7925, betelgeuse.raDeg, 0.01)
        assertEquals(7.4071, betelgeuse.decDeg, 0.01)
        assertEquals("α", betelgeuse.bayerName)
        assertEquals("参宿四", betelgeuse.nameZh)

        val lines = JsonConstellationLines().lines()
        assertEquals(88, lines.size)
        assertEquals(28, lines.getValue("Ori").sumOf { it.size })
        // Serpens arrives as two upstream features and must be merged.
        assertTrue(lines.getValue("Ser").size >= 2)

        val m31 = JsonObjectCatalog().allObjects().first { it.id == "M31" }
        assertEquals(35.0, m31.positionAngleDeg!!, 0.0)
    }
}
