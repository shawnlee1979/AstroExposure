package app.astroexposure.usecase

import app.astroexposure.data.catalog.GridLightPollutionSource
import app.astroexposure.data.catalog.JsonEclipseCatalog
import app.astroexposure.data.catalog.JsonLensCatalog
import app.astroexposure.data.catalog.JsonMeteorShowerCatalog
import app.astroexposure.data.catalog.JsonObjectCatalog
import app.astroexposure.data.ephemeris.AstronomyEngineEphemeris
import app.astroexposure.domain.camera.BuiltInCameras
import app.astroexposure.domain.catalog.EclipseType
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.model.DeepSkyModel
import app.astroexposure.domain.model.DriftModel
import app.astroexposure.domain.model.EclipseModel
import app.astroexposure.domain.model.LandscapeModel
import app.astroexposure.domain.sky.TotalSky
import app.astroexposure.domain.strictness.Strictness
import app.astroexposure.domain.tracking.BuiltInTrackingProfiles
import app.astroexposure.domain.tracking.TrackingProfile
import app.astroexposure.domain.units.ArcsecPerMinute
import app.astroexposure.domain.units.Degrees
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Straight-through tests (supervisor recommendation B): real domain models
 * behind the real :data adapters, exercised through the use-case layer.
 */
class UseCasesTest {

    private val ephemeris = AstronomyEngineEphemeris()
    private val nyc = GeoLocation(
        latitude = Degrees(40.7128),
        longitude = Degrees(-74.0060),
        heightMeters = 0.0,
        zone = ZoneId.of("America/New_York"),
    )
    private val a1 = BuiltInCameras.A1_II
    private val darkSky: TotalSky = TotalSky.fromManualBortle(3, 0.0)

    // --- Wide field -----------------------------------------------------------

    @Test
    fun untrackedWideFieldMatchesDesignGoldenCase() {
        // Design §5.2.1 table: A1 II, 50 mm, f/1.4, dec -29°, k=1 -> 2.0 s.
        val card = WideFieldUseCase().execute(
            WideFieldUseCase.Input(
                camera = a1, focalMm = 50.0, aperture = 1.4,
                declinationDeg = -29.0, strictness = Strictness.STRICT,
                profile = BuiltInTrackingProfiles.UNTRACKED, totalSky = darkSky,
            ),
        )
        assertEquals(false, card.tracked)
        assertEquals(2.0, card.npfSeconds!!, 0.15)
        assertNotNull(card.untrackedIso)
    }

    @Test
    fun trackedWideFieldWithUnmeasuredProfileGivesFormulaOnly() {
        val card = WideFieldUseCase().execute(
            WideFieldUseCase.Input(
                camera = a1, focalMm = 50.0, aperture = 2.0,
                declinationDeg = -29.0, strictness = Strictness.STANDARD,
                profile = BuiltInTrackingProfiles.POLARIS_SINGLE_STAR, totalSky = darkSky,
            ),
        )
        assertTrue(card.tracked)
        assertTrue(card.drift is DriftModel.Outcome.Unmeasured)
        // No seconds may be produced: neither a drift cap nor tracked settings.
        assertEquals(null, card.trackedSettings)
        assertEquals(null, card.npfSeconds)
        assertTrue(card.pixelScaleArcsecPerPixel > 0.0)
    }

    @Test
    fun trackedWideFieldWithMeasuredProfileGivesSettings() {
        val measured = TrackingProfile(
            id = "m", name = "Measured", kind = app.astroexposure.domain.tracking.TrackingKind.TRACKED,
            typicalDrift = ArcsecPerMinute(10.0), worstDrift = ArcsecPerMinute(20.0),
        )
        val card = WideFieldUseCase().execute(
            WideFieldUseCase.Input(
                camera = a1, focalMm = 50.0, aperture = 2.0,
                declinationDeg = -29.0, strictness = Strictness.STANDARD,
                profile = measured, totalSky = darkSky,
            ),
        )
        val drift = card.drift as DriftModel.Outcome.Measured
        // s = 206.265*4.16/50 ≈ 17.2 "/px; 1px at 10 "/min -> ~103 s.
        assertEquals(103.0, drift.maxExposure.value, 5.0)
        assertNotNull(card.trackedSettings)
        assertEquals(640, card.trackedSettings!!.iso)
    }

    // --- Landscape --------------------------------------------------------------

    @Test
    fun landscapeProducesDualFramesAndWorkflow() {
        val result = LandscapeUseCase().execute(
            LandscapeUseCase.Input(
                skyShutterSeconds = 2.0, skyIso = 3200, skyAperture = 1.4,
                skyTracked = false,
                methods = LandscapeModel.ForegroundMethod.entries.toSet(),
                minutesAfterSunset = 20, moonIllumination = 0.2,
            ),
        )
        assertEquals(3, result.foregroundFrames.size)
        assertEquals(5, result.workflowSteps.size)
        assertEquals(2.0, result.skyFrame.shutterSeconds, 1e-9)
    }

    // --- Deep sky -----------------------------------------------------------------

    @Test
    fun deepSkyReadyForBuiltInCameraUnderDarkSky() {
        val card = DeepSkyUseCase().execute(
            DeepSkyUseCase.Input(
                camera = a1, aperture = 2.0, totalSky = darkSky,
                strictness = Strictness.STANDARD,
                targetClass = DeepSkyModel.TargetClass.BRIGHT_CLUSTER_NEBULA,
                profile = BuiltInTrackingProfiles.UNTRACKED, focalMm = 50.0,
            ),
        )
        val ready = card.outcome as DeepSkyModel.Outcome.Ready
        assertEquals(640, ready.recommendedIso)
        assertTrue(ready.subExposureSeconds > 0.0)
        assertTrue(ready.frameCount > 0)
    }

    @Test
    fun deepSkyDegradesWhenReadNoiseMissing() {
        val bare = app.astroexposure.domain.camera.Camera(
            id = "bare", displayName = "Bare",
            sensorWidthMm = 36.0, sensorHeightMm = 24.0, pixelsX = 6000, pixelsY = 4000,
        )
        val card = DeepSkyUseCase().execute(
            DeepSkyUseCase.Input(
                camera = bare, aperture = 2.0, totalSky = darkSky,
                strictness = Strictness.STANDARD,
                targetClass = DeepSkyModel.TargetClass.GENERAL_GALAXY_DARK_NEBULA,
                profile = null, focalMm = null,
            ),
        )
        val degraded = card.outcome as DeepSkyModel.Outcome.Degraded
        assertEquals(DeepSkyModel.DegradedReason.READ_NOISE_MISSING, degraded.reason)
    }

    @Test
    fun deepSkyFlagsUnverifiedGainBody() {
        val card = DeepSkyUseCase().execute(
            DeepSkyUseCase.Input(
                camera = BuiltInCameras.ZV_E1, aperture = 2.0, totalSky = darkSky,
                strictness = Strictness.STANDARD,
                targetClass = DeepSkyModel.TargetClass.BRIGHT_CLUSTER_NEBULA,
                profile = null, focalMm = null,
            ),
        )
        val ready = card.outcome as DeepSkyModel.Outcome.Ready
        assertEquals(800, ready.recommendedIso)
        assertTrue(ready.unverifiedGainFlag)
    }

    // --- Eclipse --------------------------------------------------------------------

    @Test
    fun eclipseGateBlocksParametersUntilAcknowledged() {
        val useCase = EclipseUseCase(ephemeris)
        val blocked = useCase.solar(EclipseType.TOTAL, 8.0, 100, acknowledged = false)
        assertTrue(blocked.gate.blocked)
        assertTrue(blocked.rows.isEmpty())
        assertTrue(blocked.totalityBracketSeconds.isEmpty())
    }

    @Test
    fun annularEclipseStaysFilterLockedWithSingleRow() {
        val useCase = EclipseUseCase(ephemeris)
        val card = useCase.solar(EclipseType.ANNULAR, 8.0, 100, acknowledged = true)
        assertTrue(card.filterLocked)
        assertEquals(1, card.rows.size)
        assertEquals(EclipseModel.Phenomenon.PARTIAL_PHASE, card.rows[0].phenomenon)
        assertEquals(
            EclipseModel.FilterRequirement.REQUIRED_ND5, card.rows[0].filterRequirement,
        )
    }

    @Test
    fun totalEclipseShowsFullTableAndBracket() {
        val useCase = EclipseUseCase(ephemeris)
        val card = useCase.solar(EclipseType.TOTAL, 8.0, 100, acknowledged = true)
        assertEquals(EclipseModel.Phenomenon.entries.size, card.rows.size)
        assertEquals(15, card.totalityBracketSeconds.size)
    }

    @Test
    fun moonCardUsesEphemerisIllumination() {
        val useCase = EclipseUseCase(ephemeris)
        val instant = LocalDate.of(2026, 8, 12).atTime(22, 0)
            .atZone(nyc.zone).toInstant()
        val card = useCase.moon(nyc, instant, iso = 100, aperture = 11.0)
        assertTrue(card.illumination in 0.0..1.0)
        assertTrue(card.result.shutter.value > 0.0)
    }

    // --- Meteor -----------------------------------------------------------------------

    @Test
    fun meteorCardForPerseids() {
        val showers = JsonMeteorShowerCatalog()
        val perseids = showers.showers().first { it.iauCode == "PER" }
        val card = MeteorUseCase(ephemeris).execute(
            MeteorUseCase.Input(
                shower = perseids, camera = a1, focalMm = 35.0, aperture = 1.8,
                totalSky = darkSky, strictness = Strictness.STANDARD,
                shootingHours = 3.0, location = nyc, date = LocalDate.of(2026, 8, 12),
            ),
        )
        assertTrue(card.result.singleFrameSeconds > 0.0)
        assertTrue(card.result.frameCount > 0)
        assertTrue(kotlin.math.abs(card.daysFromPeak) <= 6.0)
    }

    // --- Tonight overview ----------------------------------------------------------------

    @Test
    fun tonightOverviewAssemblesAllSections() {
        val useCase = TonightOverviewUseCase(
            ephemeris = ephemeris,
            objectCatalog = JsonObjectCatalog(),
            eclipseCatalog = JsonEclipseCatalog(),
            showerCatalog = JsonMeteorShowerCatalog(),
            lightPollution = GridLightPollutionSource(),
        )
        val result = useCase.execute(nyc, LocalDate.of(2026, 8, 12))
        assertNotNull(result.night)
        assertNotNull(result.sunEvents.sunset)
        assertTrue(result.moonIllumination in 0.0..1.0)
        // Moments are chronological.
        assertEquals(result.moments.map { it.instant }.sorted(), result.moments.map { it.instant })
        assertTrue(result.moments.isNotEmpty())
        // Static shortlist obeys its own rules.
        assertTrue(result.recommendations.size <= 10)
        for (rec in result.recommendations) {
            assertTrue((rec.obj.magnitude ?: 99.0) <= 6.5)
            assertTrue(rec.pass.culminationAltitude.value >= 40.0)
        }
    }

    @Test
    fun tonightOverviewFindsPerseidsEventNearPeak() {
        val useCase = TonightOverviewUseCase(
            ephemeris = ephemeris,
            showerCatalog = JsonMeteorShowerCatalog(),
        )
        val result = useCase.execute(nyc, LocalDate.of(2026, 8, 12))
        assertTrue(
            result.events.any {
                it is TonightOverviewUseCase.Event.Shower && it.shower.iauCode == "PER"
            },
        )
    }

    @Test
    fun lensCatalogueSeedsFiftyTwoLenses() {
        // The settings screen seeds the equipment library from this catalogue.
        assertEquals(52, JsonLensCatalog().builtInLenses().size)
    }
}
