package app.astroexposure.usecase

import app.astroexposure.domain.ephemeris.EphemerisProvider
import app.astroexposure.domain.ephemeris.GalacticCenterWindow
import app.astroexposure.domain.ephemeris.NightWindow
import app.astroexposure.domain.geo.GeoLocation
import java.time.Instant
import java.time.LocalDate

/**
 * Simplified "tonight overview" (design §6.4 phase-1 scope): astronomical
 * night bounds, moon phase and rise/set, and the galactic-centre window for
 * one civil date at one location. The date and location are supplied by the
 * application layer (device clock read happens there, never in the domain).
 */
class TonightOverviewUseCase(private val ephemeris: EphemerisProvider) {

    data class Result(
        val night: NightWindow?,
        /** Illumination fraction 0..1, sampled at dusk (or at local noon when there is no night). */
        val moonIllumination: Double,
        val moonrise: Instant?,
        val moonset: Instant?,
        val galacticCenter: GalacticCenterWindow?,
    )

    fun execute(location: GeoLocation, date: LocalDate): Result {
        val night = ephemeris.astronomicalNight(location, date)
        val riseSet = ephemeris.moonRiseSet(location, date)
        val illuminationSample = night?.dusk
            ?: date.atTime(12, 0).atZone(location.zone).toInstant()
        return Result(
            night = night,
            moonIllumination = ephemeris.moonIllumination(illuminationSample),
            moonrise = riseSet.moonrise,
            moonset = riseSet.moonset,
            galacticCenter = ephemeris.galacticCenterWindow(location, date),
        )
    }
}
