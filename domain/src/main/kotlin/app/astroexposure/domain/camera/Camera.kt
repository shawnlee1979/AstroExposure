package app.astroexposure.domain.camera

import app.astroexposure.domain.units.Micrometers
import kotlin.math.sqrt

/**
 * A camera body. Pixel pitch is DERIVED from sensor width and horizontal
 * pixel count — the single sanctioned definition (design §3.2), so the value
 * can never drift from the sensor data.
 *
 * The phase-2 fields (dual-gain points, read noise, full well, RAW file size)
 * all carry defaults so a user-defined body needs only the base geometry
 * (design §2.3). Null means "not recorded": models must degrade explicitly
 * instead of borrowing another body's numbers (design §2.8).
 */
data class Camera(
    val id: String,
    val displayName: String,
    val sensorWidthMm: Double,
    val sensorHeightMm: Double,
    val pixelsX: Int,
    val pixelsY: Int,
    /** Dual-gain (HCG) switch point under a mechanical shutter, ISO (design §3.3). */
    val dualGainIsoMechanical: Int? = null,
    /** Dual-gain (HCG) switch point under an electronic shutter, ISO (design §3.3). */
    val dualGainIsoElectronic: Int? = null,
    /** Representative read noise in the high-gain segment, electrons (design §3.4). */
    val readNoiseAtGainElectrons: Double? = null,
    /** Representative read noise in the low-gain segment, electrons (design §3.4). */
    val readNoiseLowSegmentElectrons: Double? = null,
    /** Full-well capacity at base ISO, electrons (design §3.5). */
    val fullWellElectrons: Double? = null,
    /** True when [fullWellElectrons] is a same-family reference value, not a per-body measurement. */
    val fullWellIsReferenceValue: Boolean = false,
    /** False when the photo-mode dual-gain point has not been verified (design §3.3, ZV-E1). */
    val photoGainVerified: Boolean = true,
    /** Conservative deep-sky ISO to use while the photo gain point is unverified (design §5.3). */
    val fallbackDeepSkyIso: Int? = null,
    /** Typical single-frame RAW file size, MB (design §5.3/§5.5 data budgets). */
    val rawFileSizeMb: Double? = null,
) {
    /** Pixel pitch in micrometers: sensor width (µm) / horizontal pixels. */
    val pixelPitch: Micrometers
        get() = Micrometers(sensorWidthMm * 1000.0 / pixelsX)

    /** Crop factor relative to full frame (diagonal 43.27 mm). */
    val cropFactor: Double
        get() = 43.27 / sqrt(sensorWidthMm * sensorWidthMm + sensorHeightMm * sensorHeightMm)

    /**
     * The dual-gain point models should aim for: the mechanical-shutter point
     * when present, otherwise the electronic one, otherwise null.
     */
    val preferredDualGainIso: Int?
        get() = dualGainIsoMechanical ?: dualGainIsoElectronic
}

/** Built-in bodies (design §3.1, §3.3–§3.5). */
object BuiltInCameras {
    /**
     * Sony A1 II (ILCE-1M2): 35.9 x 24.0 mm, 8640 x 5760 -> pitch 4.16 µm.
     * Dual gain (design §3.3, PhotonsToPhotos measurements relayed by
     * SonyAlphaRumors 2024-12-25 / FredMiranda): mechanical ISO 640,
     * electronic ISO 500. Read noise (design §3.4): high-gain segment about
     * 1.1 e- (first-generation A1 measured value, interval representative),
     * low-gain segment about 3.5 e-. Full well 39,898 e- is the
     * first-generation A1 compiled value (design §3.5) — a reference value,
     * flagged as such. RAW file size about 60 MB (design §5.3 range 55–65 MB).
     */
    val A1_II = Camera(
        id = "sony-a1-ii",
        displayName = "Sony A1 II",
        sensorWidthMm = 35.9,
        sensorHeightMm = 24.0,
        pixelsX = 8640,
        pixelsY = 5760,
        dualGainIsoMechanical = 640,
        dualGainIsoElectronic = 500,
        readNoiseAtGainElectrons = 1.1,
        readNoiseLowSegmentElectrons = 3.5,
        fullWellElectrons = 39898.0,
        fullWellIsReferenceValue = true,
        rawFileSizeMb = 60.0,
    )

    /**
     * Sony ZV-E1: 35.6 x 23.8 mm, 4240 x 2832 -> pitch 8.40 µm.
     * The photo-mode dual-gain point is NOT verified (design §3.3): video-mode
     * figures must not stand in for it, so both gain points stay null,
     * [Camera.photoGainVerified] is false and the deep-sky fallback ISO is
     * 800 (design §5.3 rule for the unverified case). Read noise (design
     * §3.4): high-gain segment about 1.0 e- (same-sensor A7S III measured
     * value), low-gain segment about 3.5 e-. Full well 187,035 e- is the
     * same-sensor A7S III compiled value (design §3.5) — a reference value.
     * RAW file size about 14 MB (design §5.3 range 13–15 MB).
     */
    val ZV_E1 = Camera(
        id = "sony-zv-e1",
        displayName = "Sony ZV-E1",
        sensorWidthMm = 35.6,
        sensorHeightMm = 23.8,
        pixelsX = 4240,
        pixelsY = 2832,
        dualGainIsoMechanical = null,
        dualGainIsoElectronic = null,
        readNoiseAtGainElectrons = 1.0,
        readNoiseLowSegmentElectrons = 3.5,
        fullWellElectrons = 187035.0,
        fullWellIsReferenceValue = true,
        photoGainVerified = false,
        fallbackDeepSkyIso = 800,
        rawFileSizeMb = 14.0,
    )

    val ALL: List<Camera> = listOf(A1_II, ZV_E1)
}
