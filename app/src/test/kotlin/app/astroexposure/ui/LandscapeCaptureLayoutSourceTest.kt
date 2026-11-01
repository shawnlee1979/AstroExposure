package app.astroexposure.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level pins for the landscape capture area's layout
 * (ui-design §7.9; 0.17.1): the two capture entry buttons carry
 * long labels — each states its aiming instruction — and in
 * 0.17.0 they shared one Row, which squeezed the compass button
 * to a sliver whose text was clipped away entirely while the row
 * grew hundreds of dp tall (on-device report). The stacked,
 * full-width form is a code fact, so it is pinned as one: these
 * assertions fail the build the day the entry buttons share a
 * Row again, on any machine, without a device.
 */
class LandscapeCaptureLayoutSourceTest {

    /** The main-source root ("app/src/main" or "src/main" from the module dir). */
    private fun mainRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            val appForm = File(dir, "app/src/main")
            if (appForm.isDirectory) return appForm
            val moduleForm = File(dir, "src/main")
            if (moduleForm.isDirectory && File(dir, "build.gradle.kts").isFile) {
                return moduleForm
            }
            dir = dir.parentFile
                ?: error("src/main not found above ${System.getProperty("user.dir")}")
        }
    }

    private fun compositionSection(): String = File(
        mainRoot(),
        "kotlin/app/astroexposure/ui/CompositionSection.kt",
    ).readText()

    /** The source between two markers, both expected to exist exactly once. */
    private fun segment(source: String, from: String, to: String): String {
        val start = source.indexOf(from)
        assertTrue("marker missing: $from", start >= 0)
        val end = source.indexOf(to, start)
        assertTrue("marker missing after $from: $to", end > start)
        return source.substring(start, end)
    }

    @Test
    fun theCaptureEntryButtonsStackFullWidth() {
        val none = segment(
            compositionSection(),
            "CaptureMode.NONE ->",
            "CaptureMode.EXPLAIN ->",
        )
        assertFalse(
            "the two entry buttons must not share a Row again",
            none.contains("Row("),
        )
        assertTrue(none.contains("Column("))
        assertTrue(
            "both entry buttons are full-width",
            none.split("fillMaxWidth()").size - 1 >= 2,
        )
        assertTrue(none.contains("composition_viewfinder_capture"))
        assertTrue(none.contains("composition_capture"))
    }

    @Test
    fun theExplainButtonsStackFullWidthToo() {
        // The permission explanation's three labels overflow one
        // row in English the same way; the stacked form is pinned
        // alongside the entry row it follows.
        val explain = segment(
            compositionSection(),
            "CaptureMode.EXPLAIN ->",
            "CaptureMode.VIEWFINDER ->",
        )
        assertFalse(explain.contains("Row("))
        assertTrue(explain.contains("Column("))
        assertTrue(
            "all three explanation buttons are full-width",
            explain.split("fillMaxWidth()").size - 1 >= 3,
        )
    }

    @Test
    fun theTopValueCarriesAFingerTapChannelAt48dp() {
        // The foreground-top value enters editing on tap (§7.9).
        // The semantic onClick alone reaches only assistive
        // services; without the gesture channel the entry is dead
        // to fingers, and without the 48 dp floor it breaks the
        // §2 touch-target rule (0.17.0 review, medium).
        val value = segment(
            compositionSection(),
            "val valueDescription",
            "if (!state.topEdited)",
        )
        assertTrue(value.contains("detectTapGestures"))
        assertTrue(value.contains("heightIn(min = 48.dp)"))
    }
}
