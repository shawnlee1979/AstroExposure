package app.astroexposure.domain.model

import app.astroexposure.domain.units.Degrees
import app.astroexposure.domain.units.Seconds
import kotlin.math.log2
import kotlin.math.pow

/**
 * Moon exposure model (design §5.4.1): the "Looney 11" rule plus the
 * phase-illumination correction table and the low-altitude correction.
 * Single implementation of this model in the codebase (D.4).
 *
 * Base rule: full moon, clear sky, high altitude -> at f/11 the shutter is
 * 1/ISO seconds. Other apertures follow by EV equivalence; the result is
 * snapped to the conventional 1/3-stop shutter scale (design §8.6: Looney 11
 * conversions must be exact to 1/3 stop).
 */
object MoonModel {

    /** Base aperture of the Looney 11 rule. */
    private const val BASE_APERTURE = 11.0

    /** Below this altitude, atmospheric extinction adds a stop (design §5.4.1). */
    private const val LOW_ALTITUDE_THRESHOLD_DEG = 15.0

    data class Input(
        val iso: Int,
        val aperture: Double,
        /** Moon illumination fraction in [0, 1] from the ephemeris port. */
        val illumination: Double,
        val moonAltitude: Degrees,
    )

    data class Result(
        /** Recommended shutter on the conventional 1/3-stop scale. */
        val shutter: Seconds,
        /** Nominal shutter denominator for display (e.g. 100 for 1/100 s); <= 1 means seconds >= 1. */
        val shutterDenominator: Int,
        /** Stops added by the phase table (0, +1, +1.5, +2, +3, +4). */
        val phaseCorrectionStops: Double,
        /** True when the low-altitude +1 stop correction was applied. */
        val lowAltitudeCorrectionApplied: Boolean,
    )

    /**
     * Phase correction stops by illumination fraction (design §5.4.1 table,
     * including the 60–75% half-stop row). A value exactly on a band boundary
     * belongs to the HIGHER-illumination band (design's boundary rule).
     */
    fun phaseCorrectionStops(illumination: Double): Double = when {
        illumination >= 0.97 -> 0.0
        illumination >= 0.75 -> 1.0
        illumination >= 0.60 -> 1.5
        illumination >= 0.40 -> 2.0
        illumination >= 0.15 -> 3.0
        else -> 4.0
    }

    fun evaluate(input: Input): Result {
        val phaseStops = phaseCorrectionStops(input.illumination)
        val lowAlt = input.moonAltitude.value < LOW_ALTITUDE_THRESHOLD_DEG
        val extraStops = phaseStops + if (lowAlt) 1.0 else 0.0
        // EV equivalence from the f/11 base: shutter scales with (N/11)^2,
        // then the corrections add exposure multiplicatively in stops.
        val rawSeconds = (1.0 / input.iso) *
            (input.aperture / BASE_APERTURE).pow(2) *
            2.0.pow(extraStops)
        val snapped = snapToShutterScale(rawSeconds)
        return Result(
            shutter = snapped,
            shutterDenominator = if (snapped.value < 1.0) Math.round(1.0 / snapped.value).toInt() else 0,
            phaseCorrectionStops = phaseStops,
            lowAltitudeCorrectionApplied = lowAlt,
        )
    }

    /**
     * Conventional nominal shutter durations (seconds), the standard
     * 1/3-stop camera scale: 30 s ... 1 s, then 1/1.3 ... 1/8000 nominal
     * denominators. Snapping picks the nearest entry in log space, which is
     * what real camera dials do.
     */
    private val SCALE_SECONDS: List<Double> = run {
        val list = mutableListOf<Double>()
        var t = 30.0
        while (t >= 1.0) {
            list.add(t)
            t /= 2.0.pow(1.0 / 3.0)
        }
        val denominators = listOf(
            1.3, 1.6, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0,
            30.0, 40.0, 50.0, 60.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 320.0,
            400.0, 500.0, 640.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0,
            3200.0, 4000.0, 5000.0, 6400.0, 8000.0,
        )
        denominators.forEach { list.add(1.0 / it) }
        list.sorted()
    }

    private fun snapToShutterScale(seconds: Double): Seconds {
        val target = log2(seconds)
        var best = SCALE_SECONDS.first()
        var bestDist = Double.MAX_VALUE
        for (candidate in SCALE_SECONDS) {
            val d = kotlin.math.abs(log2(candidate) - target)
            if (d < bestDist) {
                bestDist = d
                best = candidate
            }
        }
        return Seconds(best)
    }
}
