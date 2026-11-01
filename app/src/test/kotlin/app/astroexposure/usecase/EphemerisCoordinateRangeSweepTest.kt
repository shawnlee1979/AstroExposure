package app.astroexposure.usecase

import app.astroexposure.data.catalog.JsonConstellationCatalog
import app.astroexposure.data.catalog.JsonConstellationLines
import app.astroexposure.data.catalog.JsonMeteorShowerCatalog
import app.astroexposure.data.catalog.JsonObjectCatalog
import app.astroexposure.data.catalog.JsonStarCatalog
import app.astroexposure.data.catalog.JsonWideFieldCatalog
import app.astroexposure.data.ephemeris.AstronomyEngineEphemeris
import app.astroexposure.domain.ephemeris.CelestialBody
import app.astroexposure.domain.ephemeris.EphemerisProvider
import app.astroexposure.domain.ephemeris.EquatorialCoordinates
import app.astroexposure.domain.ephemeris.GalacticCenterWindow
import app.astroexposure.domain.ephemeris.HorizontalCoordinates
import app.astroexposure.domain.ephemeris.MoonRiseSet
import app.astroexposure.domain.ephemeris.NightWindow
import app.astroexposure.domain.ephemeris.SunEvents
import app.astroexposure.domain.ephemeris.TargetPass
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.units.Degrees
import app.astroexposure.support.ShowerDates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full-catalogue sweep of the coordinate ranges handed to the
 * ephemeris port (0.17.3). The 0.17.1 device crash was an
 * IllegalArgumentException out of the engine's defineStar:
 * "Invalid right ascension: -0.08222323640334205". The landscape
 * evaluation feeds the port not only catalogue centres but also
 * CONSTRUCTED points — a deep-sky ellipse's rim and a viewpoint's
 * anchor-circle rim, both built by the framing chart's inverse
 * tangent transform — and a radiant's position is an extrapolation
 * (peak position + daily drift x days from peak). Any of those can
 * in principle leave the ranges the engine validates
 * (RA in [0, 24), Dec in [-90, 90]) even though every catalogue
 * value sits inside them.
 *
 * This sweep drives the production paths with a recording port:
 * evaluateLandscape for every constellation (88), deep-sky object
 * (576) and viewpoint (7), and evaluateTargetAtCursor for every
 * shower (25) on every day of its activity period, the radiant
 * position being the use case's own drift extrapolation for that
 * date. Every coordinate the port receives is range-checked, and
 * every distinct coordinate is then handed to the real engine:
 * its defineStar path must not throw.
 *
 * Red/green contract: the pre-fix run counted the violations this
 * sweep exists to keep at zero — the Cassiopeia Milky Way
 * viewpoint (anchor RA 0.9 h, rim radius 12.5 deg) produced 16
 * out-of-range rim points, the first exactly the crash record's
 * -0.08222323640334205 h. Since the 0.17.3 fix (source-side RA
 * wrapping where constructed points and extrapolated radiants are
 * produced, plus the adapter's boundary wrap), both counts are
 * asserted zero.
 */
class EphemerisCoordinateRangeSweepTest {

    private val alpharetta = GeoLocation(
        latitude = Degrees(34.0754),
        longitude = Degrees(-84.2941),
        heightMeters = 0.0,
        zone = ZoneId.of("America/New_York"),
    )
    private val date: LocalDate = LocalDate.of(2026, 10, 29)
    private val instant: Instant = Instant.parse("2026-10-30T03:00:00Z")

    /**
     * An [EphemerisProvider] that records every target coordinate
     * it is handed and answers with benign, position-varying values
     * so the use case's own math runs its full course. Ambient
     * answers (night, sun events) are one fixed plausible night;
     * the sweep asserts on the recorded coordinates, never on the
     * evaluations built from these stand-in positions.
     */
    private class RecordingEphemeris : EphemerisProvider {
        /** Every distinct coordinate received, in first-seen order. */
        val recorded = LinkedHashSet<EquatorialCoordinates>()

        private fun record(target: EquatorialCoordinates) {
            recorded += target
        }

        override fun astronomicalNight(location: GeoLocation, date: LocalDate): NightWindow =
            NightWindow(
                dusk = Instant.parse("2026-10-30T00:11:26Z"),
                dawn = Instant.parse("2026-10-30T10:30:37Z"),
            )

        override fun moonIllumination(instant: Instant): Double = 0.0

        override fun moonPhaseAngle(instant: Instant): Degrees = Degrees(0.0)

        override fun moonRiseSet(location: GeoLocation, date: LocalDate): MoonRiseSet =
            MoonRiseSet(moonrise = null, moonset = null)

        override fun altitudeOf(
            location: GeoLocation,
            target: EquatorialCoordinates,
            instant: Instant,
        ): Degrees {
            record(target)
            return Degrees(
                35.0 + 20.0 * sin(target.raHours) +
                    10.0 * cos(Math.toRadians(target.declination.value)),
            )
        }

        override fun azimuthOf(
            location: GeoLocation,
            target: EquatorialCoordinates,
            instant: Instant,
        ): Degrees {
            record(target)
            val raw = target.raHours * 15.0 + target.declination.value
            return Degrees(((raw % 360.0) + 360.0) % 360.0)
        }

        override fun galacticCenterWindow(
            location: GeoLocation,
            date: LocalDate,
            minAltitude: Degrees,
        ): GalacticCenterWindow = GalacticCenterWindow(
            risesAbove = null,
            culmination = Instant.parse("2026-10-30T05:00:00Z"),
            culminationAltitude = Degrees(30.0),
            setsBelow = null,
        )

        override fun horizontalPosition(
            location: GeoLocation,
            body: CelestialBody,
            instant: Instant,
        ): HorizontalCoordinates = HorizontalCoordinates(Degrees(-20.0), Degrees(0.0))

        override fun sunEvents(location: GeoLocation, date: LocalDate): SunEvents =
            SunEvents(
                sunrise = Instant.parse("2026-10-30T11:48:00Z"),
                sunset = Instant.parse("2026-10-29T22:31:00Z"),
                civilDuskEnd = Instant.parse("2026-10-29T22:59:00Z"),
                nauticalDuskEnd = Instant.parse("2026-10-29T23:31:00Z"),
            )

        override fun targetPass(
            location: GeoLocation,
            target: EquatorialCoordinates,
            date: LocalDate,
            threshold: Degrees,
        ): TargetPass? {
            record(target)
            return null
        }
    }

    private val failures = mutableListOf<String>()
    private var rangeViolations = 0
    private var firstViolation: String? = null
    private var engineThrows = 0
    private var firstEngineThrow: String? = null

    @Test
    fun coordinatesReachingTheEphemerisStayInRangeAcrossTheCatalogue() {
        val recorder = RecordingEphemeris()
        val useCase = TonightOverviewUseCase(
            ephemeris = recorder,
            objectCatalog = JsonObjectCatalog(),
            showerCatalog = JsonMeteorShowerCatalog(),
            constellationCatalog = JsonConstellationCatalog(),
            wideFieldCatalog = JsonWideFieldCatalog(),
            starCatalog = JsonStarCatalog(),
            constellationLines = JsonConstellationLines(),
        )
        val timeline = useCase.execute(alpharetta, date).timeline

        var cursor = 0
        fun drain(tag: String) {
            val fresh = recorder.recorded.drop(cursor)
            cursor = recorder.recorded.size
            for (coord in fresh) {
                if (coord.raHours !in 0.0..<24.0 ||
                    coord.declination.value !in -90.0..90.0
                ) {
                    rangeViolations++
                    val line = "$tag -> RA ${coord.raHours} h, " +
                        "Dec ${coord.declination.value} deg"
                    if (firstViolation == null) firstViolation = line
                    failures += "OUT OF RANGE: $line"
                }
            }
        }

        // Landscape path: centres plus constructed range points.
        val constellationIds = JsonConstellationCatalog().constellations()
            .map { "constellation:${it.code}" }
        val objectIds = JsonObjectCatalog().allObjects().map { it.id }
        val viewpointIds = JsonWideFieldCatalog().viewpoints()
            .map { "viewpoint:${it.id}" }
        assertEquals(88, constellationIds.size)
        assertEquals(576, objectIds.size)
        assertEquals(7, viewpointIds.size)
        for (id in constellationIds + objectIds + viewpointIds) {
            useCase.evaluateLandscape(alpharetta, date, timeline, id, 0.0)
            drain(id)
        }

        // Radiant path: the use case's drift extrapolation, every
        // day of each shower's activity period.
        val showers = JsonMeteorShowerCatalog().showers()
        assertEquals(25, showers.size)
        var radiantDays = 0
        for (shower in showers) {
            val peak = ShowerDates.approxDate(shower.peakLon, LocalDate.of(2026, 1, 1))
            val start = ShowerDates.approxDate(shower.activityStartLon, peak)
            val end = ShowerDates.approxDate(shower.activityEndLon, peak)
            val spanDays = ChronoUnit.DAYS.between(start, end)
            assertTrue(
                "shower ${shower.iauCode} activity span implausible: $spanDays d",
                spanDays in 0..200,
            )
            var day = start
            while (!day.isAfter(end)) {
                useCase.evaluateTargetAtCursor(
                    alpharetta, day, instant, timeline, null, "shower:${shower.iauCode}",
                )
                drain("shower:${shower.iauCode}@$day")
                radiantDays++
                day = day.plusDays(1)
            }
        }

        // Engine gate: every distinct coordinate through the real
        // adapter's defineStar path.
        val engine = AstronomyEngineEphemeris()
        for (coord in recorder.recorded) {
            try {
                engine.azimuthOf(alpharetta, coord, instant)
            } catch (e: IllegalArgumentException) {
                engineThrows++
                val line = "RA ${coord.raHours} h, Dec ${coord.declination.value} deg: ${e.message}"
                if (firstEngineThrow == null) firstEngineThrow = line
                failures += "ENGINE THREW: $line"
            }
        }

        val report = buildString {
            appendLine("subjects swept: ${constellationIds.size + objectIds.size + viewpointIds.size} landscape + ${showers.size} showers x $radiantDays activity days")
            appendLine("distinct coordinates recorded: ${recorder.recorded.size}")
            appendLine("range violations: $rangeViolations")
            appendLine("first violation: ${firstViolation ?: "none"}")
            appendLine("engine defineStar throws: $engineThrows")
            appendLine("first engine throw: ${firstEngineThrow ?: "none"}")
        }
        println(report)
        assertTrue(
            "coordinate sweep: ${failures.size} failure(s); first: " +
                (failures.firstOrNull() ?: "none"),
            failures.isEmpty(),
        )
    }
}
