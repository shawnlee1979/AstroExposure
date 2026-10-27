package app.astroexposure.domain.model

import app.astroexposure.domain.camera.Camera
import app.astroexposure.domain.strictness.Strictness
import app.astroexposure.domain.tracking.TrackingKind
import app.astroexposure.domain.tracking.TrackingProfile
import app.astroexposure.domain.units.ArcsecPerMinute
import app.astroexposure.domain.units.Seconds

/**
 * Tracking drift model (design §5.2.2) — the single implementation of the
 * pixel-tolerance conversion:
 *
 *   pixel scale s ("/px) = 206.265 * p (µm) / f (mm)
 *   t_max (s) = tolerance (px) * s / drift D ("/min) * 60
 *
 * Strictness mapping (design §5, general rule 1): STRICT uses the worst
 * drift with a 1 px tolerance, STANDARD the typical drift at 1 px, RELAXED
 * the typical drift at 2 px.
 *
 * When the profile carries no measured drift, the result is [Outcome.Unmeasured]
 * — no seconds are ever produced from an unmeasured profile (design §2.8).
 */
object DriftModel {

    /** Why no measured result could be produced. */
    enum class UnmeasuredReason {
        /** Profile is tracked but its drift has not been measured yet. */
        DRIFT_NOT_MEASURED,

        /** The untracked profile was passed by mistake; NPF governs there. */
        PROFILE_NOT_TRACKED,
    }

    sealed interface Outcome {
        data class Measured(
            val maxExposure: Seconds,
            /** The drift value actually used (worst for STRICT, typical otherwise). */
            val driftUsed: ArcsecPerMinute,
            val tolerancePx: Double,
            val pixelScaleArcsecPerPixel: Double,
        ) : Outcome

        data class Unmeasured(
            val reason: UnmeasuredReason,
            val pixelScaleArcsecPerPixel: Double,
        ) : Outcome
    }

    /** Pixel scale in arcsec/pixel for [camera] at [focalMm] (design §5.2.2). */
    fun pixelScaleArcsecPerPixel(camera: Camera, focalMm: Double): Double =
        206.265 * camera.pixelPitch.value / focalMm

    fun maxExposure(
        camera: Camera,
        focalMm: Double,
        profile: TrackingProfile,
        strictness: Strictness,
    ): Outcome {
        val scale = pixelScaleArcsecPerPixel(camera, focalMm)
        if (profile.kind != TrackingKind.TRACKED) {
            return Outcome.Unmeasured(UnmeasuredReason.PROFILE_NOT_TRACKED, scale)
        }
        val tolerancePx = if (strictness == Strictness.RELAXED) 2.0 else 1.0
        val drift = if (strictness == Strictness.STRICT) {
            profile.worstDrift ?: profile.typicalDrift
        } else {
            profile.typicalDrift
        } ?: return Outcome.Unmeasured(UnmeasuredReason.DRIFT_NOT_MEASURED, scale)
        val seconds = tolerancePx * scale / drift.value * 60.0
        return Outcome.Measured(
            maxExposure = Seconds(seconds),
            driftUsed = drift,
            tolerancePx = tolerancePx,
            pixelScaleArcsecPerPixel = scale,
        )
    }
}
