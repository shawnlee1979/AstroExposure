package app.astroexposure.data.ephemeris

import app.astroexposure.domain.ephemeris.EphemerisProvider
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.units.Degrees
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [EphemerisProvider] (DEVELOPMENT-RULES.md C.1): written
 * against the port only — any implementation must pass them unchanged.
 *
 * Goldens for New York City (40.7128, -74.0060), collected 2026-10-26:
 * - Astronomical twilight bounds: sunrise-sunset.org API
 *   (2026-10-26 dusk 23:32:02Z; 2026-10-27 dawn 09:48:55Z).
 * - Moonrise/moonset 2026-10-26: MET Norway Locationforecast API
 *   (moonrise 17:58-04:00, moonset 07:56-04:00).
 * - Full moon instant 2026-10-26T04:12Z (public lunar calendars).
 * - Galactic-centre culmination altitude: spherical identity
 *   90 - |lat - dec| = 90 - |40.7128 - (-29.0)| = 20.29°.
 * Tolerances follow design §4.2 (twilight ±3 min, moonrise/set ±5 min,
 * illumination ±3 points, altitude ±0.5°).
 */
class EphemerisContractTest {

    private val provider: EphemerisProvider = AstronomyEngineEphemeris()

    private val nyc = GeoLocation(
        latitude = Degrees(40.7128),
        longitude = Degrees(-74.0060),
        zone = ZoneId.of("America/New_York"),
    )

    private fun assertInstantClose(expectedIso: String, actual: Instant?, toleranceSeconds: Long) {
        assertNotNull("expected an instant", actual)
        val expected = Instant.parse(expectedIso).epochSecond
        val delta = kotlin.math.abs(expected - actual!!.epochSecond)
        assertTrue("expected $expectedIso ±${toleranceSeconds}s, got $actual (delta ${delta}s)", delta <= toleranceSeconds)
    }

    @Test
    fun `astronomical night bounds match public ephemeris`() {
        val night = provider.astronomicalNight(nyc, LocalDate.of(2026, 10, 26))
        assertNotNull(night)
        assertInstantClose("2026-10-26T23:32:02Z", night!!.dusk, 180)
        assertInstantClose("2026-10-27T09:48:55Z", night.dawn, 180)
    }

    @Test
    fun `moon illumination at full moon instant is near one`() {
        val illum = provider.moonIllumination(Instant.parse("2026-10-26T04:12:00Z"))
        assertTrue("illumination $illum not in [0.985, 1.0]", illum in 0.985..1.0)
    }

    @Test
    fun `moonrise and moonset match public ephemeris`() {
        val rs = provider.moonRiseSet(nyc, LocalDate.of(2026, 10, 26))
        // 17:58 EDT = 21:58Z; 07:56 EDT = 11:56Z.
        assertInstantClose("2026-10-26T21:58:00Z", rs.moonrise, 300)
        assertInstantClose("2026-10-26T11:56:00Z", rs.moonset, 300)
    }

    @Test
    fun `galactic centre culmination altitude matches spherical identity`() {
        val window = provider.galacticCenterWindow(nyc, LocalDate.of(2026, 6, 18))
        assertNotNull(window)
        assertEquals(20.29, window!!.culminationAltitude.value, 0.5)
    }
}
