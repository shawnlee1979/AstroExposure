package app.astroexposure.usecase

import app.astroexposure.data.catalog.JsonConstellationCatalog
import app.astroexposure.data.catalog.JsonMeteorShowerCatalog
import app.astroexposure.data.catalog.JsonObjectCatalog
import app.astroexposure.data.catalog.JsonWideFieldCatalog
import app.astroexposure.data.ephemeris.AstronomyEngineEphemeris
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.timeline.CompositionWindow
import app.astroexposure.domain.units.Degrees
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Composition windows on the fixed golden night (ui-design §7.9):
 * Alpharetta, the night of 2026-10-27 — the same fixture night as
 * the cross-category board tests. The two cases are the design
 * proposal's worked examples (sample ⑬ and the §5 acceptance list),
 * evaluated with the app's own ephemeris.
 *
 * The Orion case reproduces the sample's figures to within a few
 * minutes (the differences are the sample's rounding). The
 * galactic-centre case does NOT reproduce sample ⑬'s window as a
 * night window: the sample drew the span 18:47–20:00 against its
 * own day-boundary figures, but the app's astronomical dusk this
 * night is 20:13 (the §7.8 finding — the sample board's sunset and
 * night-start readings run about an hour behind the ephemeris), so
 * the span lies entirely in twilight and the honest verdict is the
 * twilight diagnosis. The sample's object-side figures (best
 * 19:07, crossing altitude 20°) agree with the computed crossing
 * (19:08:46, 19.9°) — what changed is only which side of dusk the
 * span falls on. Crossings are additionally verified against the
 * ephemeris port directly: at a reported crossing instant the
 * port's own azimuth must be the bearing.
 */
class CompositionGoldenNightTest {

    private val ephemeris = AstronomyEngineEphemeris()
    private val alpharetta = GeoLocation(
        latitude = Degrees(34.0754),
        longitude = Degrees(-84.2941),
        heightMeters = 0.0,
        zone = ZoneId.of("America/New_York"),
    )
    private val date: LocalDate = LocalDate.of(2026, 10, 27)

    private val useCase = TonightOverviewUseCase(
        ephemeris = ephemeris,
        objectCatalog = JsonObjectCatalog(),
        showerCatalog = JsonMeteorShowerCatalog(),
        constellationCatalog = JsonConstellationCatalog(),
        wideFieldCatalog = JsonWideFieldCatalog(),
    )
    private val overview = useCase.execute(alpharetta, date)

    private fun local(dayOffset: Long, time: LocalTime): Instant =
        date.plusDays(dayOffset).atTime(time).atZone(alpharetta.zone).toInstant()

    private fun assertNear(expected: Instant, actual: Instant, toleranceMinutes: Long) {
        assertTrue(
            "expected $actual within ${toleranceMinutes}m of $expected",
            Duration.between(expected, actual).abs() <= Duration.ofMinutes(toleranceMinutes),
        )
    }

    @Test
    fun orionAgainstASouthEastForeground() {
        val evaluation = useCase.evaluateComposition(
            alpharetta, date, overview.timeline, "constellation:Ori", 150.0, 10.0,
        )!!
        assertNull(evaluation.result.diagnosis)
        assertEquals(1, evaluation.result.segments.size)
        val segment = evaluation.result.segments.single()
        // Sample figures 03:10–03:46, crossing 03:37 at 54°.
        assertNear(local(1, LocalTime.of(3, 11)), segment.start, 3)
        assertNear(local(1, LocalTime.of(3, 46)), segment.end, 3)
        val best = segment.best as CompositionWindow.BestMoment.Crossing
        assertNear(local(1, LocalTime.of(3, 39)), best.instant, 3)
        assertEquals(54.2, best.altitudeDeg, 0.5)
        // The span ends at the altitude ceiling: Orion is still
        // climbing toward its 58° culmination when it leaves the
        // band, so the segment's maximum altitude IS the ceiling.
        assertEquals(CompositionWindow.MAX_ALTITUDE_DEG, segment.maxAltitudeDeg, 0.3)
        // Independent check through the port: at the crossing
        // instant the centre's azimuth is the bearing itself.
        val azimuth = ephemeris.azimuthOf(
            alpharetta, evaluation.subject.coordinates, best.instant,
        ).value
        assertEquals(150.0, azimuth, 0.5)
    }

    @Test
    fun galacticCentreAgainstASouthWestForegroundIsTwilightOnly() {
        val evaluation = useCase.evaluateComposition(
            alpharetta, date, overview.timeline, "viewpoint:galactic-centre", 210.0, 10.0,
        )!!
        // The app's astronomical dusk is 20:13 this night; the
        // whole composition span precedes it.
        assertNear(local(0, LocalTime.of(20, 13)), evaluation.nightSpan!!.start, 2)
        assertTrue(evaluation.result.segments.isEmpty())
        val diagnosis = evaluation.result.diagnosis
            as CompositionWindow.Diagnosis.TwilightOnly
        assertEquals(1, diagnosis.segments.size)
        val segment = diagnosis.segments.single()
        assertNear(local(0, LocalTime.of(18, 23)), segment.start, 3)
        assertNear(local(0, LocalTime.of(20, 1)), segment.end, 3)
        // Sample ⑬'s object-side figures: best 19:07 at 20°.
        val best = segment.best as CompositionWindow.BestMoment.Crossing
        assertNear(local(0, LocalTime.of(19, 9)), best.instant, 3)
        assertEquals(19.9, best.altitudeDeg, 0.5)
        val azimuth = ephemeris.azimuthOf(
            alpharetta, evaluation.subject.coordinates, best.instant,
        ).value
        assertEquals(210.0, azimuth, 0.5)
    }

    @Test
    fun aBearingTheSubjectNeverApproachesIsAnExplicitDiagnosis() {
        // Tonight the centre's azimuth runs from ~192° at dusk
        // through west and — below the horizon — around through
        // north to ~101° by dawn, so even an east bearing IS
        // approached (deep below the horizon, an altitude
        // diagnosis). The one sector never entered is the gap the
        // sweep leaves between ~101° and ~192°; 150° sits in it.
        val evaluation = useCase.evaluateComposition(
            alpharetta, date, overview.timeline, "viewpoint:galactic-centre", 150.0, 10.0,
        )!!
        assertTrue(evaluation.result.segments.isEmpty())
        assertTrue(
            evaluation.result.diagnosis
                is CompositionWindow.Diagnosis.NeverInAzimuthBand,
        )
    }

    @Test
    fun aRadiantHasNoCompositionEvaluation() {
        val shower = JsonMeteorShowerCatalog().showers().first()
        assertNull(
            useCase.evaluateComposition(
                alpharetta, date, overview.timeline,
                "shower:${shower.iauCode}", 210.0, 10.0,
            ),
        )
    }
}
