package app.astroexposure.domain.model

import kotlin.math.pow

/**
 * Landscape-with-sky model (design §5.1): the output is a DUAL parameter
 * set — a sky frame (computed elsewhere by the NPF / drift models and
 * passed in as a snapshot) plus a foreground frame in up to three shooting
 * methods. Foreground parameters are empirical magnitudes ("经验值",
 * design §5.1), given as ranges and orders of magnitude, never as
 * star-model outputs: NPF, drift and swamp do not apply to the foreground.
 */
object LandscapeModel {

    /** Foreground shooting methods (design §5.1); the caller selects any subset. */
    enum class ForegroundMethod { BLUE_HOUR, NIGHT_LONG_EXPOSURE, HIGH_ISO_STACK }

    /** The fixed five-step two-frame workflow (design §5.1). */
    enum class WorkflowStep {
        LOCK_COMPOSITION,
        SHOOT_FOREGROUND_FIRST,
        REFOCUS_ON_STARS,
        SHOOT_SKY_FRAME,
        COMPOSITE_IN_POST,
    }

    /** Caller-supplied snapshot of the already-computed sky frame. */
    data class SkyFrameSnapshot(
        val shutterSeconds: Double,
        val iso: Int,
        val aperture: Double,
        val tracked: Boolean,
    )

    data class Input(
        val sky: SkyFrameSnapshot,
        val methods: Set<ForegroundMethod>,
        /** Minutes after sunset for the blue-hour table; null outside blue hour. */
        val minutesAfterSunset: Int?,
        /** Moon illumination in [0, 1] when the night method should note moonlight. */
        val moonIllumination: Double?,
        /** ISO the user picked for the blue-hour frame, within 100–400. */
        val blueHourIso: Int = 100,
        /** Aperture the user picked for the blue-hour frame, within f/5.6–f/8. */
        val blueHourAperture: Double = 8.0,
    )

    data class SkyFrame(
        val shutterSeconds: Double,
        val iso: Int,
        val aperture: Double,
        val tracked: Boolean,
        /** Fixed reminder when tracking blurs the foreground (design §5.1 step 4). */
        val compositeNote: Boolean,
    )

    data class ForegroundFrame(
        val method: ForegroundMethod,
        val isoRange: IntRange,
        val apertureRange: ClosedFloatingPointRange<Double>,
        /** Shutter guidance in seconds: a single magnitude point or a range. */
        val shutterMinSeconds: Double,
        val shutterMaxSeconds: Double,
        /** Frame count for the stacking method; null otherwise. */
        val frameCountRange: IntRange?,
        /** True when moonlight materially lights the foreground. */
        val moonlightHint: Boolean,
    )

    data class Result(
        val skyFrame: SkyFrame,
        val foregroundFrames: List<ForegroundFrame>,
        val workflowSteps: List<WorkflowStep>,
        /**
         * Requested methods that produced no frame because their inputs
         * place them outside their domain — currently BLUE_HOUR when
         * [Input.minutesAfterSunset] is null (outside blue hour). The skip
         * is explicit here so callers can label it instead of a frame with
         * invented parameters appearing (design §5.1, §2.8).
         */
        val skippedMethods: Set<ForegroundMethod> = emptySet(),
    )

    /**
     * Blue-hour base magnitudes at f/8, ISO 100 (empirical, design §5.1):
     * about 10 / 20 / 30 minutes after sunset -> 2 s / 8 s / 25 s.
     */
    private fun blueHourBaseSeconds(minutesAfterSunset: Int): Double = when {
        minutesAfterSunset <= 15 -> 2.0
        minutesAfterSunset <= 25 -> 8.0
        else -> 25.0
    }

    fun evaluate(input: Input): Result {
        val skipped = mutableSetOf<ForegroundMethod>()
        val frames = input.methods.sorted().mapNotNull { method ->
            when (method) {
                ForegroundMethod.BLUE_HOUR -> {
                    // null minutes means "not in blue hour" (see Input):
                    // no blue-hour frame exists then — never substitute a
                    // mid-table value the caller did not ask for.
                    val minutes = input.minutesAfterSunset
                    if (minutes == null) {
                        skipped += method
                        null
                    } else {
                    // EV shift from the f/8 + ISO 100 base to the chosen settings.
                    val base = blueHourBaseSeconds(minutes)
                    val shifted = base *
                        (input.blueHourAperture / 8.0).pow(2) *
                        (100.0 / input.blueHourIso)
                    ForegroundFrame(
                        method = method,
                        isoRange = 100..400,
                        apertureRange = 5.6..8.0,
                        shutterMinSeconds = shifted,
                        shutterMaxSeconds = shifted,
                        frameCountRange = null,
                        moonlightHint = false,
                    )
                    }
                }
                ForegroundMethod.NIGHT_LONG_EXPOSURE -> ForegroundFrame(
                    method = method,
                    isoRange = 400..1600,
                    apertureRange = 2.8..4.0,
                    shutterMinSeconds = 30.0,
                    shutterMaxSeconds = 240.0,
                    frameCountRange = null,
                    moonlightHint = (input.moonIllumination ?: 0.0) >= 0.5,
                )
                ForegroundMethod.HIGH_ISO_STACK -> ForegroundFrame(
                    method = method,
                    isoRange = 3200..6400,
                    apertureRange = 2.8..4.0,
                    shutterMinSeconds = input.sky.shutterSeconds,
                    shutterMaxSeconds = input.sky.shutterSeconds,
                    frameCountRange = 8..16,
                    moonlightHint = false,
                )
            }
        }
        return Result(
            skyFrame = SkyFrame(
                shutterSeconds = input.sky.shutterSeconds,
                iso = input.sky.iso,
                aperture = input.sky.aperture,
                tracked = input.sky.tracked,
                compositeNote = input.sky.tracked,
            ),
            foregroundFrames = frames,
            workflowSteps = WorkflowStep.entries,
            skippedMethods = skipped,
        )
    }
}
