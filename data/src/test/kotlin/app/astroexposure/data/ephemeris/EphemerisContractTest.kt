package app.astroexposure.data.ephemeris

import app.astroexposure.domain.ephemeris.CelestialBody
import app.astroexposure.domain.ephemeris.EclipseKind
import app.astroexposure.domain.ephemeris.EphemerisProvider
import app.astroexposure.domain.ephemeris.EquatorialCoordinates
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.units.Degrees
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
 *
 * Phase-2 goldens, collected 2026-10-26:
 * - NYC sun events 2026-10-26 (timeanddate.com sun table for New York):
 *   sunrise 07:19 EDT, sunset 17:59 EDT, civil dusk ends 18:27 EDT,
 *   nautical dusk ends 19:00 EDT. Tolerances per the phase-2 brief:
 *   sunset ±4 min, civil dusk end ±5 min (sunrise/nautical held to the
 *   same fixed tolerances).
 * - NYC Moon position 2026-10-26 21:00 EDT (timeanddate.com moon table for
 *   New York): moonrise 17:58 EDT that evening, meridian passing the
 *   following 01:29 EDT at 70.9 degrees altitude — so at 21:00 the Moon is
 *   still in the EASTERN sky. Spherical geometry from those public
 *   rise/transit anchors gives altitude about 31 degrees, azimuth about
 *   89 degrees (tolerance ±10 degrees on azimuth, altitude > 0 with a
 *   25–38 degree sanity band).
 * - Galactic centre via targetPass at Los Angeles (34.0522, -118.2437):
 *   culmination altitude by the same spherical identity,
 *   90 - |34.0522 - (-29.0)| = 26.95 degrees (inside the 26–31 degree
 *   band), tolerance ±1.5 degrees.
 * - 2026 eclipse events (NASA GSFC eclipse catalogue, Espenak & Meeus):
 *   2026-02-17 annular solar, 2026-03-03 total lunar, 2026-08-12 total
 *   solar, 2026-08-28 partial lunar; peak dates asserted within ±1 day.
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
    fun `moon phase angle at the full moon instant is near 180 degrees`() {
        // Same public full-moon instant as the illumination golden:
        // θ (Moon minus Sun ecliptic longitude) must sit in the Full
        // Moon sector [157.5°, 202.5°) of the ui-design §7.4 naming.
        val theta = provider.moonPhaseAngle(Instant.parse("2026-10-26T04:12:00Z"))
        assertTrue("phase angle ${theta.value} not in [170, 190]", theta.value in 170.0..190.0)
        assertEquals(
            app.astroexposure.domain.sky.MoonPhaseName.FULL_MOON,
            app.astroexposure.domain.sky.MoonPhaseNames.of(theta),
        )
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

    @Test
    fun `sun events match public ephemeris`() {
        val events = provider.sunEvents(nyc, LocalDate.of(2026, 10, 26))
        // 07:19 / 17:59 / 18:27 / 19:00 EDT = 11:19 / 21:59 / 22:27 / 23:00 UTC.
        assertInstantClose("2026-10-26T11:19:00Z", events.sunrise, 240)
        assertInstantClose("2026-10-26T21:59:00Z", events.sunset, 240)
        assertInstantClose("2026-10-26T22:27:00Z", events.civilDuskEnd, 300)
        assertInstantClose("2026-10-26T23:00:00Z", events.nauticalDuskEnd, 300)
    }

    @Test
    fun `moon horizontal position in the eastern sky before transit`() {
        val at2100 = ZonedDateTime.of(2026, 10, 26, 21, 0, 0, 0, ZoneId.of("America/New_York"))
        val pos = provider.horizontalPosition(nyc, CelestialBody.MOON, at2100.toInstant())
        assertTrue("moon altitude ${pos.altitude.value} not > 0", pos.altitude.value > 0.0)
        assertTrue(
            "moon altitude ${pos.altitude.value} outside the 25-38 degree sanity band",
            pos.altitude.value in 25.0..38.0,
        )
        assertEquals(89.0, pos.azimuth.value, 10.0)
    }

    @Test
    fun `target pass for the galactic centre at Los Angeles`() {
        val losAngeles = GeoLocation(
            latitude = Degrees(34.0522),
            longitude = Degrees(-118.2437),
            zone = ZoneId.of("America/Los_Angeles"),
        )
        val galacticCentre = EquatorialCoordinates(17.75, Degrees(-29.0))
        val pass = provider.targetPass(losAngeles, galacticCentre, LocalDate.of(2026, 6, 18), Degrees(20.0))
        assertNotNull(pass)
        assertEquals(26.95, pass!!.culminationAltitude.value, 1.5)
        assertNotNull(pass.risesAbove)
        assertNotNull(pass.setsBelow)
        assertTrue(pass.risesAbove!!.isBefore(pass.culmination))
        assertTrue(pass.culmination.isBefore(pass.setsBelow))
    }

    @Test
    fun `target pass for a target culminating before dusk`() {
        // Pre-dusk culmination geometry: at New York on 2026-09-30 the
        // galactic centre transits in the early evening, BEFORE
        // astronomical dusk, and is already below 20 degrees by dusk.
        // The pass must not be reported with the next day's culmination:
        // at a 20-degree threshold there is no pass tonight at all, and
        // at a 0-degree threshold the pass carries the same evening's
        // culmination (before dusk) and a set inside the night.
        val galacticCentre = EquatorialCoordinates(17.75, Degrees(-29.0))
        val date = LocalDate.of(2026, 9, 30)
        val night = provider.astronomicalNight(nyc, date)
        assertNotNull(night)

        assertNull(provider.targetPass(nyc, galacticCentre, date, Degrees(20.0)))

        val pass = provider.targetPass(nyc, galacticCentre, date, Degrees(0.0))
        assertNotNull(pass)
        assertTrue(
            "culmination ${pass!!.culmination} must precede dusk ${night!!.dusk}",
            pass.culmination.isBefore(night.dusk),
        )
        assertEquals(20.29, pass.culminationAltitude.value, 1.5)
        assertNull(pass.risesAbove)
        assertNotNull(pass.setsBelow)
        assertTrue(pass.setsBelow!!.isAfter(night.dusk))
        assertTrue(!pass.setsBelow!!.isAfter(night.dawn))
    }

    @Test
    fun `target azimuth at culmination points south for a northern observer`() {
        // The azimuthOf port member (added for the cursor detail card):
        // at culmination a target south of the zenith crosses the
        // meridian, so its azimuth is due south (180 degrees).
        val losAngeles = GeoLocation(
            latitude = Degrees(34.0522),
            longitude = Degrees(-118.2437),
            zone = ZoneId.of("America/Los_Angeles"),
        )
        val galacticCentre = EquatorialCoordinates(17.75, Degrees(-29.0))
        val pass = provider.targetPass(losAngeles, galacticCentre, LocalDate.of(2026, 6, 18), Degrees(20.0))
        assertNotNull(pass)
        val azimuth = provider.azimuthOf(losAngeles, galacticCentre, pass!!.culmination)
        assertEquals(180.0, azimuth.value, 3.0)
        // Consistency: altitudeOf at the same instant is the culmination altitude.
        val altitude = provider.altitudeOf(losAngeles, galacticCentre, pass.culmination)
        assertEquals(pass.culminationAltitude.value, altitude.value, 0.5)
    }

    @Test
    fun `moon separation via horizontal coordinates matches the equatorial route`() {
        // The cursor's Moon separation (ui-design §7.5, 0.6.0) is the
        // TRUE spherical angle, computed in the domain from the port's
        // horizontal coordinates. Independent check: the same angle
        // from equatorial coordinates — the Moon's RA/Dec straight
        // from the engine and M31's catalogue position — must agree
        // within refraction-scale tolerance (both bodies are high, so
        // refraction shifts altitudes by only a few arcminutes). An
        // altitude-difference impostor would miss by tens of degrees
        // in this geometry (Moon ~31° up in the east, M31 far higher).
        val at2100 = ZonedDateTime.of(2026, 10, 26, 21, 0, 0, 0, ZoneId.of("America/New_York"))
        val instant = at2100.toInstant()
        val m31 = EquatorialCoordinates(0.7123, Degrees(41.269))
        val moonHorizontal = provider.horizontalPosition(nyc, CelestialBody.MOON, instant)
        val targetHorizontal = app.astroexposure.domain.ephemeris.HorizontalCoordinates(
            provider.altitudeOf(nyc, m31, instant),
            provider.azimuthOf(nyc, m31, instant),
        )
        val viaHorizontal = app.astroexposure.domain.timeline.TimelineCursor
            .angularSeparationDeg(moonHorizontal, targetHorizontal)

        val observer = io.github.cosinekitty.astronomy.Observer(
            nyc.latitude.value, nyc.longitude.value, nyc.heightMeters,
        )
        val time = io.github.cosinekitty.astronomy.Time(
            instant.toEpochMilli() / 86_400_000.0 - 10_957.5,
        )
        val moonEq = io.github.cosinekitty.astronomy.equator(
            io.github.cosinekitty.astronomy.Body.Moon, time, observer,
            io.github.cosinekitty.astronomy.EquatorEpoch.OfDate,
            io.github.cosinekitty.astronomy.Aberration.Corrected,
        )
        fun separationFromEquatorial(
            raHoursA: Double, decDegA: Double, raHoursB: Double, decDegB: Double,
        ): Double {
            val decA = Math.toRadians(decDegA)
            val decB = Math.toRadians(decDegB)
            val deltaRa = Math.toRadians((raHoursA - raHoursB) * 15.0)
            val cosSep = kotlin.math.sin(decA) * kotlin.math.sin(decB) +
                kotlin.math.cos(decA) * kotlin.math.cos(decB) * kotlin.math.cos(deltaRa)
            return Math.toDegrees(kotlin.math.acos(cosSep.coerceIn(-1.0, 1.0)))
        }
        val viaEquatorial = separationFromEquatorial(
            moonEq.ra, moonEq.dec, m31.raHours, m31.declination.value,
        )
        assertEquals(viaEquatorial, viaHorizontal, 0.5)
        assertTrue("separation $viaHorizontal outside sanity band", viaHorizontal in 20.0..120.0)
    }

    @Test
    fun `eclipses of 2026 match the NASA catalogue events`() {
        val events = provider.eclipsesBetween(
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2027-01-01T00:00:00Z"),
        )
        fun assertEvent(kind: EclipseKind, type: String, date: LocalDate) {
            val match = events.firstOrNull { e ->
                e.kind == kind && e.type == type &&
                    kotlin.math.abs(
                        e.peak.atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay() - date.toEpochDay(),
                    ) <= 1
            }
            if (match == null) {
                fail("no $kind $type eclipse within ±1 day of $date in $events")
            }
        }
        assertEvent(EclipseKind.SOLAR, "ANNULAR", LocalDate.of(2026, 2, 17))
        assertEvent(EclipseKind.LUNAR, "TOTAL", LocalDate.of(2026, 3, 3))
        assertEvent(EclipseKind.SOLAR, "TOTAL", LocalDate.of(2026, 8, 12))
        assertEvent(EclipseKind.LUNAR, "PARTIAL", LocalDate.of(2026, 8, 28))
    }
}
