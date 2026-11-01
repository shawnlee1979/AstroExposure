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
 * Landscape composition on fixed nights (ui-design §7.9,
 * 0.16.0): Alpharetta; Orion on the golden night of 2026-10-27
 * (the same fixture night as the cross-category board tests) and
 * Lyra on the night of 2026-10-31 — the 0.16.0 design proposal's
 * worked examples (§6), recomputed there with an independent
 * pipeline. The app's own ephemeris reproduces them to within
 * the proposal's stated tolerances (derived focals to ~1 mm,
 * window bounds to a few minutes — the same sampling-phase
 * difference the earlier models' golden tests recorded). The
 * assertions pin the app's numbers with those tolerances, so a
 * solver or wiring change that moves the geometry — not the
 * rounding — fails here.
 *
 * The model under test is the second revision: the subject is
 * the anchor, its azimuth is derived from its own track, the
 * foreground top is the only site input, and the focal length is
 * derived from the vertical span at the anchor (the night's
 * greatest centre altitude).
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
    private val lyraDate: LocalDate = LocalDate.of(2026, 10, 31)

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
    private val lyraOverview = useCase.execute(alpharetta, lyraDate)

    private fun local(dayOffset: Long, time: LocalTime): Instant =
        date.plusDays(dayOffset).atTime(time).atZone(alpharetta.zone).toInstant()

    private fun lyraLocal(dayOffset: Long, time: LocalTime): Instant =
        lyraDate.plusDays(dayOffset).atTime(time).atZone(alpharetta.zone).toInstant()

    private fun assertNear(expected: Instant, actual: Instant, toleranceMinutes: Long) {
        assertTrue(
            "expected $actual within ${toleranceMinutes}m of $expected",
            Duration.between(expected, actual).abs() <= Duration.ofMinutes(toleranceMinutes),
        )
    }

    private fun orion(top: Double) =
        useCase.evaluateLandscape(
            alpharetta, date, overview.timeline, "constellation:Ori", top,
        )!!

    private fun lyra(top: Double) =
        useCase.evaluateLandscape(
            alpharetta, lyraDate, lyraOverview.timeline, "constellation:Lyr", top,
        )!!

    @Test
    fun lyraIsFeasibleWithItsAnchorOnTheNightStart() {
        // Proposal §6.1: Lyra culminates in daylight; inside the
        // night (20:12–05:29) the centre only descends, so the
        // anchor sits on the night's start edge.
        val result = lyra(6.0).result
        val verdict = result.verdict
        assertTrue("was $verdict", verdict is LandscapeComposition.Verdict.Feasible)
        assertTrue((verdict as LandscapeComposition.Verdict.Feasible).recommendedPortrait)
        assertNear(lyraLocal(0, LocalTime.of(20, 12)), result.anchor.instant, 6)
        assertEquals(59.0, result.anchor.centreAltitudeDeg, 1.5)
    }

    @Test
    fun lyraDerivedFocalsAndShare() {
        // Proposal §6.1: the closed forms hold under the exact
        // check in both orientations (portrait = 1.5x landscape).
        val result = lyra(6.0).result
        assertEquals(24.1, result.portrait.derivedFocalMm!!, 1.2)
        assertEquals(16.1, result.landscape.derivedFocalMm!!, 1.2)
        assertEquals(0.14, result.portrait.share!!, 0.03)
    }

    @Test
    fun lyraWindowAndSnapshotInstant() {
        // Proposal §6.1: the portrait window opens with the night
        // and closes when the skeleton's lowest point sinks to the
        // 6° foreground top; its best moment is the snapshot's.
        val evaluation = lyra(6.0)
        val portrait = evaluation.result.portrait
        assertEquals(1, portrait.segments.size)
        val segment = portrait.segments.single()
        assertNear(lyraLocal(0, LocalTime.of(20, 12)), segment.start, 8)
        assertNear(lyraLocal(1, LocalTime.of(0, 54)), segment.end, 8)
        assertEquals(282.0, segment.durationMinutes, 12.0)
        assertNear(lyraLocal(0, LocalTime.of(21, 58)), segment.best.instant, 10)
        assertEquals(38.0, segment.best.centreAltitudeDeg, 2.0)
        assertNear(segment.best.instant, evaluation.snapshotInstant, 10)
    }

    @Test
    fun orionDerivedFocalsAtAZeroForegroundTop() {
        // Proposal §6.2: the anchor is the culmination; the
        // portrait closed form (16.8) stands, the landscape one
        // (11.2) undercuts the widest focal and derives nothing.
        val result = orion(0.0).result
        assertNear(local(1, LocalTime.of(4, 45)), result.anchor.instant, 6)
        assertEquals(57.9, result.anchor.centreAltitudeDeg, 1.0)
        val verdict = result.verdict
        assertTrue("was $verdict", verdict is LandscapeComposition.Verdict.Feasible)
        assertTrue((verdict as LandscapeComposition.Verdict.Feasible).recommendedPortrait)
        assertEquals(16.8, result.portrait.derivedFocalMm!!, 0.8)
        assertNull(result.landscape.derivedFocalMm)
        assertEquals(11.2, result.landscape.closedFormFocalMm, 0.8)
    }

    @Test
    fun orionWindowAndShare() {
        // Proposal §6.2: the window opens when the skeleton's
        // lowest point rises past 0° and runs to the night's end.
        val result = orion(0.0).result
        val portrait = result.portrait
        assertEquals(1, portrait.segments.size)
        val segment = portrait.segments.single()
        assertNear(local(0, LocalTime.of(23, 22)), segment.start, 8)
        assertNear(local(1, LocalTime.of(6, 26)), segment.end, 8)
        assertEquals(423.0, segment.durationMinutes, 12.0)
        assertNear(local(1, LocalTime.of(2, 1)), segment.best.instant, 10)
        assertEquals(0.21, portrait.share!!, 0.03)
    }

    @Test
    fun orionSnapshotCarriesTheSkeletonPoints() {
        // The skeleton really is the judged range: 23 unique
        // vertices, with chains for the snapshot.
        val evaluation = orion(0.0)
        assertEquals(23, evaluation.snapshotPoints.size)
        assertTrue(evaluation.snapshotChains.isNotEmpty())
    }

    @Test
    fun orionUnderAHighForegroundTopNeverRisesPastIt() {
        // Proposal §6.3: at T=60° the skeleton's lowest point
        // peaks at 46.3° — the subject passes behind the
        // foreground all night and no focal length can change
        // that (the first verdict).
        val result = orion(60.0).result
        val verdict = result.verdict
        assertTrue(
            "was $verdict",
            verdict is LandscapeComposition.Verdict.NeverAboveForegroundTop,
        )
        verdict as LandscapeComposition.Verdict.NeverAboveForegroundTop
        assertEquals(46.3, verdict.maxBottomAltitudeDeg, 1.2)
        assertNear(local(1, LocalTime.of(5, 0)), verdict.atInstant, 12)
        assertEquals(60.0, verdict.foregroundTopDeg, 0.0)
    }

    @Test
    fun galacticCentreOnTheGoldenNightIsAllOutsideNight() {
        // The galactic-centre viewpoint under the new model
        // (measured on this fixture, 0.16.0): the subject only
        // descends inside the night, so the anchor sits on the
        // night's start edge (20:13, centre altitude ~12°). Both
        // orientations derive a focal — the exact check shortens
        // both closed forms for the wide anchor circle (landscape
        // ~25.9 from 41.0, portrait ~37.6 from 61.6) — and the
        // geometric window (17:48–19:35, ~107 min) is real, but
        // it closes before astronomical dusk: cut to the night it
        // leaves nothing, so the verdict is the third category,
        // all outside night. (The old fixed-bearing model called
        // this same site a too-short window on a sub-10-minute
        // night sliver; with the azimuth following the subject
        // the window itself is longer and wholly twilight.)
        val evaluation = useCase.evaluateLandscape(
            alpharetta, date, overview.timeline, "viewpoint:galactic-centre", 0.0,
        )!!
        val result = evaluation.result
        assertTrue(
            "was ${result.verdict}",
            result.verdict is LandscapeComposition.Verdict.AllOutsideNight,
        )
        assertNear(local(0, LocalTime.of(20, 13)), result.anchor.instant, 6)
        assertEquals(12.0, result.anchor.centreAltitudeDeg, 1.0)
        assertEquals(25.9, result.landscape.derivedFocalMm!!, 1.2)
        assertEquals(37.6, result.portrait.derivedFocalMm!!, 1.5)
        assertTrue(result.landscape.segments.isEmpty())
        assertTrue(result.portrait.segments.isEmpty())
        assertEquals(1, result.landscape.geometricSegments.size)
        val geometric = result.landscape.geometricSegments.single()
        assertNear(local(0, LocalTime.of(17, 48)), geometric.start, 8)
        assertNear(local(0, LocalTime.of(19, 35)), geometric.end, 8)
        assertEquals(107.0, geometric.durationMinutes, 10.0)
        // The verdict carries the domain recommendation (0.17.0):
        // both night windows are 0 minutes (a tie), so the larger
        // derived focal — portrait 37.6 over landscape 25.9 —
        // recommends portrait. Before 0.17.0 this verdict carried
        // no recommendation and both call sites defaulted to
        // landscape by their own divergent re-derivations.
        val verdict = result.verdict as LandscapeComposition.Verdict.AllOutsideNight
        assertTrue(verdict.recommendedPortrait)
        // The snapshot falls back to the geometric segments (no
        // night-cut segment exists). The two spans share their
        // endpoints exactly — the same 106.89 minutes — so the
        // longest-segment rule ties and §7.10's tie rule decides:
        // the recommended orientation's segment, portrait, whose
        // best moment is 18:35. Before 0.17.0 the snapshot
        // defaulted to landscape and froze at landscape's best
        // moment (18:43, the design board's figure); the board's
        // ±10-minute tolerance straddles both instants, so the
        // pin here is the exact segment instant instead.
        assertEquals(107.0, result.portrait.geometricSegments.single().durationMinutes, 10.0)
        assertEquals(
            geometric.durationMinutes,
            result.portrait.geometricSegments.single().durationMinutes,
            0.0,
        )
        assertEquals(
            result.portrait.geometricSegments.single().best.instant,
            evaluation.snapshotInstant,
        )
        assertNear(local(0, LocalTime.of(18, 35)), evaluation.snapshotInstant, 10)
        // A viewpoint's range is its anchor circle rim.
        assertEquals(48, evaluation.snapshotPoints.size)
    }

    @Test
    fun aRadiantHasNoLandscapeEvaluation() {
        val shower = JsonMeteorShowerCatalog().showers().first()
        assertNull(
            useCase.evaluateLandscape(
                alpharetta, date, overview.timeline,
                "shower:${shower.iauCode}", 0.0,
            ),
        )
    }
}
