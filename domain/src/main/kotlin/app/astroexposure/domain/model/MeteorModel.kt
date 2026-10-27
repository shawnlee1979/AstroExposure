package app.astroexposure.domain.model

import app.astroexposure.domain.camera.Camera
import app.astroexposure.domain.catalog.MeteorShower
import app.astroexposure.domain.sky.TotalSky
import app.astroexposure.domain.sky.effectiveQe
import app.astroexposure.domain.sky.skyElectronRate
import app.astroexposure.domain.strictness.Strictness
import app.astroexposure.domain.units.Degrees
import kotlin.math.floor

/**
 * Meteor-shower continuous-shooting model (design §5.5). A meteor's
 * brightness in a frame does not grow with shutter time, so the single-frame
 * exposure is capped by whichever comes first:
 *   (a) trailing — the NPF limit ([NpfModel], the single implementation, D.4);
 *   (b) sky background — the time for the sky background to reach the
 *       strictness tier's histogram target fraction of full well:
 *       t_sky = targetFraction * fullWell / R_sky.
 * When the body records no full well, only the NPF cap applies and
 * [Result.skyCapUnavailable] is set (explicit degradation, design §2.8).
 *
 * The aperture arrives already chosen by the caller (lens maximum or a
 * half-stop down, design §5.5 step 1); the model does not change it.
 * ISO follows the shared gain adsorption discipline ([WideFieldModel]).
 */
object MeteorModel {

    /** Inter-frame gap allowance in the budget arithmetic, seconds (design §5.5: about 1 s). */
    const val FRAME_GAP_SECONDS = 1.0

    enum class LimitingFactor { TRAILING, SKY_BRIGHTNESS }

    /** Moonlight expectation classes (empirical thresholds, design §5.5). */
    enum class MoonVerdict { SEVERE, MODERATE, LOW }

    /** Activity level relative to the shower peak, from the caller-supplied day offset. */
    enum class ActivityLevel { PEAK, NEAR, OFF_PEAK }

    data class Input(
        val shower: MeteorShower,
        val camera: Camera,
        val focalMm: Double,
        /** Aperture already selected by the caller (maximum or half-stop down). */
        val aperture: Double,
        val totalSky: TotalSky,
        val strictness: Strictness,
        /** Planned continuous shooting duration, hours. */
        val shootingHours: Double,
        /** Moon illumination in [0, 1] for the shooting window. */
        val moonIllumination: Double,
        /** Whether the Moon is up during the shooting window. */
        val moonUp: Boolean,
        /** Days from the shower peak (ephemeris side supplies this; model only consumes it). */
        val daysFromPeak: Double,
    )

    data class Result(
        /** Single-frame shutter cap in seconds (the smaller of the two caps). */
        val singleFrameSeconds: Double,
        val limitingFactor: LimitingFactor,
        /** True when no full well is recorded and the sky cap could not be applied. */
        val skyCapUnavailable: Boolean,
        /** True when the sky cap used the default QE (camera records none, design §2.3/§2.8). */
        val qeDefaulted: Boolean,
        val iso: Int?,
        val unverifiedGainFlag: Boolean,
        val frameCount: Int,
        /** Total data volume in GB; null when the body records no RAW file size. */
        val dataGb: Double?,
        val moonVerdict: MoonVerdict,
        val activityLevel: ActivityLevel,
    )

    /**
     * Moonlight verdict (empirical thresholds, design §5.5): with the Moon
     * down there is no moonlight to interfere, whatever the phase — LOW.
     * With the Moon up: illumination >= 0.9 on the peak night (within the
     * PEAK band, |days from peak| <= 0.5) is SEVERE; >= 0.55 is MODERATE;
     * else LOW.
     */
    fun moonVerdict(illumination: Double, moonUp: Boolean, daysFromPeak: Double): MoonVerdict = when {
        !moonUp -> MoonVerdict.LOW
        illumination >= 0.9 && kotlin.math.abs(daysFromPeak) <= 0.5 -> MoonVerdict.SEVERE
        illumination >= 0.55 -> MoonVerdict.MODERATE
        else -> MoonVerdict.LOW
    }

    /** Activity level from the peak offset: |d| <= 0.5 d PEAK, <= 1.5 d NEAR, else OFF_PEAK. */
    fun activityLevel(daysFromPeak: Double): ActivityLevel = when {
        kotlin.math.abs(daysFromPeak) <= 0.5 -> ActivityLevel.PEAK
        kotlin.math.abs(daysFromPeak) <= 1.5 -> ActivityLevel.NEAR
        else -> ActivityLevel.OFF_PEAK
    }

    fun evaluate(input: Input): Result {
        val npf = NpfModel.evaluate(
            NpfModel.Input(
                camera = input.camera,
                focalLengthMm = input.focalMm,
                aperture = input.aperture,
                declination = Degrees(input.shower.radiantDecDeg),
                strictness = input.strictness,
            ),
        ).maxShutter.value

        val fullWell = input.camera.fullWellElectrons
        val (qe, qeDefaulted) = effectiveQe(input.camera)
        val skyCap: Double? = fullWell?.let { fw ->
            val rSky = skyElectronRate(input.totalSky.totalMpsas, input.aperture, input.camera, qe)
            input.strictness.backgroundTargetFraction * fw / rSky
        }

        val single: Double
        val limiting: LimitingFactor
        if (skyCap != null && skyCap < npf) {
            single = skyCap
            limiting = LimitingFactor.SKY_BRIGHTNESS
        } else {
            single = npf
            limiting = LimitingFactor.TRAILING
        }

        val frames = floor(input.shootingHours * 3600.0 / (single + FRAME_GAP_SECONDS)).toInt()
        val dataGb = input.camera.rawFileSizeMb?.let { frames * it / 1024.0 }
        val (iso, unverified) = WideFieldModel.adsorbedIso(input.camera)

        return Result(
            singleFrameSeconds = single,
            limitingFactor = limiting,
            skyCapUnavailable = skyCap == null,
            qeDefaulted = qeDefaulted,
            iso = iso,
            unverifiedGainFlag = unverified,
            frameCount = frames,
            dataGb = dataGb,
            moonVerdict = moonVerdict(input.moonIllumination, input.moonUp, input.daysFromPeak),
            activityLevel = activityLevel(input.daysFromPeak),
        )
    }
}
