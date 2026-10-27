package app.astroexposure.domain.model

import app.astroexposure.domain.camera.Camera
import app.astroexposure.domain.sky.TotalSky
import app.astroexposure.domain.units.Seconds
import kotlin.math.roundToInt

/**
 * Wide-field ISO / shutter helpers (design §5.2.1 untracked ISO guidance and
 * §5.2.2 tracked settings). The untracked ISO range is an EMPIRICAL rule
 * ("经验口径", design §5.2.1): anchored at a dark sky of about 21.75 mpsas,
 * where the A1 II range is ISO 3200–6400 and the ZV-E1 range is
 * ISO 3200–12800; every 1 mag of brighter total sky shifts the whole range
 * down one stop (factor 2), floored at ISO 400 and capped at the anchor's
 * upper end. The anchor is keyed on pixel pitch: bodies at or below 5 µm
 * use the A1 II anchor, larger-pixel bodies the ZV-E1 anchor (design §3.6
 * treats the two bodies' defaults as systematically different).
 */
object WideFieldModel {

    /** Dark-sky anchor brightness, mpsas (design §5.2.1: atlas Bortle <= 4, weak moonlight). */
    const val ANCHOR_MPSAS = 21.75

    /** No untracked recommendation goes below this ISO (empirical floor). */
    const val ISO_FLOOR = 400

    private fun anchorRange(camera: Camera): IntRange =
        if (camera.pixelPitch.value <= 5.0) 3200..6400 else 3200..12800

    data class IsoRange(val low: Int, val high: Int)

    /** Untracked ISO range for the given total sky (empirical rule, see class KDoc). */
    fun untrackedIso(totalSky: TotalSky, camera: Camera): IsoRange {
        val anchor = anchorRange(camera)
        // Whole-stop shift: positive when the sky is brighter than the anchor.
        val shiftStops = (ANCHOR_MPSAS - totalSky.totalMpsas).roundToInt()
        val factor = Math.pow(2.0, -shiftStops.toDouble())
        val low = (anchor.first * factor).roundToInt().coerceIn(ISO_FLOOR, anchor.last)
        val high = (anchor.last * factor).roundToInt().coerceIn(ISO_FLOOR, anchor.last)
        return IsoRange(low = minOf(low, high), high = maxOf(low, high))
    }

    data class TrackedSettings(
        /** A tidy shutter at or below the tracking cap. */
        val shutter: Seconds,
        /** ISO snapped to the gain point (or fallback), per the §5.2.2 adsorption discipline. */
        val iso: Int?,
        val unverifiedGainFlag: Boolean,
    )

    /** Tidy shutter candidates for tracked work, longest first (design §5.2.2). */
    private val TIDY_SHUTTERS = listOf(120.0, 90.0, 60.0, 30.0)

    /**
     * Tracked settings: the largest tidy shutter not exceeding [maxShutter]
     * (or [maxShutter] itself when even 30 s exceeds it), plus the ISO the
     * gain discipline snaps to: the dual-gain point itself; for unverified
     * bodies the recorded fallback ISO, flagged.
     */
    fun trackedSettings(camera: Camera, maxShutter: Seconds): TrackedSettings {
        val tidy = TIDY_SHUTTERS.firstOrNull { it <= maxShutter.value }
        val shutter = Seconds(tidy ?: maxShutter.value)
        val gain = camera.preferredDualGainIso
        return if (gain != null && camera.photoGainVerified) {
            TrackedSettings(shutter = shutter, iso = gain, unverifiedGainFlag = false)
        } else {
            TrackedSettings(
                shutter = shutter,
                iso = camera.fallbackDeepSkyIso ?: gain,
                unverifiedGainFlag = true,
            )
        }
    }

    /**
     * ISO adsorption shared with the meteor model (same discipline, D.4):
     * the dual-gain point when verified, else the fallback ISO, else null.
     */
    fun adsorbedIso(camera: Camera): Pair<Int?, Boolean> {
        val gain = camera.preferredDualGainIso
        return if (gain != null && camera.photoGainVerified) {
            gain to false
        } else {
            (camera.fallbackDeepSkyIso ?: gain) to true
        }
    }
}
