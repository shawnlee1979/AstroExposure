package app.astroexposure.domain.model

import app.astroexposure.domain.units.Seconds
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

/**
 * The conventional camera shutter scale and the snap-to-scale operation —
 * the single implementation in the codebase (D.4), shared by every model
 * that must land on a real camera dial value (Moon, Eclipse, WideField).
 *
 * Scale: 30 s down to 1 s in 1/3-stop steps, then the conventional nominal
 * denominators 1/1.3 … 1/8000. Snapping picks the nearest entry in log
 * space, which is what real camera dials do.
 */
object ShutterScale {

    val SCALE_SECONDS: List<Double> = run {
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

    /** Nearest conventional shutter duration to [seconds] (log-space nearest). */
    fun snap(seconds: Double): Seconds {
        require(seconds > 0.0) { "shutter seconds must be positive, was $seconds" }
        val target = log2(seconds)
        var best = SCALE_SECONDS.first()
        var bestDist = Double.MAX_VALUE
        for (candidate in SCALE_SECONDS) {
            val d = abs(log2(candidate) - target)
            if (d < bestDist) {
                bestDist = d
                best = candidate
            }
        }
        return Seconds(best)
    }

    /** Nominal denominator for display (e.g. 100 for 1/100 s); 0 when the duration is >= 1 s. */
    fun denominatorOf(shutter: Seconds): Int =
        if (shutter.value < 1.0) Math.round(1.0 / shutter.value).toInt() else 0
}
