package app.astroexposure.camera

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level pins for the camera adapter's boundaries
 * (ui-design §7.9, 0.17.0; ADR-0005): the privacy construction and
 * the layering rule are code facts, so they are tested as code
 * facts — these assertions fail the build the day any of them
 * stops being true, on any machine, without a device.
 */
class CameraAdapterSourceTest {

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

    private fun source(relative: String): String =
        File(mainRoot(), relative).readText()

    private val adapterPath =
        "kotlin/app/astroexposure/camera/CameraXViewfinderAdapter.kt"
    private val portPath = "kotlin/app/astroexposure/camera/ViewfinderPort.kt"

    @Test
    fun theAdapterBindsPreviewOnly() {
        // "Never takes or stores a picture" as a regression-testable
        // fact: the still-capture and frame-analysis use-case types
        // appear nowhere in the adapter — not in code, not in a
        // comment.
        val adapter = source(adapterPath)
        assertFalse(adapter.contains("ImageCapture"))
        assertFalse(adapter.contains("ImageAnalysis"))
    }

    @Test
    fun cameraXTypesLiveInTheAdapterAlone() {
        val offenders = mainRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("androidx.camera") }
            .map { it.name }
            .toList()
        assertEquals(listOf("CameraXViewfinderAdapter.kt"), offenders)
    }

    @Test
    fun thePortSpeaksNoCameraStackTypes() {
        assertFalse(source(portPath).contains("androidx.camera"))
    }

    @Test
    fun theRedLightFormIsInTheAdapter() {
        // The red-light viewfinder (red translucent layer + exposure
        // compensation to the minimum) is adapter work: both halves
        // must be present in the adapter's source.
        val adapter = source(adapterPath)
        assertTrue(adapter.contains("setExposureCompensationIndex"))
        assertTrue(adapter.contains("0xFFFF9D8A"))
        assertTrue(adapter.contains("RED_TINT_ALPHA"))
    }

    @Test
    fun theAxisElevationNeverComesFromAPitchShortcut() {
        // γ is computed from the rotation matrix by its own pure
        // function; the 90°-minus-pitch conversion (valid only for
        // one family of holds) must not appear in the orientation
        // adapter's code.
        val provider = source(
            "kotlin/app/astroexposure/orientation/AndroidOrientationProvider.kt",
        )
        assertTrue(provider.contains("cameraAxisElevationDeg(rotationMatrix)"))
        assertFalse(provider.contains("90.0 -"))
        assertFalse(provider.contains("90 -"))
    }
}
