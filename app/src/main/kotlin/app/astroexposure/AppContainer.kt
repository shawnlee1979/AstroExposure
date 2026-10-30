package app.astroexposure

import android.content.Context
import androidx.room.Room
import app.astroexposure.data.catalog.GridLightPollutionSource
import app.astroexposure.data.catalog.JsonConstellationCatalog
import app.astroexposure.data.catalog.JsonConstellationLines
import app.astroexposure.data.catalog.JsonEclipseCatalog
import app.astroexposure.data.catalog.JsonLensCatalog
import app.astroexposure.data.catalog.JsonMeteorShowerCatalog
import app.astroexposure.data.catalog.JsonObjectCatalog
import app.astroexposure.data.catalog.JsonStarCatalog
import app.astroexposure.data.catalog.JsonWideFieldCatalog
import app.astroexposure.data.ephemeris.AstronomyEngineEphemeris
import app.astroexposure.domain.catalog.ConstellationCatalog
import app.astroexposure.domain.catalog.ConstellationLines
import app.astroexposure.domain.catalog.EclipseCatalog
import app.astroexposure.domain.catalog.LensCatalog
import app.astroexposure.domain.catalog.LightPollutionSource
import app.astroexposure.domain.catalog.MeteorShowerCatalog
import app.astroexposure.domain.catalog.ObjectCatalog
import app.astroexposure.domain.catalog.StarCatalog
import app.astroexposure.domain.catalog.WideFieldCatalog
import app.astroexposure.domain.ephemeris.EphemerisProvider
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.orientation.DeviceOrientationProvider
import app.astroexposure.domain.units.Degrees
import app.astroexposure.location.LocationProvider
import app.astroexposure.orientation.AndroidOrientationProvider
import app.astroexposure.persistence.AppRepository
import app.astroexposure.persistence.AstroDatabase
import app.astroexposure.usecase.DeepSkyUseCase
import app.astroexposure.usecase.EclipseUseCase
import app.astroexposure.usecase.LandscapeUseCase
import app.astroexposure.usecase.MeteorUseCase
import app.astroexposure.usecase.TonightOverviewUseCase
import app.astroexposure.usecase.WideFieldUseCase
import java.time.ZoneId

/**
 * Hand-written composition root (DEVELOPMENT-RULES.md E.3: constructor
 * injection, no DI framework). The UI receives use cases and the repository
 * from here and never touches adapters or the ephemeris engine directly.
 */
class AppContainer(context: Context) {

    val ephemeris: EphemerisProvider = AstronomyEngineEphemeris()
    val objectCatalog: ObjectCatalog = JsonObjectCatalog()
    val eclipseCatalog: EclipseCatalog = JsonEclipseCatalog()
    val showerCatalog: MeteorShowerCatalog = JsonMeteorShowerCatalog()
    val constellationCatalog: ConstellationCatalog = JsonConstellationCatalog()
    val wideFieldCatalog: WideFieldCatalog = JsonWideFieldCatalog()
    val starCatalog: StarCatalog = JsonStarCatalog()
    val constellationLines: ConstellationLines = JsonConstellationLines()
    val lensCatalog: LensCatalog = JsonLensCatalog()
    val lightPollution: LightPollutionSource = GridLightPollutionSource()

    private val database: AstroDatabase = Room.databaseBuilder(
        context.applicationContext, AstroDatabase::class.java, "astroexposure.db",
    ).addMigrations(AstroDatabase.MIGRATION_1_2).build()

    val repository = AppRepository(database, lensCatalog)
    val locationProvider = LocationProvider(context.applicationContext)
    val orientationProvider: DeviceOrientationProvider =
        AndroidOrientationProvider(context.applicationContext)

    val tonightOverview = TonightOverviewUseCase(
        ephemeris, objectCatalog, eclipseCatalog, showerCatalog, lightPollution,
        constellationCatalog, wideFieldCatalog, starCatalog, constellationLines,
    )
    val wideField = WideFieldUseCase()
    val landscape = LandscapeUseCase()
    val deepSky = DeepSkyUseCase()
    val eclipse = EclipseUseCase(ephemeris)
    val meteor = MeteorUseCase(ephemeris)

    companion object {
        /**
         * Fallback observing site until one of the three location channels
         * supplies a real one: New York City. The UI labels it as the
         * built-in sample site so it can never be mistaken for the user's
         * real location.
         */
        val DEFAULT_LOCATION = GeoLocation(
            latitude = Degrees(40.7128),
            longitude = Degrees(-74.0060),
            heightMeters = 0.0,
            zone = ZoneId.of("America/New_York"),
        )
    }
}
