package app.astroexposure.support

import app.astroexposure.domain.catalog.LightPollutionSource
import app.astroexposure.domain.catalog.MeteorShower
import app.astroexposure.domain.ephemeris.CelestialBody
import app.astroexposure.domain.ephemeris.EphemerisProvider
import app.astroexposure.domain.ephemeris.EquatorialCoordinates
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.sky.BaselineSource
import app.astroexposure.domain.sky.MoonlightModel
import app.astroexposure.domain.sky.TotalSky
import app.astroexposure.domain.strictness.Strictness
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Time health check (design §2.2): warn when the system time zone disagrees
 * with the selected site's zone, or when a GPS time fix differs from the
 * system clock by more than 60 s. Pure: every input is supplied by the
 * application boundary, which is where the device clock may be read.
 */
object TimeHealthCheck {

    enum class Warning { ZONE_MISMATCH, CLOCK_SKEW }

    data class Result(
        val warnings: List<Warning>,
        /** Absolute system-vs-GPS skew in seconds when a GPS time was available. */
        val clockSkewSeconds: Long?,
    ) {
        val healthy: Boolean get() = warnings.isEmpty()
    }

    /** Skew threshold from design §2.2. */
    const val SKEW_THRESHOLD_SECONDS = 60L

    fun evaluate(
        systemZone: ZoneId,
        locationZone: ZoneId,
        systemNow: Instant,
        gpsTime: Instant?,
    ): Result {
        val warnings = mutableListOf<Warning>()
        if (systemZone.id != locationZone.id) warnings += Warning.ZONE_MISMATCH
        val skew = gpsTime?.let { abs(Duration.between(it, systemNow).seconds) }
        if (skew != null && skew > SKEW_THRESHOLD_SECONDS) warnings += Warning.CLOCK_SKEW
        return Result(warnings = warnings, clockSkewSeconds = skew)
    }
}

/**
 * System strictness recommendation (design §5, general rule 1): an
 * experience-based rule ("经验口径"), always shown with its reason and always
 * overridable per calculation or as the settings default.
 */
object StrictnessRecommendation {

    enum class Reason {
        /** Untracked with a long focal length or small pixels: trailing bites first. */
        UNTRACKED_TIGHT,

        /** Tracked with a measured drift profile: the typical value is trustworthy. */
        TRACKED_MEASURED,

        /** Large pixels or a short focal length tolerate more. */
        LARGE_PIXEL_OR_SHORT_FOCAL,

        /** Nothing special: the middle tier. */
        DEFAULT_STANDARD,
    }

    data class Result(val strictness: Strictness, val reason: Reason)

    fun recommend(
        tracked: Boolean,
        driftMeasured: Boolean,
        focalMm: Double,
        pixelPitchUm: Double,
    ): Result = when {
        !tracked && (focalMm >= 35.0 || pixelPitchUm <= 5.0) ->
            Result(Strictness.STRICT, Reason.UNTRACKED_TIGHT)
        tracked && driftMeasured ->
            Result(Strictness.STANDARD, Reason.TRACKED_MEASURED)
        pixelPitchUm > 5.0 || focalMm <= 24.0 ->
            Result(Strictness.STANDARD, Reason.LARGE_PIXEL_OR_SHORT_FOCAL)
        else -> Result(Strictness.STANDARD, Reason.DEFAULT_STANDARD)
    }
}

/**
 * The composed sky at one instant (design §5, general rule 2): atlas or
 * manual-Bortle baseline plus the moonlight contribution for a target
 * direction, all via the domain's single implementations.
 *
 * Known approximation: the ephemeris port exposes the Moon's horizontal
 * position and a target's altitude, but not a target's azimuth or the Moon's
 * equatorial coordinates, so the Moon–target separation is approximated by
 * the altitude difference (its lower bound). The moonlight term is therefore
 * an upper-bound estimate; this is recorded as a port gap, not hidden.
 */
data class SkyContext(
    val totalSky: TotalSky,
    val moonIllumination: Double,
    val moonAltitudeDeg: Double,
    val moonUp: Boolean,
    val moonlightMcdPerM2: Double,
)

object SkyAssembly {

    fun compose(
        ephemeris: EphemerisProvider,
        lightPollution: LightPollutionSource?,
        location: GeoLocation,
        instant: Instant,
        target: EquatorialCoordinates,
        manualBortle: Int?,
    ): SkyContext {
        val illumination = ephemeris.moonIllumination(instant)
        val moonPos = ephemeris.horizontalPosition(location, CelestialBody.MOON, instant)
        val targetAlt = ephemeris.altitudeOf(location, target, instant)
        val separationApprox = abs(moonPos.altitude.value - targetAlt.value)
        val moonlight = MoonlightModel.contributionMcdPerM2(
            illumination = illumination,
            moonAltitudeDeg = moonPos.altitude.value,
            separationDeg = separationApprox,
            targetAltitudeDeg = targetAlt.value,
        )
        val totalSky = when {
            manualBortle != null -> TotalSky.fromManualBortle(manualBortle, moonlight)
            else -> {
                val atlas = lightPollution?.artificialBrightnessMcdPerM2(location)
                if (atlas != null) {
                    TotalSky.compose(atlas, BaselineSource.ATLAS, moonlight)
                } else {
                    // No atlas coverage at this point: explicit no-baseline
                    // degradation (design §2.8) — the UI offers manual Bortle.
                    TotalSky.noBaseline(moonlight)
                }
            }
        }
        return SkyContext(
            totalSky = totalSky,
            moonIllumination = illumination,
            moonAltitudeDeg = moonPos.altitude.value,
            moonUp = moonPos.altitude.value > 0.0,
            moonlightMcdPerM2 = moonlight,
        )
    }
}

/**
 * Approximate civil dates for a shower's solar longitudes. The catalogue
 * speaks solar longitude only (IMO convention); converting with the mean
 * solar motion from the March equinox gives dates good to about ±2 days,
 * which is all the overview (±3-day event window, design §6.4) and the
 * days-from-peak figure claim. The UI never presents these as exact peak
 * instants.
 */
object ShowerDates {

    /** Mean solar longitude rate, degrees/day (360° per tropical year). */
    private const val MEAN_RATE_DEG_PER_DAY = 360.0 / 365.25636

    /** The March equinox falls on Mar 20 (UTC) in the catalogue era. */
    private fun equinox(year: Int): LocalDate = LocalDate.of(year, 3, 20)

    /** Closest civil date at which the Sun reaches [solarLongitudeDeg] near [reference]. */
    fun approxDate(solarLongitudeDeg: Double, reference: LocalDate): LocalDate {
        val lon = ((solarLongitudeDeg % 360.0) + 360.0) % 360.0
        val days = (lon / MEAN_RATE_DEG_PER_DAY).roundToLong()
        return listOf(reference.year - 1, reference.year, reference.year + 1)
            .map { equinox(it).plusDays(days) }
            .minByOrNull { abs(ChronoUnit.DAYS.between(reference, it)) }!!
    }

    /** Signed whole days from [date] to the shower's approximate peak date. */
    fun daysFromPeak(shower: MeteorShower, date: LocalDate): Double {
        val peak = approxDate(shower.peakLon, date)
        return ChronoUnit.DAYS.between(peak, date).toDouble()
    }

    /** True when [date] lies inside the shower's approximate activity window. */
    fun isActive(shower: MeteorShower, date: LocalDate): Boolean {
        val start = approxDate(shower.activityStartLon, date)
        val end = approxDate(shower.activityEndLon, date)
        // Windows that wrap the year end produce start > end after the
        // closest-date projection; treat those as active on either side.
        return if (!end.isBefore(start)) {
            !date.isBefore(start) && !date.isAfter(end)
        } else {
            !date.isBefore(start) || !date.isAfter(end)
        }
    }
}
