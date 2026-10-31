package app.astroexposure.ui

import app.astroexposure.data.catalog.JsonLensCatalog
import app.astroexposure.domain.camera.FramingChart
import app.astroexposure.domain.camera.FullFrameFraming
import app.astroexposure.domain.catalog.ConstellationEntry
import app.astroexposure.domain.catalog.DeepSkyObject
import app.astroexposure.domain.catalog.DeepSkyObjectType
import app.astroexposure.domain.catalog.MeteorShower
import app.astroexposure.domain.catalog.WideFieldViewpoint
import app.astroexposure.domain.ephemeris.EquatorialCoordinates
import app.astroexposure.domain.lens.FramingLensCandidates
import app.astroexposure.domain.lens.Lens
import app.astroexposure.domain.units.Degrees
import app.astroexposure.usecase.TonightOverviewUseCase.TonightSubject
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calculators' lens picker (ui-design §4, 0.14.0): lens
 * choice moved from the tonight detail dialog to the
 * calculators page, filtered by the focal basis — a handed-over
 * subject's recommended range, a point snapshot, or nothing.
 * The golden case is the one the design was stated with:
 * Lacerta (catalogue major axis 10°, reference 102 mm,
 * suggested range 67–137 mm) over the real built-in library.
 *
 * The picker's filtering, facet counts, ordering and landing
 * are pure functions, pinned here directly. The state machine
 * around them lives in composables a JVM test cannot invoke,
 * so its wiring is pinned at source level, like the other
 * source contracts in this package.
 */
class LensPickerTest {

    private val library: List<Lens> = JsonLensCatalog().builtInLenses()

    /** The Lacerta handover basis, derived exactly as the prefill composes it. */
    private fun lacertaBasis(): LensFocalBasis.Recommended {
        val advice = FullFrameFraming.advice(600.0) as FullFrameFraming.Advice.Range
        val selection = FramingLensCandidates.select(
            sizeMajorArcmin = 600.0,
            referenceFocalMm = advice.referenceFocalMm,
            sliderMinMm = 14.0,
            sliderMaxMm = 2000.0,
            lenses = library,
        )
        return LensFocalBasis.Recommended(
            subjectName = "Lacerta",
            referenceMm = advice.referenceFocalMm,
            lowMm = selection.intervalLowMm!!,
            highMm = selection.intervalHighMm!!,
        )
    }

    // -- The handover triple: same numbers as the step-1 grid -----

    @Test
    fun lacertaAdviceIsTheDesignedReferenceAndRange() {
        val advice = FullFrameFraming.advice(600.0) as FullFrameFraming.Advice.Range
        assertEquals(102, advice.referenceFocalMm.roundToInt())
        assertEquals(67, advice.lowFocalMm.roundToInt())
        assertEquals(137, advice.highFocalMm.roundToInt())
        val basis = lacertaBasis()
        // The selection's interval is the grid's printed range,
        // digit for digit — the picker and the grid share the
        // one derivation and cannot disagree.
        assertEquals(advice.lowFocalMm, basis.lowMm, 1e-9)
        assertEquals(advice.highFocalMm, basis.highMm, 1e-9)
        assertEquals(
            advice.referenceFocalMm,
            FramingChart.defaultFocalMm(advice)!!,
            1e-9,
        )
    }

    @Test
    fun cappedAtMaxSubjectDegradesToTheReferencePoint() {
        // A very small subject: the geometric range sits wholly
        // above the slider domain, so the selection has no
        // interval and the handover degrades to point coverage
        // of the capped reference (2000 mm).
        val advice = FullFrameFraming.advice(1.0)
        assertTrue(advice is FullFrameFraming.Advice.CappedAtMaxFocal)
        assertEquals(2000.0, FramingChart.defaultFocalMm(advice)!!, 1e-9)
        val selection = FramingLensCandidates.select(1.0, 2000.0, 14.0, 2000.0, library)
        assertNull(selection.intervalLowMm)
        assertNull(selection.intervalHighMm)
        // And the point basis at the cap honestly filters to
        // nothing in this library — the picker's empty state.
        assertTrue(
            lensCandidateUniverse(library, LensFocalBasis.Point(2000.0, "tiny")).isEmpty(),
        )
    }

    @Test
    fun noSizeSubjectHandsOverNoFocalTriple() {
        assertNull(FramingChart.defaultFocalMm(FullFrameFraming.Advice.NoSizeData))
    }

    // -- Recommended state: the Lacerta cascade -------------------

    @Test
    fun lacertaUniverseIsSeventeenWithTheDesignedFacetCounts() {
        val basis = lacertaBasis()
        val universe = lensCandidateUniverse(library, basis)
        assertEquals(17, universe.size)
        assertEquals(
            listOf("Sony E" to 9, "Canon RF" to 4, "Nikon Z" to 4),
            lensMountFacetCounts(universe),
        )
        val sonyE = filterLensFacets(universe, "Sony E", null)
        assertEquals(
            listOf(
                LensBrand.SONY to 3,
                LensBrand.SIGMA to 4,
                LensBrand.TAMRON to 2,
            ),
            lensBrandFacetCounts(sonyE),
        )
        // The Canon RF path's brand facet degenerates to the one
        // maker present — it is still a facet, not a special case.
        val canonRf = filterLensFacets(universe, "Canon RF", null)
        assertEquals(listOf(LensBrand.CANON to 4), lensBrandFacetCounts(canonRf))
    }

    @Test
    fun lacertaListOrdersByLogDistanceWithTheCoveringZoomFirst() {
        val basis = lacertaBasis()
        val all = lensPickerList(library, basis, null, null)
        assertEquals(17, all.size)
        assertEquals("Tamron 35-150mm F2-2.8 Di III VXD", all.first().name)
        assertEquals(
            0.0,
            FramingLensCandidates.distanceStops(all.first(), basis.referenceMm),
            1e-12,
        )
        // The Tamron-only end of the cascade: the covering zoom,
        // then the 28-75 (distance to its nearer endpoint).
        val tamron = lensPickerList(library, basis, "Sony E", LensBrand.TAMRON)
        assertEquals(
            listOf(
                "Tamron 35-150mm F2-2.8 Di III VXD",
                "Tamron 28-75mm F2.8 Di III VXD G2",
            ),
            tamron.map { it.name },
        )
    }

    // -- Free state ------------------------------------------------

    @Test
    fun freeStateUniverseIsTheWholeLibraryInLibraryOrder() {
        assertEquals(52, library.size)
        assertEquals(library, lensCandidateUniverse(library, LensFocalBasis.None))
        assertEquals(
            listOf("Sony E" to 30, "Canon RF" to 11, "Nikon Z" to 11),
            lensMountFacetCounts(library),
        )
        assertEquals(
            listOf(
                LensBrand.SONY to 12,
                LensBrand.CANON to 11,
                LensBrand.NIKON to 11,
                LensBrand.SIGMA to 11,
                LensBrand.TAMRON to 7,
            ),
            lensBrandFacetCounts(library),
        )
        val list = lensPickerList(library, LensFocalBasis.None, null, null)
        assertEquals(
            listOf(
                "Sony E 11mm F1.8",
                "Tamron 11-20mm F2.8 Di III-A RXD",
                "Sony FE 12-24mm F2.8 GM",
                "Sigma 14mm F1.4 DG DN Art",
            ),
            list.take(4).map { it.name },
        )
    }

    @Test
    fun pointBasisIsACoverageSnapshotOfItsOwnValue() {
        // The point basis filters by the value it was taken
        // with — the current focal length at tap time. Nothing
        // in the basis follows a later slider position: the
        // filter is a pure function of the snapshot alone.
        val at135 = LensFocalBasis.Point(135.0)
        val expected = library.filter { 135.0 >= it.focalMinMm && 135.0 <= it.focalMaxMm }
        assertEquals(expected, lensCandidateUniverse(library, at135))
        assertEquals(expected, lensCandidateUniverse(library, at135))
        assertTrue(expected.any { it.name == "Tamron 35-150mm F2-2.8 Di III VXD" })
        assertTrue(expected.none { it.focalMaxMm < 135.0 })
        assertTrue(expected.none { it.focalMinMm > 135.0 })
        // A different snapshot is a different universe.
        val at24 = lensCandidateUniverse(library, LensFocalBasis.Point(24.0))
        assertTrue(at24 != expected)
        assertTrue(at24.any { it.focalMinMm == 24.0 })
    }

    // -- Landing: one clamp rule in both states --------------------

    private fun landing(lens: Lens, basis: LensFocalBasis, currentFocalMm: Double): Double =
        FramingLensCandidates.pickFocalMm(
            lens,
            basis.referenceMmOrNull() ?: currentFocalMm,
        )

    @Test
    fun recommendedPicksLandOnTheReferenceClampedIntoTheLens() {
        val basis = lacertaBasis()
        val byName = library.associateBy { it.name }
        assertEquals(
            102,
            landing(byName.getValue("Tamron 35-150mm F2-2.8 Di III VXD"), basis, 50.0)
                .roundToInt(),
        )
        assertEquals(
            85,
            landing(byName.getValue("Sony FE 85mm F1.4 GM II"), basis, 50.0).roundToInt(),
        )
        assertEquals(
            70,
            landing(byName.getValue("Sony FE 24-70mm F2.8 GM II"), basis, 50.0)
                .roundToInt(),
        )
        assertEquals(
            75,
            landing(byName.getValue("Tamron 28-75mm F2.8 Di III VXD G2"), basis, 50.0)
                .roundToInt(),
        )
    }

    @Test
    fun freeStatePicksLandOnTheCurrentFocalClampedNotTheWideEnd() {
        // The landing rule is one for both states (0.14.0): with
        // no basis, a zoom pick lands on the CURRENT focal length
        // clamped into its segment. Before 0.14.0 the calculator
        // landed every pick on the zoom's wide end
        // (focalMinMm); this case pins the new rule at the point
        // where the two differ.
        val zoom = library.first { it.name == "Sony FE 24-70mm F2.8 GM II" }
        assertEquals(50.0, landing(zoom, LensFocalBasis.None, 50.0), 1e-9)
        assertEquals(70.0, landing(zoom, LensFocalBasis.None, 200.0), 1e-9)
        assertEquals(24.0, landing(zoom, LensFocalBasis.None, 14.0), 1e-9)
        val prime = library.first { it.name == "Sony FE 135mm F1.8 GM" }
        assertEquals(135.0, landing(prime, LensFocalBasis.None, 50.0), 1e-9)
    }

    // -- the calculator landing table ---------------------------

    private fun deepSky(type: DeepSkyObjectType): TonightSubject =
        TonightSubject.DeepSky(
            DeepSkyObject(
                id = "X", name = "X", nameZh = null, raHours = 0.0, decDeg = 0.0,
                magnitude = 5.0, sizeMajorArcmin = 10.0, sizeMinorArcmin = 5.0,
                type = type, constellation = "Lac",
                surfaceBrightness = null, surfaceBrightnessEstimated = false,
            ),
        )

    @Test
    fun calculatorLandingMatchesTheSpecTable() {
        // ui-design §7.8: constellations and wide-field
        // viewpoints land on the LANDSCAPE calculator (until
        // 0.14.0 the code sent them to the wide-field calculator
        // against the spec); deep-sky objects to deep-sky, a
        // radiant to the meteor calculator.
        for (type in DeepSkyObjectType.entries) {
            assertEquals(
                "deep-sky type $type",
                CalcCategory.DEEP_SKY,
                subjectCalcCategory(deepSky(type)),
            )
        }
        val constellation = TonightSubject.Constellation(
            ConstellationEntry(
                code = "Lac", centerRaHours = 22.5, centerDecDeg = 46.0,
                sizeMajorDeg = 10, sizeMinorDeg = 8, brightStars = emptyList(),
                noteZh = "注", noteEn = "note",
            ),
        )
        assertEquals(CalcCategory.LANDSCAPE, subjectCalcCategory(constellation))
        val viewpoint = TonightSubject.Viewpoint(
            WideFieldViewpoint(
                id = "cygnus", nameZh = "天鹅座银河段", nameEn = "Cygnus",
                anchorRaHours = 20.0, anchorDecDeg = 40.0,
                sizeMajorDeg = 25, sizeMinorDeg = 10, noteZh = "注", noteEn = "note",
            ),
        )
        assertEquals(CalcCategory.LANDSCAPE, subjectCalcCategory(viewpoint))
        val radiant = TonightSubject.Radiant(
            shower = MeteorShower(
                iauCode = "STA", nameEn = "Southern Taurids", nameZh = "南金牛座流星雨",
                activityStartLon = 0.0, activityEndLon = 0.0, peakLon = 0.0, peakZhr = 7,
                radiantRaHours = 3.0, radiantDecDeg = 14.0,
                driftRaPerDay = 0.0, driftDecPerDay = 0.0,
                parent = null, velocityKmS = 27.0, populationIndex = 2.3,
            ),
            radiant = EquatorialCoordinates(3.0, Degrees(14.0)),
        )
        assertEquals(CalcCategory.METEOR, subjectCalcCategory(radiant))
    }

    // -- State-machine wiring (source level) ------------------------

    private fun mainSource(file: String): String {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            for (
                base in listOf(
                    "app/src/main/kotlin/app/astroexposure/ui",
                    "src/main/kotlin/app/astroexposure/ui",
                )
            ) {
                val candidate = File(dir, "$base/$file")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
                ?: error("$file not found above ${System.getProperty("user.dir")}")
        }
    }

    @Test
    fun handoverSetsTheBasisAndFacetsButNeverPicks() {
        val source = mainSource("CalculatorsScreen.kt")
        val effect = source.substringAfter("LaunchedEffect(prefill)")
            .substringBefore("val recommendation")
        assertTrue(effect.contains("LensFocalBasis.Recommended(handed.name, reference, low, high)"))
        assertTrue(effect.contains("LensFocalBasis.Point(reference, subjectName = handed.name)"))
        assertTrue(effect.contains("else -> LensFocalBasis.None"))
        assertTrue(effect.contains("mountFacet = null"))
        assertTrue(effect.contains("brandFacet = null"))
        // The handover informs the picker; it does not pick —
        // the current lens and focal length are untouched.
        assertFalse("handover never assigns the lens", effect.contains("lens ="))
        assertFalse("handover never assigns the focal", effect.contains("focalMm ="))
    }

    @Test
    fun pickingKeepsTheRecommendedStateAlive() {
        val source = mainSource("CalculatorsScreen.kt")
        val pick = source.substringAfter("onLensPick = {")
            .substringBefore("onBasisChange")
        assertTrue(pick.contains("lens = picked"))
        assertTrue(pick.contains("focalMm = landingFocalMm"))
        // Q6: picking does not exit the recommended state — the
        // basis survives until the note row's clear, a facet
        // change, or the next handover replaces it.
        assertFalse("a pick never clears the basis", pick.contains("focalBasis"))
        // A manual category switch dismisses only the receipt
        // hint, never the basis.
        val chips = source.substringAfter("CalcCategory.entries.forEach")
            .substringBefore("handoverHint?.let")
        assertTrue(chips.contains("handoverHint = null"))
        assertFalse("a category switch never clears the basis", chips.contains("focalBasis"))
        // A camera change touches nothing else (the camera has
        // no mount to derive a facet from).
        assertTrue(source.contains("onCamera = { camera = it }"))
    }

    @Test
    fun sourceNoteRowIsTheOnlyBasisExitBesideTheChips() {
        val picker = mainSource("LensSelection.kt")
        // The recommended state's note row carries the clear
        // action back to the free state, facets untouched.
        assertTrue(picker.contains("onBasisChange(LensFocalBasis.None)"))
        assertTrue(picker.contains("R.string.calc_lens_basis_recommended"))
        assertTrue(picker.contains("R.string.calc_lens_basis_point_named"))
        // The free state's point chip snapshots the CURRENT
        // focal length at tap time.
        assertTrue(picker.contains("onBasisChange(LensFocalBasis.Point(currentFocalMm))"))
        // The empty universe offers the same exit.
        assertTrue(picker.contains("R.string.calc_lens_show_all"))
    }
}
