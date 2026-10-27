package app.astroexposure.domain.model

import app.astroexposure.domain.catalog.EclipseType
import app.astroexposure.domain.units.Seconds
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

/**
 * Solar-eclipse exposure reference (design §5.4.2) — a TABLE model, not a
 * formula model. The table follows the structure of Jerry Lodriguss's
 * AstroPix solar-eclipse exposure table at ISO 100; entries are recorded
 * exactly as the design table gives them.
 *
 * Column policy: the design table gives f/2.8, f/5.6, f/8, f/11 and f/16
 * (no f/4 column). Queries use the NEAREST recorded column plus an exact
 * EV correction for the aperture difference — no interpolated column is
 * fabricated. ISO changes shift by EV (t scales with 100/ISO), and the
 * result is snapped with the shared [ShutterScale] (D.4).
 *
 * This model is an exposure reference only; it is not a safety guide
 * (design §5.4.2 positioning statement). The [SafetyGate] logic is part of
 * the model so it is unit-testable as safety-critical code.
 */
object EclipseModel {

    /** Apertures with recorded table columns, in order. */
    val TABLE_APERTURES = listOf(2.8, 5.6, 8.0, 11.0, 16.0)

    /** Eclipse phenomena (rows of the design §5.4.2 table). */
    enum class Phenomenon {
        PARTIAL_PHASE, DIAMOND_RING, BAILY_BEADS, CHROMOSPHERE, PROMINENCE,
        CORONA_0_5_RS, CORONA_1_0_RS, CORONA_2_0_RS, CORONA_4_0_RS, CORONA_8_0_RS,
    }

    /** Filter requirement class for a phenomenon (design §5.4.2). */
    enum class FilterRequirement {
        /** ND5.0-class solar filter mandatory (partial phases). */
        REQUIRED_ND5,

        /** No filter, and only inside the totality window. */
        NONE_TOTALITY_ONLY,

        /** Transition phenomena lasting seconds around totality. */
        TRANSITION_SECONDS,
    }

    /**
     * Table seconds per phenomenon, aligned with [TABLE_APERTURES];
     * null = the design table records no value ("—") for that cell.
     * ISO 100; partial-phase row assumes the ND5.0 filter.
     */
    private val TABLE_SECONDS: Map<Phenomenon, List<Double?>> = mapOf(
        Phenomenon.PARTIAL_PHASE to listOf(1.0 / 8000, 1.0 / 2000, 1.0 / 1000, 1.0 / 500, 1.0 / 250),
        Phenomenon.DIAMOND_RING to listOf(1.0 / 500, 1.0 / 125, 1.0 / 60, 1.0 / 30, 1.0 / 15),
        Phenomenon.BAILY_BEADS to listOf(null, 1.0 / 8000, 1.0 / 4000, 1.0 / 2000, 1.0 / 1000),
        Phenomenon.CHROMOSPHERE to listOf(null, 1.0 / 4000, 1.0 / 2000, 1.0 / 1000, 1.0 / 500),
        Phenomenon.PROMINENCE to listOf(1.0 / 8000, 1.0 / 2000, 1.0 / 1000, 1.0 / 500, 1.0 / 250),
        Phenomenon.CORONA_0_5_RS to listOf(1.0 / 125, 1.0 / 30, 1.0 / 15, 1.0 / 8, 1.0 / 4),
        Phenomenon.CORONA_1_0_RS to listOf(1.0 / 30, 1.0 / 8, 1.0 / 4, 1.0 / 2, 1.0),
        Phenomenon.CORONA_2_0_RS to listOf(1.0 / 15, 1.0 / 4, 1.0 / 2, 1.0, 2.0),
        Phenomenon.CORONA_4_0_RS to listOf(1.0 / 4, 1.0, 2.0, 4.0, 8.0),
        Phenomenon.CORONA_8_0_RS to listOf(1.0, 4.0, 8.0, 16.0, 32.0),
    )

    /** Raw recorded cell (ISO 100), or null when the table records none. */
    fun tableSeconds(phenomenon: Phenomenon, aperture: Double): Double? {
        val col = nearestColumn(aperture)
        return TABLE_SECONDS.getValue(phenomenon)[col]
    }

    private fun nearestColumn(aperture: Double): Int {
        var best = 0
        var bestDist = Double.MAX_VALUE
        TABLE_APERTURES.forEachIndexed { idx, colAperture ->
            val d = abs(log2(aperture / colAperture))
            if (d < bestDist) {
                bestDist = d
                best = idx
            }
        }
        return best
    }

    /**
     * Recommended shutter for [phenomenon] at [aperture] and [iso]: nearest
     * recorded column, EV-corrected for the aperture difference from that
     * column and for ISO (base ISO 100), snapped to the camera scale.
     * Returns null when the nearest column records no value for the row.
     */
    fun shutterFor(phenomenon: Phenomenon, aperture: Double, iso: Int): Seconds? {
        val col = nearestColumn(aperture)
        val base = TABLE_SECONDS.getValue(phenomenon)[col] ?: return null
        val columnAperture = TABLE_APERTURES[col]
        val corrected = base * (aperture / columnAperture).pow(2) * (100.0 / iso)
        // The table's longest cell is 32 s, beyond the dial scale's 30 s top;
        // return it unsnapped rather than silently shortening it.
        return if (corrected > 30.0) Seconds(corrected) else ShutterScale.snap(corrected)
    }

    fun filterRequirement(phenomenon: Phenomenon): FilterRequirement = when (phenomenon) {
        Phenomenon.PARTIAL_PHASE -> FilterRequirement.REQUIRED_ND5
        Phenomenon.DIAMOND_RING, Phenomenon.BAILY_BEADS -> FilterRequirement.TRANSITION_SECONDS
        else -> FilterRequirement.NONE_TOTALITY_ONLY
    }

    data class EclipseTable(
        val rows: List<Phenomenon>,
        /** True when the whole table is locked to filter mode (annular / partial events). */
        val lockedToFilter: Boolean,
    )

    /**
     * The table visible for an event type (design §5.4.2 rule 3): annular
     * and partial events have no unfiltered window, so only the partial-phase
     * row is returned, locked to filter mode. Total (and hybrid) events
     * return the full table.
     */
    fun tableFor(eclipseType: EclipseType): EclipseTable = when (eclipseType) {
        EclipseType.ANNULAR, EclipseType.PARTIAL ->
            EclipseTable(rows = listOf(Phenomenon.PARTIAL_PHASE), lockedToFilter = true)
        else ->
            EclipseTable(rows = Phenomenon.entries, lockedToFilter = false)
    }

    /**
     * Totality bracket sequence (design §5.4.2 rule 1): full-stop steps from
     * 1/2000 s to 8 s, as raw seconds.
     */
    fun totalityBracket(): List<Seconds> = listOf(
        1.0 / 2000, 1.0 / 1000, 1.0 / 500, 1.0 / 250, 1.0 / 125, 1.0 / 60, 1.0 / 30,
        1.0 / 15, 1.0 / 8, 1.0 / 4, 1.0 / 2, 1.0, 2.0, 4.0, 8.0,
    ).map { Seconds(it) }
}

/**
 * The eclipse safety gate (design §5.4.2): a pure evaluation so the
 * safety-critical branches are unit-tested. Entering the eclipse module
 * requires an explicit acknowledgement on EVERY entry; annular events
 * never leave filter mode.
 */
object SafetyGate {

    data class GateResult(
        /** True when the user has not acknowledged the filter requirement. */
        val blocked: Boolean,
        /** True when the event type keeps the table locked to filter mode throughout. */
        val filterLocked: Boolean,
    )

    fun evaluate(acknowledged: Boolean, eclipseType: EclipseType): GateResult = GateResult(
        blocked = !acknowledged,
        filterLocked = eclipseType == EclipseType.ANNULAR || eclipseType == EclipseType.PARTIAL,
    )
}
