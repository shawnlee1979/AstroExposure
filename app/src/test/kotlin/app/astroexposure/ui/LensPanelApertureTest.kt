package app.astroexposure.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The all-lenses panel's aperture numbers are the shared
 * [formatApertureValue] output — the panel keeps no formatter
 * of its own (ui-design §7.10; before 0.13.1 the panel's
 * former private formatter stripped every
 * trailing ".0", so an f/4.0 lens read "f/4" in the panel but
 * "f/4.0" in the calculator). The value cases pin the shared
 * formatter at the exact points where the two implementations
 * diverged — that formatter's output IS the panel row's
 * aperture text, by construction — and the source cases pin
 * that the panel row really calls the shared function, and
 * that the brand facet's selection is keyed by the [LensBrand]
 * enum itself rather than by looking a display name back up.
 * Source is read directly,
 * like FramingChartStringsTest, because the row's text
 * assembly is a composable a JVM test cannot invoke.
 */
class LensPanelApertureTest {

    @Test
    fun sharedApertureFormatterAtTheDivergencePoints() {
        assertEquals("4.0", formatApertureValue(4.0))
        assertEquals("2.0", formatApertureValue(2.0))
        assertEquals("2.8", formatApertureValue(2.8))
        assertEquals("8", formatApertureValue(8.0))
    }

    @Test
    fun sharedApertureFormatterNeighbouringValues() {
        assertEquals("1.8", formatApertureValue(1.8))
        assertEquals("5.6", formatApertureValue(5.6))
        assertEquals("16", formatApertureValue(16.0))
    }

    private fun framingSectionSource(): String {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            for (base in listOf(
                "app/src/main/kotlin/app/astroexposure/ui",
                "src/main/kotlin/app/astroexposure/ui",
            )) {
                val candidate = File(dir, "$base/FramingChartSection.kt")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
                ?: error("FramingChartSection.kt not found above ${System.getProperty("user.dir")}")
        }
    }

    @Test
    fun panelHasNoApertureFormatterOfItsOwn() {
        val source = framingSectionSource()
        assertFalse("no private aperture formatter remains", source.contains("apertureText"))
        assertTrue(source.contains("formatApertureValue(lens.maxAperture)"))
        assertTrue(source.contains("formatApertureValue(tele)"))
    }

    @Test
    fun brandFacetSelectionIsKeyedByTheEnum() {
        val source = framingSectionSource()
        assertTrue(source.contains("selected = brandFacet,"))
        assertFalse(
            "no display-name lookup-back remains",
            source.contains("displayName(customLabel) == name"),
        )
    }
}
