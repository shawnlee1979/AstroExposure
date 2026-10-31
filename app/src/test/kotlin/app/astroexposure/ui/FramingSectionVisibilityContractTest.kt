package app.astroexposure.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The framing-chart section's visible-text trim (ui-design
 * §7.10, 0.13.2, on-device feedback): five visible elements
 * are gone — the section title, the purpose line, the caliber
 * line above the chart, the verdict and quantities lines
 * under it, and the candidate row's header line. Only the
 * visible layer was trimmed: the chart canvas description
 * still composes the verdict, the quantities and the
 * neighbour names in full, so the information layer is
 * untouched. The cases below pin, at source level (the
 * section is a composable a JVM test cannot invoke), that
 * none of the five returns to the visible layer, that the
 * retired string keys stay retired, and that the strings the
 * description still needs stay in the resources.
 */
class FramingSectionVisibilityContractTest {

    private fun repoFile(vararg parts: String): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            for (base in listOf("app", "")) {
                var candidate = if (base.isEmpty()) dir else File(dir, base)
                for (part in parts) candidate = File(candidate, part)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
                ?: error("${parts.last()} not found above ${System.getProperty("user.dir")}")
        }
    }

    private fun sectionSource(): String = repoFile(
        "src", "main", "kotlin", "app", "astroexposure", "ui",
        "FramingChartSection.kt",
    ).readText()

    private fun strings(localeDir: String): Map<String, String> {
        val text = repoFile("src", "main", "res", localeDir, "strings.xml").readText()
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun retiredHeaderKeysStayOutOfBothLocales() {
        for (locale in listOf("values", "values-en")) {
            val strings = strings(locale)
            for (key in listOf(
                "framing_title",
                "framing_purpose",
                "framing_caliber",
                "framing_candidates_note",
            )) {
                assertFalse("$key removed ($locale)", strings.containsKey(key))
            }
        }
    }

    @Test
    fun descriptionStringsStayInBothLocales() {
        for (locale in listOf("values", "values-en")) {
            val strings = strings(locale)
            assertTrue(strings.containsKey("framing_quantities"))
            assertTrue(strings.containsKey("framing_projection_gnomonic"))
            assertTrue(strings.containsKey("framing_projection_stereographic"))
            assertTrue(strings.containsKey("framing_same_field"))
        }
    }

    @Test
    fun sectionSourceHasNoRetiredVisibleLines() {
        val source = sectionSource()
        for (ref in listOf(
            "R.string.framing_title",
            "R.string.framing_purpose",
            "R.string.framing_caliber",
            "R.string.framing_candidates_note",
        )) {
            assertFalse("$ref not referenced", source.contains(ref))
        }
        assertFalse(
            "no visible verdict Text under the chart",
            source.contains("text = conclusion,"),
        )
        assertFalse(
            "no visible quantities Text under the chart",
            source.contains("text = quantities,"),
        )
    }

    @Test
    fun canvasDescriptionStillCarriesVerdictQuantitiesAndNeighbours() {
        val source = sectionSource()
        assertTrue(
            source.contains("conclusion, quantities, neighbourDescription"),
        )
    }

    /**
     * Lens picking left the section entirely (0.14.0): the
     * candidate row, the all-lenses panel and the readout's
     * lens-name suffix are gone — lens choice lives on the
     * calculators page now (see LensPickerTest for its pins).
     * What stays is the pure composition what-if: chart, focal
     * readout and slider, rotation, orientation.
     */
    @Test
    fun sectionHasNoLensPickingLeft() {
        val source = sectionSource()
        for (remnant in listOf(
            "AllLensesPanel",
            "candidateEmptyText",
            "selectedLensId",
            "showAllLenses",
            "framing_all_lenses",
            "FramingLensCandidates",
        )) {
            assertFalse("$remnant removed from the section", source.contains(remnant))
        }
        assertTrue(source.contains("FramingChartCanvas("))
        assertTrue(source.contains("focalReadout(advice, focalMm)"))
        assertTrue(source.contains("FocalScaleTicks()"))
        assertTrue(source.contains("R.string.framing_angle_label"))
    }

    @Test
    fun retiredPanelKeysStayOutOfBothLocales() {
        for (locale in listOf("values", "values-en")) {
            val strings = strings(locale)
            for (key in listOf(
                "framing_all_lenses",
                "framing_all_lenses_mount",
                "framing_all_lenses_brand",
                "framing_facet_all",
                "framing_candidates_empty_range",
                "framing_candidates_empty_capped_max",
                "framing_candidates_empty_capped_min",
                "framing_lens_spec_prime",
                "framing_lens_spec_zoom",
                "framing_lens_spec_zoom_range",
            )) {
                assertFalse("$key removed ($locale)", strings.containsKey(key))
            }
            // The lens spec wording lives on under the
            // calculators' prefix, text unchanged.
            assertTrue(strings.containsKey("calc_lens_spec_prime"))
            assertTrue(strings.containsKey("calc_lens_spec_zoom"))
            assertTrue(strings.containsKey("calc_lens_spec_zoom_range"))
        }
    }
}
