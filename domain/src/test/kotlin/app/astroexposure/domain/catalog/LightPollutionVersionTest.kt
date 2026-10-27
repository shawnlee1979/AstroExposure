package app.astroexposure.domain.catalog

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Short-display formatting for the light-pollution grid header
 * (ui-design §7.2): both header forms the data pipeline writes —
 * full-grid and sparse-sample — plus the missing-field fallbacks.
 */
class LightPollutionVersionTest {

    private val fullRaw =
        "Falchi et al. 2016 World Atlas via GFZ doi:10.5880/GFZ.1.4.2016.001 (resampled 2026-10-27)"
    private val sparseRaw =
        "Falchi et al. 2016 World Atlas values sampled via lightpollutionmap.info on 2026-09-30"

    @Test
    fun fullGridHeaderParsesAndFormatsInBothLanguages() {
        val version = LightPollutionVersionFormat.parse(fullRaw, "full-grid")
        assertEquals(GridKind.FULL_GRID, version.gridKind)
        assertEquals(2016, version.atlasYear)
        assertEquals(LocalDate.of(2026, 10, 27), version.builtDate)
        assertEquals(
            "Falchi 2016 图谱 · 全量网格 2026-10-27",
            LightPollutionVersionFormat.shortDisplay(version, english = false),
        )
        assertEquals(
            "Falchi 2016 atlas · Full grid 2026-10-27",
            LightPollutionVersionFormat.shortDisplay(version, english = true),
        )
    }

    @Test
    fun sparseSampleHeaderParsesAndFormatsInBothLanguages() {
        val version = LightPollutionVersionFormat.parse(sparseRaw, "sparse-sample")
        assertEquals(GridKind.SPARSE_SAMPLE, version.gridKind)
        assertEquals(2016, version.atlasYear)
        assertEquals(LocalDate.of(2026, 9, 30), version.builtDate)
        assertEquals(
            "Falchi 2016 图谱 · 样本网格 2026-09-30",
            LightPollutionVersionFormat.shortDisplay(version, english = false),
        )
        assertEquals(
            "Falchi 2016 atlas · Sample grid 2026-09-30",
            LightPollutionVersionFormat.shortDisplay(version, english = true),
        )
    }

    @Test
    fun shortDisplayFollowsHeaderFieldsInsteadOfFixedText() {
        // A later rebuild of the same atlas changes only the date.
        val rebuilt = LightPollutionVersionFormat.parse(
            fullRaw.replace("2026-10-27", "2027-01-05"), "full-grid",
        )
        assertEquals(
            "Falchi 2016 图谱 · 全量网格 2027-01-05",
            LightPollutionVersionFormat.shortDisplay(rebuilt, english = false),
        )
    }

    @Test
    fun missingFieldsYieldNoShortDisplay() {
        // A header without a grid-kind token (the test fixture form).
        val noKind = LightPollutionVersionFormat.parse("test-fixture-1", null)
        assertNull(noKind.gridKind)
        assertNull(LightPollutionVersionFormat.shortDisplay(noKind, english = false))
        // Unknown grid-kind tokens are not guessed.
        val unknown = LightPollutionVersionFormat.parse(fullRaw, "coarse-grid")
        assertNull(unknown.gridKind)
        assertNull(LightPollutionVersionFormat.shortDisplay(unknown, english = true))
        // A citation with no build date cannot produce the dated display.
        val noDate = LightPollutionVersionFormat.parse("Falchi et al. 2016 World Atlas", "full-grid")
        assertNull(noDate.builtDate)
        assertNull(LightPollutionVersionFormat.shortDisplay(noDate, english = false))
    }
}
