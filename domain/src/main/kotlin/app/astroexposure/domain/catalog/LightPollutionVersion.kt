package app.astroexposure.domain.catalog

import java.time.LocalDate

/**
 * Structured form of the light-pollution grid header (ui-design §7.2):
 * the location card shows a short display string composed from the
 * header fields, never the raw [rawDataVersion] citation string.
 *
 * The header (written by the data pipeline, scripts/resample_falchi.py)
 * records the atlas citation in `dataVersion` — including the atlas
 * year and the build date — and the grid kind in `gridKind`
 * (`full-grid` / `sparse-sample`). Both fields are parsed here so the
 * short display follows the header automatically when the data product
 * is replaced; no part of the display string is a fixed literal copied
 * from one particular header.
 */
enum class GridKind { FULL_GRID, SPARSE_SAMPLE }

data class LightPollutionVersion(
    /** The header's raw `dataVersion` string, kept for the data-sources screen. */
    val rawDataVersion: String,
    /** Mapped from the header's `gridKind` token; null when the header carries none. */
    val gridKind: GridKind?,
    /** The atlas year parsed from the citation (e.g. 2016); null when absent. */
    val atlasYear: Int?,
    /** The build / sampling date recorded in the citation; null when absent. */
    val builtDate: LocalDate?,
)

/**
 * Pure formatting for [LightPollutionVersion] (ui-design §7.2). The
 * short display exists in both UI languages; it is assembled from the
 * parsed header fields, so a data replacement (new build date, a
 * sparse-sample fallback grid) changes the card text on its own.
 */
object LightPollutionVersionFormat {

    private val isoDate = Regex("""\d{4}-\d{2}-\d{2}""")
    private val atlasYearPattern = Regex("""Falchi(?:\s+et\s+al\.)?\s+(\d{4})""")

    /**
     * Parse a header into its structured form. [gridKindToken] is the
     * header's separate `gridKind` field (null for headers that predate
     * it); unknown tokens map to null rather than to a guessed kind.
     * The build date is the last ISO date in the citation — the
     * full-grid form ends with "(resampled YYYY-MM-DD)" and the
     * sparse-sample form with "on YYYY-MM-DD".
     */
    fun parse(rawDataVersion: String, gridKindToken: String?): LightPollutionVersion {
        val kind = when (gridKindToken) {
            "full-grid" -> GridKind.FULL_GRID
            "sparse-sample" -> GridKind.SPARSE_SAMPLE
            else -> null
        }
        val year = atlasYearPattern.find(rawDataVersion)?.groupValues?.get(1)?.toIntOrNull()
        val date = isoDate.findAll(rawDataVersion).lastOrNull()?.value?.let { text ->
            try {
                LocalDate.parse(text)
            } catch (e: Exception) {
                null
            }
        }
        return LightPollutionVersion(
            rawDataVersion = rawDataVersion,
            gridKind = kind,
            atlasYear = year,
            builtDate = date,
        )
    }

    /**
     * The short display for the location card, e.g.
     * zh: "Falchi 2016 图谱 · 全量网格 2026-10-27"
     * en: "Falchi 2016 atlas · Full grid 2026-10-27"
     * Returns null when any field the display needs is missing from the
     * header — the caller then falls back to the raw version string
     * instead of presenting a partially invented label.
     */
    fun shortDisplay(version: LightPollutionVersion, english: Boolean): String? {
        val year = version.atlasYear ?: return null
        val kind = version.gridKind ?: return null
        val date = version.builtDate ?: return null
        return if (english) {
            val kindText = when (kind) {
                GridKind.FULL_GRID -> "Full grid"
                GridKind.SPARSE_SAMPLE -> "Sample grid"
            }
            "Falchi $year atlas · $kindText $date"
        } else {
            val kindText = when (kind) {
                GridKind.FULL_GRID -> "全量网格"
                GridKind.SPARSE_SAMPLE -> "样本网格"
            }
            "Falchi $year 图谱 · $kindText $date"
        }
    }
}
