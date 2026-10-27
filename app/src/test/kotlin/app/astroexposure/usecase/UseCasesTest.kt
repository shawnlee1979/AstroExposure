package app.astroexposure.usecase

import app.astroexposure.data.catalog.GridLightPollutionSource
import app.astroexposure.data.catalog.JsonEclipseCatalog
import app.astroexposure.data.catalog.JsonLensCatalog
import app.astroexposure.data.catalog.JsonMeteorShowerCatalog
import app.astroexposure.data.catalog.JsonObjectCatalog
import app.astroexposure.data.ephemeris.AstronomyEngineEphemeris
import app.astroexposure.domain.camera.BuiltInCameras
import app.astroexposure.data.catalog.JsonParseException
import app.astroexposure.domain.catalog.DeepSkyObject
import app.astroexposure.domain.catalog.EclipseCatalog
import app.astroexposure.domain.catalog.EclipseRecord
import app.astroexposure.domain.catalog.EclipseType
import app.astroexposure.domain.catalog.MeteorShower
import app.astroexposure.domain.catalog.MeteorShowerCatalog
import app.astroexposure.domain.catalog.ObjectCatalog
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Straight-through tests: real domain models behind the real :data
 * adapters, exercised through the use-case layer.
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
        // Static shortlist obeys its own rules. (Regression coverage: the
        // production catalogue once failed to load over a misspelt type
        // string in the pipeline output, and the shortlist silently
        // degraded to empty; with the catalogue healthy the rules below
        // must hold on real recommendations.)
        assertTrue(result.recommendationsAvailable)
        assertTrue(
            "healthy catalogue must produce a non-empty shortlist",
            result.recommendations.isNotEmpty(),
        )
        assertTrue(result.recommendations.size <= 10)
        for (rec in result.recommendations) {
            assertTrue((rec.obj.magnitude ?: 99.0) <= 6.5)
            assertTrue(rec.pass.culminationAltitude.value >= 40.0)
        }
    }

    @Test
    fun tonightTimelineSpansSunsetMinusOneHourToSunrisePlusOneHour() {
        val useCase = TonightOverviewUseCase(
            ephemeris = ephemeris,
            lightPollution = GridLightPollutionSource(),
        )
        val date = LocalDate.of(2026, 8, 12)
        val result = useCase.execute(nyc, date)
        val timeline = result.timeline
        val nextSunrise = ephemeris.sunEvents(nyc, date.plusDays(1)).sunrise
        assertNotNull(result.sunEvents.sunset)
        assertNotNull(nextSunrise)
        assertEquals(result.sunEvents.sunset!!.minusSeconds(3600), timeline.axisStart)
        assertEquals(nextSunrise!!.plusSeconds(3600), timeline.axisEnd)
        // Samples run at the 5-minute step across the whole axis.
        assertTrue(timeline.samples.size > 100)
        for (i in 0 until timeline.samples.size - 1) {
            assertEquals(300L, timeline.samples[i + 1].instant.epochSecond - timeline.samples[i].instant.epochSecond)
        }
        // Bands cover the axis end to end and include the astronomical night.
        assertEquals(timeline.axisStart, timeline.bandSegments.first().start)
        assertEquals(timeline.axisEnd, timeline.bandSegments.last().end)
        assertTrue(
            timeline.bandSegments.any {
                it.band == app.astroexposure.domain.timeline.NightTimeline.SunBand.ASTRONOMICAL_NIGHT
            },
        )
        assertTrue(timeline.hasAstronomicalNight)
        // The galactic-centre interval, when present, lies inside the axis.
        timeline.galacticCenterInterval?.let {
            assertTrue(!it.start.isBefore(timeline.axisStart) && !it.end.isAfter(timeline.axisEnd))
        }
    }

    @Test
    fun tonightTimelineDegradesWithoutAstronomicalNight() {
        // Tromsø in mid-June: the Sun never drops to -18°, so there is no
        // astronomical night; the axis must still render (curves + bands
        // derived from the samples) and say so via the flag.
        val tromso = GeoLocation(
            latitude = Degrees(69.6492),
            longitude = Degrees(18.9553),
            heightMeters = 0.0,
            zone = ZoneId.of("Europe/Oslo"),
        )
        val useCase = TonightOverviewUseCase(ephemeris = ephemeris)
        val result = useCase.execute(tromso, LocalDate.of(2026, 6, 15))
        assertEquals(null, result.night)
        assertFalse(result.timeline.hasAstronomicalNight)
        assertTrue(result.timeline.samples.isNotEmpty())
        assertTrue(
            result.timeline.bandSegments.none {
                it.band == app.astroexposure.domain.timeline.NightTimeline.SunBand.ASTRONOMICAL_NIGHT
            },
        )
    }

    @Test
    fun tonightLightPollutionInfoCarriesGridValueAndVersion() {
        val source = GridLightPollutionSource()
        val useCase = TonightOverviewUseCase(ephemeris = ephemeris, lightPollution = source)
        val result = useCase.execute(nyc, LocalDate.of(2026, 8, 12))
        val info = result.lightPollutionInfo
        assertNotNull(info)
        assertEquals(source.dataVersion, info!!.dataVersion)
        assertNotNull(info.artificialMcdPerM2)
        assertTrue(info.bortleEstimate in 1..9)
        // Open ocean has no grid data: the value is null, never guessed.
        val ocean = GeoLocation(
            latitude = Degrees(0.0), longitude = Degrees(-150.0),
            heightMeters = 0.0, zone = ZoneId.of("UTC"),
        )
        val oceanInfo = useCase.execute(ocean, LocalDate.of(2026, 8, 12)).lightPollutionInfo
        assertNotNull(oceanInfo)
        assertEquals(null, oceanInfo!!.artificialMcdPerM2)
        assertEquals(null, oceanInfo.bortleEstimate)
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
        assertEquals(TonightOverviewUseCase.EventsDisplay.LIST, result.eventsDisplay)
    }

    @Test
    fun lensCatalogueSeedsFiftyTwoLenses() {
        // The settings screen seeds the equipment library from this catalogue.
        assertEquals(52, JsonLensCatalog().builtInLenses().size)
    }

    // --- Tonight degradation (review items 1/2/5/6/7) ---------------------------

    private fun objectCatalogThrowing(failure: () -> Throwable): ObjectCatalog =
        object : ObjectCatalog {
            override fun allObjects(): List<DeepSkyObject> = throw failure()
            override fun byId(id: String): DeepSkyObject? = null
            override val dataVersion: String = "broken"
        }

    @Test
    fun tonightDegradesOnEveryCatalogueFailureType() {
        // The degradation catch must cover the whole load-failure family:
        // schema/state errors, JSON syntax errors (an IllegalArgument
        // Exception via JsonParseException) and date parse errors alike.
        val failures: List<() -> Throwable> = listOf(
            { IllegalStateException("schema mismatch") },
            { JsonParseException("bad document", 0) },
            { java.time.format.DateTimeParseException("bad date", "2026-13-40", 0) },
        )
        for (failure in failures) {
            val useCase = TonightOverviewUseCase(
                ephemeris = ephemeris,
                objectCatalog = objectCatalogThrowing(failure),
            )
            val result = useCase.execute(nyc, LocalDate.of(2026, 8, 12))
            assertFalse(result.recommendationsAvailable)
            assertTrue(result.recommendations.isEmpty())
            // The rest of the overview still assembles.
            assertNotNull(result.sunEvents.sunset)
        }
    }

    @Test
    fun tonightAvailabilityDoesNotLeakAcrossCalls() {
        // The use case is a singleton: a failed call must not poison the
        // next, successful one on the same instance.
        var fail = true
        val flaky = object : ObjectCatalog {
            override fun allObjects(): List<DeepSkyObject> =
                if (fail) throw IllegalStateException("boom") else JsonObjectCatalog().allObjects()
            override fun byId(id: String): DeepSkyObject? = null
            override val dataVersion: String = "flaky"
        }
        val useCase = TonightOverviewUseCase(ephemeris = ephemeris, objectCatalog = flaky)
        val first = useCase.execute(nyc, LocalDate.of(2026, 8, 12))
        assertFalse(first.recommendationsAvailable)
        fail = false
        val second = useCase.execute(nyc, LocalDate.of(2026, 8, 12))
        assertTrue(second.recommendationsAvailable)
        assertTrue(second.recommendations.isNotEmpty())
    }

    @Test
    fun tonightEventsDegradeWhenEclipseCatalogueFails() {
        val brokenEclipses = object : EclipseCatalog {
            override fun eclipses(fromYear: Int, toYear: Int): List<EclipseRecord> =
                throw JsonParseException("corrupt eclipses", 0)
            override val dataVersion: String = "broken"
        }
        val useCase = TonightOverviewUseCase(
            ephemeris = ephemeris,
            eclipseCatalog = brokenEclipses,
            showerCatalog = JsonMeteorShowerCatalog(),
        )
        val result = useCase.execute(nyc, LocalDate.of(2026, 8, 12))
        assertFalse(result.eventsAvailable)
        // Showers (the healthy catalogue) still contribute their events.
        assertTrue(
            result.events.any { it is TonightOverviewUseCase.Event.Shower },
        )
        assertNotNull(result.sunEvents.sunset)
    }

    @Test
    fun tonightEventsDisplayIsUnavailableWhenAllEventCataloguesFail() {
        val brokenEclipses = object : EclipseCatalog {
            override fun eclipses(fromYear: Int, toYear: Int): List<EclipseRecord> =
                throw JsonParseException("corrupt eclipses", 0)
            override val dataVersion: String = "broken"
        }
        val brokenShowers = object : MeteorShowerCatalog {
            override fun showers(): List<MeteorShower> =
                throw JsonParseException("corrupt showers", 0)
            override val dataVersion: String = "broken"
        }
        val useCase = TonightOverviewUseCase(
            ephemeris = ephemeris,
            eclipseCatalog = brokenEclipses,
            showerCatalog = brokenShowers,
        )
        val result = useCase.execute(nyc, LocalDate.of(2026, 8, 12))
        assertFalse(result.eventsAvailable)
        assertTrue(result.events.isEmpty())
        assertEquals(TonightOverviewUseCase.EventsDisplay.UNAVAILABLE, result.eventsDisplay)
    }

    @Test
    fun tonightEventsDisplayIsUnavailableWhenOnlyOneEventCatalogueFails() {
        // A single failed catalogue is enough to make the event section
        // unavailable: availability is all-or-nothing across catalogues,
        // not just when both fail at once.
        val brokenEclipses = object : EclipseCatalog {
            override fun eclipses(fromYear: Int, toYear: Int): List<EclipseRecord> =
                throw JsonParseException("corrupt eclipses", 0)
            override val dataVersion: String = "broken"
        }
        val useCase = TonightOverviewUseCase(
            ephemeris = ephemeris,
            eclipseCatalog = brokenEclipses,
            showerCatalog = JsonMeteorShowerCatalog(),
        )
        val result = useCase.execute(nyc, LocalDate.of(2026, 8, 12))
        assertFalse(result.eventsAvailable)
        assertEquals(TonightOverviewUseCase.EventsDisplay.UNAVAILABLE, result.eventsDisplay)
    }

    @Test
    fun tonightEventsDisplayIsNoneWhenHealthyCataloguesHaveNoNearbyEvent() {
        val useCase = TonightOverviewUseCase(
            ephemeris = ephemeris,
            eclipseCatalog = JsonEclipseCatalog(),
            showerCatalog = JsonMeteorShowerCatalog(),
        )
        // 2026-06-10 sits in a genuine gap: the nearest shower peaks
        // (ELY ~May 10, JPE ~Jul 8) and eclipses (Mar 3, Aug 12) are all
        // far outside the ±3-day window.
        val result = useCase.execute(nyc, LocalDate.of(2026, 6, 10))
        assertTrue(result.eventsAvailable)
        assertTrue(result.events.isEmpty())
        assertEquals(TonightOverviewUseCase.EventsDisplay.NONE, result.eventsDisplay)
    }
}
