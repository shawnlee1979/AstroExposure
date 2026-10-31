package app.astroexposure.usecase

import app.astroexposure.data.catalog.JsonConstellationCatalog
import app.astroexposure.data.catalog.JsonConstellationLines
import app.astroexposure.data.catalog.JsonMeteorShowerCatalog
import app.astroexposure.data.catalog.JsonObjectCatalog
import app.astroexposure.data.catalog.JsonStarCatalog
import app.astroexposure.data.catalog.JsonWideFieldCatalog
import app.astroexposure.data.ephemeris.AstronomyEngineEphemeris
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.timeline.LandscapeComposition
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
 * Landscape composition on the fixed golden night (ui-design
 * §7.9, 0.15.0): Alpharetta, the night of 2026-10-27 — the same
 * fixture night as the cross-category board tests. The Orion
 * cases are the 0.15.0 design proposal's worked examples (§5),
 * recomputed there with an independent pipeline; the app's own
 * ephemeris reproduces them to within the proposal's stated
 * tolerances (caps and recommended focals to ~1 mm, window bounds
 * to a few minutes — the same sampling-phase difference the old
 * model's golden test recorded). The assertions pin the app's
 * numbers with those tolerances, so a solver change that moves
 * the geometry — not the rounding — fails here.
 *
 * The headline case is the proposal's §5.2 finding: against a
 * 150° foreground the OLD model reported a composition window
 * (03:11–03:46) with no frame anywhere in it; the new model
 * judges the same site a share conflict — at the crossing
 * (03:38:39, altitude 54.2°) Orion's skeleton top reaches ~72°,
 * and no focal inside the advised interval holds both the
 * ground-level foreground and the whole figure.
 */
class LandscapeGoldenNightTest {

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
        starCatalog = JsonStarCatalog(),
        constellationLines = JsonConstellationLines(),
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

    private fun orion(bearing: Double, top: Double) =
        useCase.evaluateLandscape(
            alpharetta, date, overview.timeline, "constellation:Ori", bearing, top,
        )!!

    @Test
    fun orionAgainstALowEastForegroundIsFeasible() {
        // Proposal §5.1: B=100°, T=0° — landscape cap 37.1,
        // feasible [31.2, 37.1], f* 32.6, window 23:03–00:28
        // (85 min); portrait window 75 min; recommend landscape.
        val evaluation = orion(100.0, 0.0)
        val result = evaluation.result
        assertNear(local(1, LocalTime.of(0, 7)), result.keyMoment.instant, 3)
        assertEquals(17.9, result.keyMoment.altitudeDeg, 0.6)
        assertEquals(31.2, result.adviceLowMm!!, 0.3)
        assertEquals(67.2, result.adviceHighMm!!, 0.3)

        val verdict = result.verdict
        assertTrue("was $verdict", verdict is LandscapeComposition.Verdict.Feasible)
        assertTrue(!(verdict as LandscapeComposition.Verdict.Feasible).recommendedPortrait)

        val landscape = result.landscape
        assertEquals(37.1, landscape.capFocalMm!!, 0.6)
        assertEquals(32.6, landscape.recommendedFocalMm!!, 0.8)
        assertEquals(1, landscape.segments.size)
        val segment = landscape.segments.single()
        assertNear(local(0, LocalTime.of(23, 3)), segment.start, 4)
        assertNear(local(1, LocalTime.of(0, 28)), segment.end, 4)
        assertEquals(85.0, segment.durationMinutes, 6.0)
        val best = segment.best as LandscapeComposition.BestMoment.Crossing
        assertNear(local(1, LocalTime.of(0, 7)), best.instant, 3)

        val portrait = result.portrait
        assertEquals(35.2, portrait.capFocalMm!!, 0.6)
        assertEquals(75.0, portrait.totalWindowMinutes, 6.0)
        // The skeleton really is the judged range: 23 unique
        // vertices, with chains for the snapshot.
        assertEquals(23, evaluation.keyMomentPoints.size)
        assertTrue(evaluation.keyMomentChains.isNotEmpty())
    }

    @Test
    fun orionAgainstTheGoldenForegroundIsAShareConflict() {
        // Proposal §5.2: B=150°, T=0° — caps 15.0 / 22.5, both
        // under the 31.2 advised floor; the live fact rides the
        // portrait cap: 47 minutes at ~26% share.
        val result = orion(150.0, 0.0).result
        assertNear(local(1, LocalTime.of(3, 39)), result.keyMoment.instant, 3)
        assertEquals(54.2, result.keyMoment.altitudeDeg, 0.6)
        assertEquals(15.0, result.landscape.capFocalMm!!, 0.6)
        assertEquals(22.5, result.portrait.capFocalMm!!, 0.6)

        val verdict = result.verdict
        assertTrue("was $verdict", verdict is LandscapeComposition.Verdict.ShareConflict)
        val live = (verdict as LandscapeComposition.Verdict.ShareConflict).liveFact!!
        assertTrue(live.portrait)
        assertEquals(22.5, live.capFocalMm, 0.6)
        assertEquals(0.26, live.share, 0.02)
        assertEquals(1, live.segments.size)
        assertNear(local(1, LocalTime.of(2, 51)), live.segments.single().start, 4)
        assertNear(local(1, LocalTime.of(3, 38)), live.segments.single().end, 4)
        // Independent check through the port: at the key instant
        // the centre's azimuth is the bearing itself.
        val azimuth = ephemeris.azimuthOf(
            alpharetta,
            TonightOverviewUseCase.TonightSubject.Constellation(
                JsonConstellationCatalog().constellations().first { it.code == "Ori" },
            ).coordinates,
            result.keyMoment.instant,
        ).value
        assertEquals(150.0, azimuth, 0.5)
    }

    @Test
    fun aTenDegreeForegroundTopStaysAShareConflict() {
        // Proposal §5.2: T=10° lifts the caps to 18.4 / 27.7 —
        // still under the advised floor.
        val result = orion(150.0, 10.0).result
        assertTrue(result.verdict is LandscapeComposition.Verdict.ShareConflict)
        assertEquals(18.4, result.landscape.capFocalMm!!, 0.6)
        assertEquals(27.7, result.portrait.capFocalMm!!, 0.6)
    }

    @Test
    fun aHighForegroundTopMakesTheSameBearingFeasible() {
        // Proposal §5.2: T=35° — landscape feasible with a
        // 95-minute window; portrait's cap is 47.1 but its f*
        // lands at ~32, not on the pinched cap.
        val result = orion(150.0, 35.0).result
        val verdict = result.verdict
        assertTrue("was $verdict", verdict is LandscapeComposition.Verdict.Feasible)
        assertTrue(!(verdict as LandscapeComposition.Verdict.Feasible).recommendedPortrait)
        assertEquals(34.5, result.landscape.capFocalMm!!, 0.6)
        assertEquals(95.0, result.landscape.totalWindowMinutes, 6.0)
        assertEquals(47.1, result.portrait.capFocalMm!!, 0.6)
        assertEquals(32.3, result.portrait.recommendedFocalMm!!, 0.8)
        assertEquals(80.0, result.portrait.totalWindowMinutes, 6.0)
    }

    @Test
    fun theConstructedCaseClassifiesAsShareConflictNotOverflow() {
        // Proposal §5.3: B=150°, T=-15° — the landscape frame
        // cannot reach the skeleton top even at 14mm (no cap),
        // but the portrait frame's 104° vertical field just can
        // (cap 16.8). The classification order therefore lands
        // on share conflict, not vertical overflow: overflow
        // requires BOTH orientations cap-less.
        val result = orion(150.0, -15.0).result
        assertTrue("was ${result.verdict}", result.verdict is LandscapeComposition.Verdict.ShareConflict)
        assertNull(result.landscape.capFocalMm)
        assertEquals(16.8, result.portrait.capFocalMm!!, 0.6)
    }

    @Test
    fun galacticCentreAgainstASouthWestForegroundIsTwilightBound() {
        // The galactic centre crosses 210° at 19:08:46 (altitude
        // 19.9°) — the old sample's object-side figures — but
        // astronomical dusk is 20:13, so only a sub-10-minute
        // sliver of the landscape window survives the night cut:
        // the honest verdict is the too-short window, with the
        // portrait window lying wholly in twilight.
        val evaluation = useCase.evaluateLandscape(
            alpharetta, date, overview.timeline, "viewpoint:galactic-centre", 210.0, 0.0,
        )!!
        val result = evaluation.result
        assertNear(local(0, LocalTime.of(19, 9)), result.keyMoment.instant, 3)
        assertEquals(19.9, result.keyMoment.altitudeDeg, 0.6)
        assertTrue(
            "was ${result.verdict}",
            result.verdict is LandscapeComposition.Verdict.WindowTooShort,
        )
        assertTrue(result.portrait.segments.isEmpty())
        assertTrue(result.portrait.geometricSegments.isNotEmpty())
        // A viewpoint's range is its anchor circle rim.
        assertEquals(48, evaluation.keyMomentPoints.size)
    }

    @Test
    fun aRadiantHasNoLandscapeEvaluation() {
        val shower = JsonMeteorShowerCatalog().showers().first()
        assertNull(
            useCase.evaluateLandscape(
                alpharetta, date, overview.timeline,
                "shower:${shower.iauCode}", 210.0, 0.0,
            ),
        )
    }
}
