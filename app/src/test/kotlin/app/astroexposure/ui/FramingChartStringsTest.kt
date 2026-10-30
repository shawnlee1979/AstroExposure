package app.astroexposure.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level contract for the framing chart's strings
 * (ui-design §7.10, 0.13.1): the quantities string is the SINGLE
 * home of the chart width and the projection name. It printed
 * as the visible line under the chart until 0.13.1; since
 * 0.13.2 that visible line is gone (on-device feedback) and
 * the string is carried only by the chart's accessibility
 * description. The in-chart corner overlay strings are gone
 * since 0.13.1 (the overlay duplicated the chart width and
 * printed the constant north-up/east-left convention on every
 * chart). String resources cannot be read from a JVM unit
 * test, so this test reads the shipped resource files
 * directly; it fails loudly if they cannot be found.
 */
class FramingChartStringsTest {

    private fun stringsFile(localeDir: String): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            for (base in listOf("app/src/main/res", "src/main/res")) {
                val candidate = File(dir, "$base/$localeDir/strings.xml")
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
                ?: error("strings.xml ($localeDir) not found above ${System.getProperty("user.dir")}")
        }
    }

    private fun strings(localeDir: String): Map<String, String> {
        val text = stringsFile(localeDir).readText()
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun quantitiesLineCarriesChartWidthAndProjectionOnce() {
        assertEquals(
            "焦距 %1\$d mm · 视场 %2\$s°×%3\$s° · 长边占比 %4\$d%% · 图面 %5\$d° · %6\$s投影",
            strings("values").getValue("framing_quantities"),
        )
        assertEquals(
            "Focal %1\$d mm · Field %2\$s°×%3\$s° · Long-side share %4\$d%% · " +
                "Chart %5\$d° · %6\$s projection",
            strings("values-en").getValue("framing_quantities"),
        )
    }

    @Test
    fun cornerOverlayStringsAreGoneProjectionWordsStay() {
        for (locale in listOf("values", "values-en")) {
            val strings = strings(locale)
            assertFalse(
                "framing_corner_north removed ($locale)",
                strings.containsKey("framing_corner_north"),
            )
            assertFalse(
                "framing_corner_field removed ($locale)",
                strings.containsKey("framing_corner_field"),
            )
            assertTrue(strings.containsKey("framing_projection_gnomonic"))
            assertTrue(strings.containsKey("framing_projection_stereographic"))
        }
    }
}
