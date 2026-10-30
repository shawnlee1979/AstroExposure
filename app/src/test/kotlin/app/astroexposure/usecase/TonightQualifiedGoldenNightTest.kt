package app.astroexposure.usecase

import app.astroexposure.data.catalog.JsonConstellationCatalog
import app.astroexposure.data.catalog.JsonMeteorShowerCatalog
import app.astroexposure.data.catalog.JsonObjectCatalog
import app.astroexposure.data.catalog.JsonWideFieldCatalog
import app.astroexposure.data.ephemeris.AstronomyEngineEphemeris
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.units.Degrees
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The qualified set on the fixed golden night of the 0.10.0 design
 * samples (ui-design §7.8, 0.10.0): Alpharetta, the night of
 * 2026-10-29. The expected counts were cross-validated by two
 * independent paths over the product's own gates (a diagnostic
 * replication and the product path with quotas raised), with
 * identical membership; this test pins them through the shipped
 * [TonightOverviewUseCase.execute] so the targets sheet's numbers
 * stay honest. It also pins the separation of the two count
 * calibres: the qualified set is the pre-truncation full set,
 * while the lanes (what the filter chips count) remain the quota
 * board plus the radiant lane.
 */
class TonightQualifiedGoldenNightTest {

    private val alpharetta = GeoLocation(
        latitude = Degrees(34.0754),
        longitude = Degrees(-84.2941),
        heightMeters = 0.0,
        zone = ZoneId.of("America/New_York"),
    )
    private val date: LocalDate = LocalDate.of(2026, 10, 29)

    private fun execute() = TonightOverviewUseCase(
        ephemeris = AstronomyEngineEphemeris(),
        objectCatalog = JsonObjectCatalog(),
        showerCatalog = JsonMeteorShowerCatalog(),
        constellationCatalog = JsonConstellationCatalog(),
        wideFieldCatalog = JsonWideFieldCatalog(),
    ).execute(alpharetta, date)

    private fun TonightOverviewUseCase.Result.qualifiedOf(
        category: TonightOverviewUseCase.SubjectCategory,
    ) = qualified.filter { it.subject.category == category }

    @Test
    fun qualifiedCountsBoardMembershipAndTheLaneCalibre() {
        val result = execute()
        assertTrue(result.shortlistAvailable)

        // The qualified set: every gate-clearer, before the quotas.
        assertEquals(
            45,
            result.qualifiedOf(TonightOverviewUseCase.SubjectCategory.DEEP_SKY).size,
        )
        assertEquals(
            5,
            result.qualifiedOf(TonightOverviewUseCase.SubjectCategory.WIDE_FIELD).size,
        )
        assertEquals(
            31,
            result.qualifiedOf(TonightOverviewUseCase.SubjectCategory.CONSTELLATION).size,
        )
        assertEquals(
            1,
            result.qualifiedOf(TonightOverviewUseCase.SubjectCategory.METEOR).size,
        )
        assertEquals(82, result.qualified.size)

        // The board itself is unchanged: same members the quota
        // rules have always produced for this night.
        fun boardIds(category: TonightOverviewUseCase.SubjectCategory) =
            result.shortlist.filter { it.subject.category == category }
                .map { it.subject.id }.toSet()
        assertEquals(
            setOf("M45", "NGC1980", "M15", "M31", "NGC869"),
            boardIds(TonightOverviewUseCase.SubjectCategory.DEEP_SKY),
        )
        assertEquals(
            setOf(
                "viewpoint:cygnus-milky-way", "viewpoint:cassiopeia-milky-way",
                "viewpoint:winter-milky-way",
            ),
            boardIds(TonightOverviewUseCase.SubjectCategory.WIDE_FIELD),
        )
        assertEquals(
            setOf("constellation:Lyr", "constellation:Tri"),
            boardIds(TonightOverviewUseCase.SubjectCategory.CONSTELLATION),
        )

        // onBoard is exactly the board — marked by the assembly,
        // identical id set to the shortlist, category by category.
        val marked = result.qualified.filter { it.onBoard }
        assertEquals(
            result.shortlist.map { it.subject.id }.toSet(),
            marked.map { it.subject.id }.toSet(),
        )
        assertEquals(10, marked.size)
        // Radiants are never board members.
        assertTrue(
            result.qualifiedOf(TonightOverviewUseCase.SubjectCategory.METEOR)
                .none { it.onBoard },
        )

        // The lane calibre (what the filter chips count) is NOT the
        // qualified set: lanes remain the quota board + the radiant.
        fun laneCount(category: TonightOverviewUseCase.SubjectCategory) =
            result.lanes.count { it.subject.category == category }
        assertEquals(5, laneCount(TonightOverviewUseCase.SubjectCategory.DEEP_SKY))
        assertEquals(3, laneCount(TonightOverviewUseCase.SubjectCategory.WIDE_FIELD))
        assertEquals(2, laneCount(TonightOverviewUseCase.SubjectCategory.CONSTELLATION))
        assertEquals(1, result.radiantLanes.size)
        assertEquals("shower:STA", result.radiantLanes.single().subject.id)
    }

    @Test
    fun qualifiedOrderKeyFactsAndSameSourceFigures() {
        val result = execute()

        // In-category orders are the assembly's own: deep sky by
        // magnitude, constellations and viewpoints by culmination.
        val deepSky = result.qualifiedOf(
            TonightOverviewUseCase.SubjectCategory.DEEP_SKY,
        )
        val magnitudes = deepSky.map { it.magnitude ?: Double.MAX_VALUE }
        assertEquals(magnitudes.sorted(), magnitudes)
        for (category in listOf(
            TonightOverviewUseCase.SubjectCategory.CONSTELLATION,
            TonightOverviewUseCase.SubjectCategory.WIDE_FIELD,
        )) {
            val altitudes = result.qualifiedOf(category).map { it.culminationAltitudeDeg }
            assertEquals(altitudes.sortedDescending(), altitudes)
        }

        // Spot figures from the cross-validated measurement.
        val m45 = deepSky.first { it.subject.id == "M45" }
        assertEquals(1.2, m45.magnitude!!, 0.001)
        assertEquals(80.1, m45.culminationAltitudeDeg, 0.3)
        val triangulum = result.qualifiedOf(
            TonightOverviewUseCase.SubjectCategory.CONSTELLATION,
        ).first { it.subject.id == "constellation:Tri" }
        assertEquals(88.1, triangulum.culminationAltitudeDeg, 0.3)
        assertTrue(kotlin.math.abs(triangulum.windowMinutes - 619) <= 3)
        // Orion qualifies (31-member set) but is NOT on the board:
        // it ranks far below the quota of two by culmination.
        val orion = result.qualifiedOf(
            TonightOverviewUseCase.SubjectCategory.CONSTELLATION,
        ).first { it.subject.id == "constellation:Ori" }
        assertFalse(orion.onBoard)
        assertEquals(57.9, orion.culminationAltitudeDeg, 0.3)

        // The one radiant: the Southern Taurids, its peak three
        // days ahead — exactly on the ±3-day membership edge.
        val sta = result.qualifiedOf(
            TonightOverviewUseCase.SubjectCategory.METEOR,
        ).single()
        assertEquals("shower:STA", sta.subject.id)
        assertEquals(LocalDate.of(2026, 11, 1), sta.meteorPeakDate)
        assertEquals(-3.0, sta.meteorDaysFromPeak!!, 0.75)
        assertEquals(7, sta.meteorZhr)
        assertTrue(kotlin.math.abs(sta.windowMinutes - 544) <= 3)

        // Same-source: every board row's qualified twin carries the
        // very figures the shortlist row carries — one assembly.
        val qualifiedById = result.qualified.associateBy { it.subject.id }
        for (entry in result.shortlist) {
            val twin = qualifiedById.getValue(entry.subject.id)
            assertEquals(entry.culmination, twin.culmination)
            assertEquals(entry.culminationAltitudeDeg, twin.culminationAltitudeDeg, 0.0)
            assertEquals(entry.shootableMinutes, twin.windowMinutes)
            assertEquals(entry.recommended?.interval, twin.recommended?.interval)
        }
    }
}
