package app.astroexposure.support

import app.astroexposure.data.catalog.JsonMeteorShowerCatalog
import app.astroexposure.domain.catalog.LightPollutionSource
import app.astroexposure.domain.ephemeris.CelestialBody
import app.astroexposure.domain.ephemeris.EphemerisProvider
import app.astroexposure.domain.ephemeris.EquatorialCoordinates
import app.astroexposure.domain.ephemeris.GalacticCenterWindow
import app.astroexposure.domain.ephemeris.HorizontalCoordinates
import app.astroexposure.domain.ephemeris.MoonRiseSet
import app.astroexposure.domain.ephemeris.NightWindow
import app.astroexposure.domain.ephemeris.SunEvents
import app.astroexposure.domain.ephemeris.TargetPass
import app.astroexposure.domain.ephemeris.EclipseEvent
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.sky.BaselineSource
import app.astroexposure.domain.strictness.Strictness
import app.astroexposure.domain.units.Degrees
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic ephemeris fake for the pure support functions. */
private class FakeEphemeris(
    private val moonAltitudeDeg: Double = 30.0,
    private val illumination: Double = 0.5,
) : EphemerisProvider {
    override fun astronomicalNight(location: GeoLocation, date: LocalDate): NightWindow? = null
    override fun moonIllumination(instant: Instant): Double = illumination
    override fun moonRiseSet(location: GeoLocation, date: LocalDate): MoonRiseSet =
        MoonRiseSet(null, null)
    override fun altitudeOf(
        location: GeoLocation, target: EquatorialCoordinates, instant: Instant,
    ): Degrees = Degrees(45.0)
    override fun azimuthOf(
        location: GeoLocation, target: EquatorialCoordinates, instant: Instant,
    ): Degrees = Degrees(180.0)
    override fun galacticCenterWindow(
        location: GeoLocation, date: LocalDate, minAltitude: Degrees,
    ): GalacticCenterWindow? = null
    override fun horizontalPosition(
        location: GeoLocation, body: CelestialBody, instant: Instant,
    ): HorizontalCoordinates = HorizontalCoordinates(
        Degrees(if (body == CelestialBody.MOON) moonAltitudeDeg else -10.0), Degrees(180.0),
    )
    override fun sunEvents(location: GeoLocation, date: LocalDate): SunEvents =
        SunEvents(null, null, null, null)
    override fun targetPass(
        location: GeoLocation, target: EquatorialCoordinates, date: LocalDate, threshold: Degrees,
    ): TargetPass? = null
    override fun eclipsesBetween(from: Instant, to: Instant): List<EclipseEvent> = emptyList()
    override fun moonPhaseAngle(instant: Instant): Degrees = Degrees(90.0)
}

private class FakeLp(private val value: Double?) : LightPollutionSource {
    override fun artificialBrightnessMcdPerM2(location: GeoLocation): Double? = value
    override val dataVersion: String = "fake"
}

class TimeHealthCheckTest {

    private val utc = ZoneId.of("UTC")
    private val ny = ZoneId.of("America/New_York")
    private val now: Instant = Instant.parse("2026-08-12T00:00:00Z")

    @Test
    fun healthyWhenZonesMatchAndNoGps() {
        val r = TimeHealthCheck.evaluate(utc, utc, now, null)
        assertTrue(r.healthy)
        assertEquals(null, r.clockSkewSeconds)
    }

    @Test
    fun zoneMismatchWarns() {
        val r = TimeHealthCheck.evaluate(utc, ny, now, null)
        assertEquals(listOf(TimeHealthCheck.Warning.ZONE_MISMATCH), r.warnings)
    }

    @Test
    fun skewOverSixtySecondsWarns() {
        val r = TimeHealthCheck.evaluate(utc, utc, now, now.plusSeconds(120))
        assertTrue(TimeHealthCheck.Warning.CLOCK_SKEW in r.warnings)
        assertEquals(120L, r.clockSkewSeconds)
    }

    @Test
    fun skewUnderThresholdIsHealthy() {
        val r = TimeHealthCheck.evaluate(utc, utc, now, now.plusSeconds(59))
        assertTrue(r.healthy)
    }
}

class StrictnessRecommendationTest {

    @Test
    fun untrackedLongFocalSmallPixelsIsStrict() {
        val r = StrictnessRecommendation.recommend(
            tracked = false, driftMeasured = false, focalMm = 50.0, pixelPitchUm = 4.16,
        )
        assertEquals(Strictness.STRICT, r.strictness)
        assertEquals(StrictnessRecommendation.Reason.UNTRACKED_TIGHT, r.reason)
    }

    @Test
    fun trackedMeasuredIsStandard() {
        val r = StrictnessRecommendation.recommend(
            tracked = true, driftMeasured = true, focalMm = 50.0, pixelPitchUm = 4.16,
        )
        assertEquals(Strictness.STANDARD, r.strictness)
        assertEquals(StrictnessRecommendation.Reason.TRACKED_MEASURED, r.reason)
    }

    @Test
    fun largePixelBodyIsStandardWithReason() {
        val r = StrictnessRecommendation.recommend(
            tracked = false, driftMeasured = false, focalMm = 14.0, pixelPitchUm = 8.4,
        )
        assertEquals(Strictness.STANDARD, r.strictness)
    }
}

class SkyAssemblyTest {

    private val location = GeoLocation(
        Degrees(40.7), Degrees(-74.0), 0.0, ZoneId.of("America/New_York"),
    )
    private val instant: Instant = Instant.parse("2026-08-12T02:00:00Z")
    private val target = EquatorialCoordinates(17.75, Degrees(-29.0))

    @Test
    fun atlasBaselineIsUsedWhenAvailable() {
        val ctx = SkyAssembly.compose(
            FakeEphemeris(), FakeLp(1.0), location, instant, target, manualBortle = null,
        )
        assertEquals(BaselineSource.ATLAS, ctx.totalSky.baselineSource)
        assertTrue(ctx.moonUp)
    }

    @Test
    fun manualBortleOverridesAtlas() {
        val ctx = SkyAssembly.compose(
            FakeEphemeris(), FakeLp(1.0), location, instant, target, manualBortle = 4,
        )
        assertEquals(BaselineSource.MANUAL_BORTLE, ctx.totalSky.baselineSource)
    }

    @Test
    fun noAtlasCoverageDegradesToNoBaseline() {
        val ctx = SkyAssembly.compose(
            FakeEphemeris(), FakeLp(null), location, instant, target, manualBortle = null,
        )
        assertEquals(BaselineSource.NONE, ctx.totalSky.baselineSource)
        assertEquals(null, ctx.totalSky.bortleEstimate)
    }

    @Test
    fun moonBelowHorizonContributesNothing() {
        val ctx = SkyAssembly.compose(
            FakeEphemeris(moonAltitudeDeg = -5.0), FakeLp(1.0),
            location, instant, target, manualBortle = null,
        )
        assertFalse(ctx.moonUp)
        assertEquals(0.0, ctx.moonlightMcdPerM2, 1e-12)
    }
}

class ShowerDatesTest {

    @Test
    fun perseidPeakApproximationLandsNearMidAugust() {
        val showers = JsonMeteorShowerCatalog().showers()
        val perseids = showers.first { it.iauCode == "PER" }
        val peak = ShowerDates.approxDate(perseids.peakLon, LocalDate.of(2026, 8, 1))
        // Mean-longitude conversion is an approximation (± several days);
        // the real 2026 peak is Aug 12-13.
        assertTrue(
            "approx peak $peak should be within 6 days of Aug 12",
            kotlin.math.abs(peak.toEpochDay() - LocalDate.of(2026, 8, 12).toEpochDay()) <= 6,
        )
        assertEquals(
            0.0,
            ShowerDates.daysFromPeak(perseids, peak),
            0.01,
        )
    }

    @Test
    fun quadrantidsWrapToJanuary() {
        val showers = JsonMeteorShowerCatalog().showers()
        val quads = showers.first { it.iauCode == "QUA" }
        val peak = ShowerDates.approxDate(quads.peakLon, LocalDate.of(2027, 1, 3))
        assertEquals(1, peak.monthValue)
    }
}
