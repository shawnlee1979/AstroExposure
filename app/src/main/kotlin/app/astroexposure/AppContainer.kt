package app.astroexposure

import app.astroexposure.data.ephemeris.AstronomyEngineEphemeris
import app.astroexposure.domain.ephemeris.EphemerisProvider
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.units.Degrees
import app.astroexposure.usecase.NpfCalculatorUseCase
import app.astroexposure.usecase.TonightOverviewUseCase
import java.time.ZoneId

/**
 * Hand-written composition root (DEVELOPMENT-RULES.md E.3: constructor
 * injection, no DI framework). The UI receives use cases from here and never
 * touches adapters or the ephemeris engine directly.
 */
class AppContainer {

    val ephemeris: EphemerisProvider = AstronomyEngineEphemeris()

    val tonightOverview = TonightOverviewUseCase(ephemeris)
    val npfCalculator = NpfCalculatorUseCase()

    companion object {
        /**
         * Phase-1 placeholder observing site until the location input lands
         * (phase 2): New York City. The UI labels it as the built-in sample
         * site so it can never be mistaken for the user's real location.
         */
        val DEFAULT_LOCATION = GeoLocation(
            latitude = Degrees(40.7128),
            longitude = Degrees(-74.0060),
            heightMeters = 0.0,
            zone = ZoneId.of("America/New_York"),
        )
    }
}
