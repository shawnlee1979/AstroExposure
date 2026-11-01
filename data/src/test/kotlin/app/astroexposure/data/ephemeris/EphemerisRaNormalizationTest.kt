package app.astroexposure.data.ephemeris

import app.astroexposure.domain.ephemeris.EquatorialCoordinates
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.units.Degrees
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adapter's RA boundary wrap (0.17.3): the engine's defineStar
 * validates RA as [0, 24) and throws outside it — the 0.17.1
 * device crash ("Invalid right ascension: -0.08222323640334205").
 * [AstronomyEngineEphemeris] wraps the RA at its boundary, so any
 * upstream arithmetic yields the same sky direction instead of an
 * exception. Declination is NOT wrapped or clamped: an
 * out-of-range declination keeps throwing, by design.
 */
class EphemerisRaNormalizationTest {

    private val provider = AstronomyEngineEphemeris()

    private val alpharetta = GeoLocation(
        latitude = Degrees(34.0754),
        longitude = Degrees(-84.2941),
        heightMeters = 0.0,
        zone = ZoneId.of("America/New_York"),
    )
    private val instant: Instant = Instant.parse("2026-10-30T03:00:00Z")
    private val date: LocalDate = LocalDate.of(2026, 10, 29)

    private fun coords(raHours: Double, decDeg: Double = 30.0) =
        EquatorialCoordinates(raHours, Degrees(decDeg))

    @Test
    fun `the crash record's negative RA resolves to its wrapped twin`() {
        val wrapped = provider.azimuthOf(alpharetta, coords(23.917776763596658), instant)
        val crashed = provider.azimuthOf(alpharetta, coords(-0.08222323640334205), instant)
        assertEquals(wrapped.value, crashed.value, 1e-9)
        val wrappedAlt = provider.altitudeOf(alpharetta, coords(23.917776763596658), instant)
        val crashedAlt = provider.altitudeOf(alpharetta, coords(-0.08222323640334205), instant)
        assertEquals(wrappedAlt.value, crashedAlt.value, 1e-9)
    }

    @Test
    fun `RA at and beyond the boundaries wraps`() {
        val zero = provider.azimuthOf(alpharetta, coords(0.0), instant)
        val twentyFour = provider.azimuthOf(alpharetta, coords(24.0), instant)
        assertEquals(zero.value, twentyFour.value, 1e-9)
        val oneHalf = provider.azimuthOf(alpharetta, coords(1.5), instant)
        val beyond = provider.azimuthOf(alpharetta, coords(25.5), instant)
        assertEquals(oneHalf.value, beyond.value, 1e-9)
        val twentyTwoHalf = provider.azimuthOf(alpharetta, coords(22.5), instant)
        val below = provider.azimuthOf(alpharetta, coords(-25.5), instant)
        assertEquals(twentyTwoHalf.value, below.value, 1e-9)
    }

    @Test
    fun `targetPass accepts an out-of-range RA without throwing`() {
        val pass = provider.targetPass(alpharetta, coords(-1.0), date, Degrees(20.0))
        assertTrue(pass == null || pass.culminationAltitude.value in -90.0..90.0)
    }

    @Test
    fun `an out-of-range declination still throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            provider.azimuthOf(alpharetta, coords(3.0, 91.0), instant)
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.azimuthOf(alpharetta, coords(3.0, -90.5), instant)
        }
    }
}
