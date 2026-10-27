package app.astroexposure.domain.model

import app.astroexposure.domain.camera.Camera
import app.astroexposure.domain.sky.TotalSky
import app.astroexposure.domain.sky.effectiveQe
import app.astroexposure.domain.sky.skyElectronRate
import app.astroexposure.domain.strictness.Strictness
import app.astroexposure.domain.units.Seconds
import kotlin.math.ceil

/**
 * Deep-sky sub-exposure model (design §5.3), the "swamp" rule:
 *
 *   target background electrons S_sky = C * RN²
 *   sub-exposure t_sub = C * RN² / R_sky
 *
 * C comes from the global strictness tier (STRICT/STANDARD 10, RELAXED 5);
 * the other tier's value is always reported alongside. t_sub is capped by
 * the tracking limit (when supplied) and by the 600 s single-frame ceiling.
 *
 * Degradation (design §2.8): missing read noise OR a missing light-pollution
 * baseline yields [Outcome.Degraded] — the method and the target electron
 * count where computable, but NO seconds. Another body's read noise is
 * never substituted.
 *
 * The total-sky input is uncertain by about ±50% up to a factor of 2
 * (design §5.3 error statement); [Outcome.Ready.skyUncertaintyNote] is a
 * permanent part of the result, not an optional warning.
 */
object DeepSkyModel {

    /** Single-frame empirical ceiling, seconds (design §5.3 guardrail). */
    const val SINGLE_FRAME_CAP_SECONDS = 600.0

    /** Permanent uncertainty statement carried by every ready result (design §5.3). */
    const val SKY_UNCERTAINTY_NOTE =
        "Sky rate is estimated from the atlas + moonlight model; error can reach ±50% or a factor of 2. " +
            "Treat sub-exposure seconds as order-of-magnitude and verify with a test frame histogram " +
            "(background peak at 20-30%)."

    enum class TargetClass(val totalHoursRepresentative: Double, val totalHoursMin: Double, val totalHoursMax: Double) {
        /** Bright clusters / nebulae (M45, M42 class): 1–2 h (design §5.3). */
        BRIGHT_CLUSTER_NEBULA(1.5, 1.0, 2.0),

        /** General galaxies / dark nebulae: 3–5 h. */
        GENERAL_GALAXY_DARK_NEBULA(4.0, 3.0, 5.0),

        /** Very faint targets: 6 h and up, accumulated over multiple nights. */
        VERY_FAINT(6.0, 6.0, 8.0),
    }

    /** Which guardrail capped the sub-exposure, when one did. */
    enum class CapReason { TRACKING_LIMIT, SINGLE_FRAME_CEILING }

    enum class DegradedReason { READ_NOISE_MISSING, SKY_BASELINE_MISSING }

    data class Input(
        val camera: Camera,
        val aperture: Double,
        val totalSky: TotalSky,
        val strictness: Strictness,
        /** Tracking cap from the drift model, when a measured profile supplied one. */
        val trackingCapSeconds: Seconds?,
        val targetClass: TargetClass,
        /** ISO the user actually selected, when it differs from the recommendation. */
        val chosenIso: Int? = null,
    )

    sealed interface Outcome {
        data class Ready(
            val recommendedIso: Int,
            /** True when [recommendedIso] is the unverified-gain fallback (design §5.3, ZV-E1). */
            val unverifiedGainFlag: Boolean,
            /** True when the chosen ISO sits below the gain point and pays the read-noise penalty. */
            val belowGainPenaltyFlag: Boolean,
            /** Read noise (e-) used, for the ISO actually in effect. */
            val readNoiseElectrons: Double,
            /** Swamp coefficient of the active tier. */
            val swampC: Int,
            /** Background target electrons C * RN². */
            val targetBackgroundElectrons: Double,
            val skyElectronRatePerPixelSec: Double,
            /** True when the sky rate used the default QE (camera records none, design §2.3/§2.8). */
            val qeDefaulted: Boolean,
            val subExposureSeconds: Double,
            /** Sub-exposure under the OTHER tier's C, for comparison (design §5.3). */
            val subExposureOtherCSeconds: Double,
            val otherC: Int,
            /**
             * True when the primary value was capped and the uncapped
             * other-C comparison still exceeds that same cap; the UI must
             * label the comparison as uncapped rather than imply that the
             * guardrails were applied to it as well.
             */
            val otherCComparisonUncapped: Boolean,
            val capReasons: Set<CapReason>,
            val totalHoursRepresentative: Double,
            val totalHoursMin: Double,
            val totalHoursMax: Double,
            val frameCount: Int,
            /** Total data volume in GB; null when the body records no RAW file size. */
            val dataGb: Double?,
            val skyUncertaintyNote: String = SKY_UNCERTAINTY_NOTE,
        ) : Outcome

        data class Degraded(
            val reason: DegradedReason,
            /** C * RN² when read noise is known (method quantity, not seconds). */
            val targetBackgroundElectrons: Double?,
            val methodNote: String,
        ) : Outcome
    }

    /** The core swamp arithmetic, exposed for golden tests: t_sub = C * RN² / R_sky. */
    fun computeSubExposureSeconds(readNoiseElectrons: Double, swampC: Int, skyRatePerPixelSec: Double): Double =
        swampC * readNoiseElectrons * readNoiseElectrons / skyRatePerPixelSec

    /** Read noise for an ISO: at/above the gain point the high-gain value, below it the low-segment value. */
    fun readNoiseForIso(camera: Camera, iso: Int): Double? {
        // For an unverified body the recorded fallback ISO stands in as the
        // threshold: at/above it the high-gain-segment value applies.
        val gain = camera.preferredDualGainIso ?: camera.fallbackDeepSkyIso
        return if (gain != null && iso >= gain) {
            camera.readNoiseAtGainElectrons ?: camera.readNoiseLowSegmentElectrons
        } else {
            camera.readNoiseLowSegmentElectrons ?: camera.readNoiseAtGainElectrons
        }
    }

    fun evaluate(input: Input): Outcome {
        val camera = input.camera
        val c = input.strictness.swampC
        val otherC = if (c == 10) 5 else 10

        // ISO rule (design §5.3): prefer the dual-gain point; unverified
        // bodies fall back to their recorded fallback ISO and are flagged.
        val gain = camera.preferredDualGainIso
        val unverified = gain == null || !camera.photoGainVerified
        val recommendedIso = input.chosenIso ?: gain ?: camera.fallbackDeepSkyIso
            ?: return degradedNoReadNoise(c, camera)
        val effectiveGain = gain ?: camera.fallbackDeepSkyIso
        val belowGain = effectiveGain != null && recommendedIso < effectiveGain

        val rn = readNoiseForIso(camera, recommendedIso)
            ?: return degradedNoReadNoise(c, camera)
        val targetElectrons = c * rn * rn

        if (input.totalSky.baselineSource == app.astroexposure.domain.sky.BaselineSource.NONE) {
            return Outcome.Degraded(
                reason = DegradedReason.SKY_BASELINE_MISSING,
                targetBackgroundElectrons = targetElectrons,
                methodNote = "No light-pollution baseline (no coordinates, no atlas coverage, no manual " +
                    "Bortle): sub-exposure seconds cannot be derived. Target background is " +
                    "C*RN² = $targetElectrons e-; supply a location or a manual Bortle to convert.",
            )
        }

        val (qe, qeDefaulted) = effectiveQe(camera)
        val rSky = skyElectronRate(input.totalSky.totalMpsas, input.aperture, camera, qe)
        val rawSub = computeSubExposureSeconds(rn, c, rSky)
        val rawOther = computeSubExposureSeconds(rn, otherC, rSky)

        val caps = mutableSetOf<CapReason>()
        var sub = rawSub
        val trackingCap = input.trackingCapSeconds?.value
        if (trackingCap != null && sub > trackingCap) {
            sub = trackingCap
            caps += CapReason.TRACKING_LIMIT
        }
        if (sub > SINGLE_FRAME_CAP_SECONDS) {
            sub = SINGLE_FRAME_CAP_SECONDS
            caps += CapReason.SINGLE_FRAME_CEILING
        }
        val effectiveCap = listOfNotNull(trackingCap, SINGLE_FRAME_CAP_SECONDS).min()
        // The comparison value is raw (uncapped) by construction, so its
        // label depends only on whether it exceeds the cap — not on
        // whether the main value was itself capped. Tying the flag to
        // caps would miss the relaxed tier, where the main value can sit
        // below the cap while the doubled comparison value exceeds it.
        val otherCComparisonUncapped = rawOther > effectiveCap

        val totalSeconds = input.targetClass.totalHoursRepresentative * 3600.0
        val frames = ceil(totalSeconds / sub).toInt()
        val dataGb = camera.rawFileSizeMb?.let { frames * it / 1024.0 }

        return Outcome.Ready(
            recommendedIso = recommendedIso,
            unverifiedGainFlag = unverified && input.chosenIso == null,
            belowGainPenaltyFlag = belowGain,
            readNoiseElectrons = rn,
            swampC = c,
            targetBackgroundElectrons = targetElectrons,
            skyElectronRatePerPixelSec = rSky,
            qeDefaulted = qeDefaulted,
            subExposureSeconds = sub,
            subExposureOtherCSeconds = rawOther,
            otherC = otherC,
            otherCComparisonUncapped = otherCComparisonUncapped,
            capReasons = caps,
            totalHoursRepresentative = input.targetClass.totalHoursRepresentative,
            totalHoursMin = input.targetClass.totalHoursMin,
            totalHoursMax = input.targetClass.totalHoursMax,
            frameCount = frames,
            dataGb = dataGb,
        )
    }

    private fun degradedNoReadNoise(c: Int, camera: Camera): Outcome.Degraded = Outcome.Degraded(
        reason = DegradedReason.READ_NOISE_MISSING,
        targetBackgroundElectrons = null,
        methodNote = "Read noise for '${camera.displayName}' is not recorded: sub-exposure seconds " +
            "cannot be derived and no other body's read noise may be substituted. Method: expose " +
            "until the per-frame background reaches C*RN² electrons (C = $c for this tier) once " +
            "the read-noise table is available.",
    )
}
