package app.astroexposure.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level pins for the landscape capture area (ui-design
 * §7.9; layout 0.17.1, compass retirement 0.17.2). The capture
 * entry button carries a long label — it states its aiming
 * instruction — and in 0.17.0 it shared one Row with a second
 * long-labelled button, which squeezed that button to a sliver
 * whose text was clipped away entirely while the row grew
 * hundreds of dp tall (on-device report); the full-width form is
 * a code fact, so it is pinned as one. Since 0.17.2 the compass
 * capture is retired outright (its aiming posture could not be
 * enforced; the viewfinder's centre marker is the aiming point
 * by construction), so the component must not grow a compass
 * entry again, and the verdict block must not regrow the
 * per-case figures lines or the standing caliber paragraph the
 * same batch removed. These assertions fail the build the day
 * any of that returns, on any machine, without a device.
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
    fun theOneCaptureEntryButtonStandsFullWidth() {
        val none = segment(
            compositionSection(),
            "CaptureMode.NONE ->",
            "CaptureMode.EXPLAIN ->",
        )
        assertFalse(
            "the entry button must not share a Row again",
            none.contains("Row("),
        )
        assertTrue(none.contains("composition_viewfinder_capture"))
        assertTrue(
            "the entry button is full-width",
            none.contains("fillMaxWidth()"),
        )
        assertEquals(
            "exactly one capture entry button remains",
            1,
            none.split("OutlinedButton(").size - 1,
        )
        assertFalse(none.contains("CaptureMode.COMPASS"))
    }

    @Test
    fun theExplainButtonsStackFullWidth() {
        // The permission explanation's two remaining actions
        // (continue, cancel) keep the stacked full-width form the
        // 0.17.1 audit gave the row; the compass alternative that
        // used to sit between them is retired.
        val explain = segment(
            compositionSection(),
            "CaptureMode.EXPLAIN ->",
            "CaptureMode.VIEWFINDER ->",
        )
        assertFalse(explain.contains("Row("))
        assertTrue(explain.contains("Column("))
        assertTrue(
            "both explanation buttons are full-width",
            explain.split("fillMaxWidth()").size - 1 >= 2,
        )
        assertFalse(explain.contains("composition_camera_use_compass"))
    }

    @Test
    fun theCompassCaptureStaysRetired() {
        val source = compositionSection()
        for (retired in listOf(
            "CompassCaptureBlock",
            "CaptureMode.COMPASS",
            "composition_compass_posture",
            "composition_capture_live_pitch",
            "composition_capture_lock_pitch",
            "composition_capture_unavailable",
            "R.string.composition_capture)",
        )) {
            assertFalse(
                "retired compass capture residue: $retired",
                source.contains(retired),
            )
        }
    }

    @Test
    fun theVerdictBlockStaysSlimmed() {
        val source = compositionSection()
        for (removed in listOf(
            "composition_never_top_detail",
            "composition_span_detail",
            "composition_caliber",
            "composition_anchor_extends",
        )) {
            assertFalse(
                "removed verdict text returned: $removed",
                source.contains(removed),
            )
        }
        // The verdict categories themselves stay.
        assertTrue(source.contains("composition_never_top_title"))
        assertTrue(source.contains("composition_span_title"))
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
