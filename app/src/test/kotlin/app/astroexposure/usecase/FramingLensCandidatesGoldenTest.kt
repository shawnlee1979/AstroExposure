package app.astroexposure.usecase

import app.astroexposure.data.catalog.JsonLensCatalog
import app.astroexposure.domain.camera.FullFrameFraming
import app.astroexposure.domain.lens.FramingLensCandidates
import app.astroexposure.domain.lens.Lens
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The candidate rule against the REAL built-in library
 * (ui-design §7.10, 0.13.0), on the two subjects the rule was
 * stated with:
 *
 * - Triangulum (catalogue major axis 8°, suggested 85–171 mm,
 *   reference 128 mm): exactly ten lenses qualify, in closeness
 *   order — the 11 mm ultra-wide that prompted the feedback is
 *   in the library but is not a candidate.
 * - M31 (suggested range beyond every built-in focal): the
 *   candidate set is empty — and a user's own 100-400 mm custom
 *   lens, which covers the 348 mm reference, enters at
 *   distance 0.
 */
class FramingLensCandidatesGoldenTest {

    private val library = JsonLensCatalog().builtInLenses()

    private fun select(sizeArcmin: Double, lenses: List<Lens>): FramingLensCandidates.Selection {
        val advice = FullFrameFraming.advice(sizeArcmin) as FullFrameFraming.Advice.Range
        return FramingLensCandidates.select(
            sizeMajorArcmin = sizeArcmin,
            referenceFocalMm = advice.referenceFocalMm,
            sliderMinMm = 14.0,
            sliderMaxMm = 2000.0,
            lenses = lenses,
        )
    }

    @Test
    fun `triangulum candidates are the designed ten in closeness order`() {
        val selection = select(480.0, library)
        assertEquals(85, selection.intervalLowMm!!.roundToInt())
        assertEquals(171, selection.intervalHighMm!!.roundToInt())
        assertEquals(
            listOf(
                "Tamron 35-150mm F2-2.8 Di III VXD",
                "Canon RF 135mm F1.8L IS USM",
                "Nikon NIKKOR Z 135mm F1.8 S Plena",
                "Sigma 135mm F1.8 DG HSM Art",
                "Sony FE 135mm F1.8 GM",
                "Canon RF 85mm F1.2L USM",
                "Nikon NIKKOR Z 85mm F1.2 S",
                "Nikon NIKKOR Z 85mm F1.8 S",
                "Sigma 85mm F1.4 DG DN Art",
                "Sony FE 85mm F1.4 GM II",
            ),
            selection.candidates.map { it.lens.name },
        )
        assertEquals(0.0, selection.candidates.first().distanceStops, 1e-12)
        // The 11 mm ultra-wide exists in the library; the band excludes it.
        assertTrue(library.any { it.focalMinMm == 11.0 })
        assertTrue(selection.candidates.none { it.lens.focalMaxMm < 35.0 })
    }

    @Test
    fun `picking the covering zoom lands on the reference focal`() {
        val advice = FullFrameFraming.advice(480.0) as FullFrameFraming.Advice.Range
        val tamron = library.first { it.id == "tamron-35-150mm-f2-2.8-di-iii-vxd" }
        assertEquals(
            advice.referenceFocalMm,
            FramingLensCandidates.pickFocalMm(tamron, advice.referenceFocalMm),
            1e-9,
        )
        assertEquals(128, advice.referenceFocalMm.roundToInt())
    }

    @Test
    fun `m31 candidates are empty with the built-in library alone`() {
        val selection = select(177.0, library)
        assertTrue(selection.candidates.isEmpty())
        // The interval itself stands (roughly 233–466 mm): the
        // empty presentation cites it, it does not fall back.
        assertEquals(233, selection.intervalLowMm!!.roundToInt())
        assertEquals(466, selection.intervalHighMm!!.roundToInt())
    }

    @Test
    fun `a custom tele zoom covering the reference enters at distance zero`() {
        val custom = Lens(
            id = "custom-00000000-0000-0000-0000-000000000000",
            name = "Sony FE 100-400mm F4.5-5.6 GM OSS",
            mount = "Sony E",
            focalMinMm = 100.0,
            focalMaxMm = 400.0,
            maxAperture = 4.5,
            maxApertureAtMaxFocal = 5.6,
        )
        val selection = select(177.0, library + custom)
        assertEquals(listOf(custom.id), selection.candidates.map { it.lens.id })
        assertEquals(0.0, selection.candidates.single().distanceStops, 1e-12)
    }
}
