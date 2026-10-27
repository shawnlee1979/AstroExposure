package app.astroexposure.domain.camera

import app.astroexposure.domain.units.Micrometers
import kotlin.math.sqrt

/**
 * A camera body. Pixel pitch is DERIVED from sensor width and horizontal
 * pixel count — the single sanctioned definition (design §3.2), so the value
 * can never drift from the sensor data.
 */
data class Camera(
    val id: String,
    val displayName: String,
    val sensorWidthMm: Double,
    val sensorHeightMm: Double,
    val pixelsX: Int,
    val pixelsY: Int,
) {
    /** Pixel pitch in micrometers: sensor width (µm) / horizontal pixels. */
    val pixelPitch: Micrometers
        get() = Micrometers(sensorWidthMm * 1000.0 / pixelsX)

    /** Crop factor relative to full frame (diagonal 43.27 mm). */
    val cropFactor: Double
        get() = 43.27 / sqrt(sensorWidthMm * sensorWidthMm + sensorHeightMm * sensorHeightMm)
}

/** Built-in bodies (design §3.1). Sensor/pixel figures from the design's verified table. */
object BuiltInCameras {
    /** Sony A1 II (ILCE-1M2): 35.9 x 24.0 mm, 8640 x 5760 -> pitch 4.16 µm. */
    val A1_II = Camera("sony-a1-ii", "Sony A1 II", 35.9, 24.0, 8640, 5760)

    /** Sony ZV-E1: 35.6 x 23.8 mm, 4240 x 2832 -> pitch 8.40 µm. */
    val ZV_E1 = Camera("sony-zv-e1", "Sony ZV-E1", 35.6, 23.8, 4240, 2832)

    val ALL: List<Camera> = listOf(A1_II, ZV_E1)
}
