package app.astroexposure.usecase

import app.astroexposure.data.catalog.JsonConstellationCatalog
import app.astroexposure.data.catalog.JsonMeteorShowerCatalog
import app.astroexposure.data.catalog.JsonObjectCatalog
import app.astroexposure.data.catalog.JsonWideFieldCatalog
import app.astroexposure.data.ephemeris.AstronomyEngineEphemeris
import app.astroexposure.domain.catalog.ConstellationCatalog
import app.astroexposure.domain.catalog.ConstellationEntry
import app.astroexposure.domain.catalog.ConstellationNames
import app.astroexposure.domain.catalog.DeepSkyObject
import app.astroexposure.domain.catalog.DeepSkyObjectType
import app.astroexposure.domain.catalog.MeteorShower
import app.astroexposure.domain.catalog.MeteorShowerCatalog
import app.astroexposure.domain.catalog.WideFieldCatalog
import app.astroexposure.domain.catalog.WideFieldViewpoint
import app.astroexposure.domain.ephemeris.GalacticCenter
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.units.Degrees
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-category tonight board (ui-design §7.8, 0.9.0): the two new
 * catalogues' integrity, the pure board assembly (gates, quotas, no
 * transfer, board order), the radiant lanes' three entry conditions,
 * and the fixed golden night the design board was drawn from.
 */
class TonightCrossCategoryTest {

    private val ephemeris = AstronomyEngineEphemeris()
    private val alpharetta = GeoLocation(
        latitude = Degrees(34.0754),
        longitude = Degrees(-84.2941),
        heightMeters = 0.0,
        zone = ZoneId.of("America/New_York"),
    )

    // --- Catalogue integrity (proposal §5 ①) --------------------------------

    @Test
    fun constellationTableCoversExactlyThe88IauCodes() {
        val catalog: ConstellationCatalog = JsonConstellationCatalog()
        val entries = catalog.constellations()
        assertEquals(88, entries.size)
        assertEquals(ConstellationNames.codes, entries.map { it.code }.toSet())
        for (entry in entries) {
            assertTrue(entry.code, entry.centerRaHours in 0.0..<24.0)
            assertTrue(entry.code, entry.centerDecDeg in -90.0..90.0)
            assertTrue(entry.code, entry.sizeMajorDeg >= entry.sizeMinorDeg)
            assertTrue(entry.code, entry.sizeMinorDeg > 0)
            assertTrue(
                entry.code,
                entry.brightStars.size in 2..4,
            )
            assertTrue(entry.code, entry.noteZh.isNotBlank())
            assertTrue(entry.code, entry.noteEn.isNotBlank())
        }
    }

    @Test
    fun wideFieldListHasTheSevenViewpointsAndTheGalacticCentreAnchor() {
        val catalog: WideFieldCatalog = JsonWideFieldCatalog()
        val viewpoints = catalog.viewpoints()
        assertEquals(7, viewpoints.size)
        assertEquals(
            setOf(
                "galactic-centre", "scutum-star-cloud", "cygnus-milky-way",
                "cassiopeia-milky-way", "winter-milky-way", "monoceros-milky-way",
                "centaurus-crux-milky-way",
            ),
            viewpoints.map { it.id }.toSet(),
        )
        val gc = viewpoints.first { it.id == "galactic-centre" }
        // The anchor IS the domain's galactic-centre constant, not a copy.
        assertEquals(GalacticCenter.RA_HOURS, gc.anchorRaHours, 1e-9)
        assertEquals(GalacticCenter.DEC_DEG, gc.anchorDecDeg, 1e-9)
        for (viewpoint in viewpoints) {
            assertTrue(viewpoint.id, viewpoint.sizeMajorDeg in 20..40)
            assertTrue(viewpoint.id, viewpoint.sizeMajorDeg >= viewpoint.sizeMinorDeg)
        }
    }

    // --- Pure board assembly (proposal §5 ①) --------------------------------

    private fun deepSky(id: String, magnitude: Double): TonightOverviewUseCase.TonightSubject =
        TonightOverviewUseCase.TonightSubject.DeepSky(
            DeepSkyObject(
                id = id, name = id, nameZh = null, raHours = 0.0, decDeg = 0.0,
                magnitude = magnitude, sizeMajorArcmin = null, sizeMinorArcmin = null,
                type = DeepSkyObjectType.GALAXY, constellation = null,
                surfaceBrightness = null, surfaceBrightnessEstimated = false,
            ),
        )

    private fun constellation(code: String): TonightOverviewUseCase.TonightSubject =
        TonightOverviewUseCase.TonightSubject.Constellation(
            ConstellationEntry(
                code = code, centerRaHours = 0.0, centerDecDeg = 0.0,
                sizeMajorDeg = 20, sizeMinorDeg = 10, brightStars = emptyList(),
                noteZh = "注", noteEn = "note",
            ),
        )

    private fun viewpoint(id: String): TonightOverviewUseCase.TonightSubject =
        TonightOverviewUseCase.TonightSubject.Viewpoint(
            WideFieldViewpoint(
                id = id, nameZh = id, nameEn = id, anchorRaHours = 0.0, anchorDecDeg = 0.0,
                sizeMajorDeg = 30, sizeMinorDeg = 12, noteZh = "注", noteEn = "note",
            ),
        )

    private fun candidate(
        subject: TonightOverviewUseCase.TonightSubject,
        culminationAltitudeDeg: Double = 50.0,
        windowMinutes: Long = 300,
        magnitude: Double? = null,
        recommendedStart: Instant? = null,
        culmination: Instant = Instant.parse("2026-10-27T04:00:00Z"),
    ) = BoardCandidate(
        subject = subject,
        culmination = culmination,
        culminationAltitudeDeg = culminationAltitudeDeg,
        windowMinutes = windowMinutes,
        magnitude = magnitude,
        recommendedStart = recommendedStart,
    )

    @Test
    fun quotasBindPerCategoryAndNeverTransfer() {
        val many = mutableListOf<BoardCandidate>()
        // Seven constellations pass their gates; only the quota of
        // two may board, chosen by culmination altitude.
        for (i in 0 until 7) {
            many += candidate(
                constellation("C%02d".format(i)),
                culminationAltitudeDeg = 50.0 + i,
            )
        }
        // No deep-sky candidates at all: the free deep-sky slots must
        // NOT transfer to the constellations.
        val board = assembleBoard(many)
        assertEquals(2, board.size)
        assertEquals(
            setOf("constellation:C06", "constellation:C05"),
            board.map { it.subject.id }.toSet(),
        )
    }

    @Test
    fun categoryGatesExcludeBelowThresholdCandidates() {
        val board = assembleBoard(
            listOf(
                // Constellation window one minute short of two hours.
                candidate(constellation("And"), windowMinutes = 119),
                // Viewpoint culmination just under 25°.
                candidate(viewpoint("low"), culminationAltitudeDeg = 24.9),
                // Viewpoint window just under one hour.
                candidate(viewpoint("brief"), windowMinutes = 59),
                // One of each passing cleanly.
                candidate(constellation("Ori")),
                candidate(viewpoint("ok")),
            ),
        )
        assertEquals(
            setOf("constellation:Ori", "viewpoint:ok"),
            board.map { it.subject.id }.toSet(),
        )
    }

    @Test
    fun deepSkySelectionTakesTheBrightestFive() {
        val candidates = (1..7).map { i ->
            candidate(deepSky("D$i", magnitude = 7.0 - i), magnitude = 7.0 - i)
        }
        val board = assembleBoard(candidates)
        assertEquals(5, board.size)
        // D1..D7 carry magnitudes 6.0 down to 0.0: the five
        // brightest are D3..D7.
        assertEquals(
            setOf("D3", "D4", "D5", "D6", "D7"),
            board.map { it.subject.id }.toSet(),
        )
    }

    @Test
    fun boardOrderIsRecommendedStartThenCulmination() {
        val early = Instant.parse("2026-10-26T23:00:00Z")
        val late = Instant.parse("2026-10-27T02:00:00Z")
        val board = assembleBoard(
            listOf(
                candidate(viewpoint("late"), recommendedStart = late),
                candidate(
                    constellation("And"),
                    recommendedStart = early,
                    culmination = Instant.parse("2026-10-27T05:00:00Z"),
                ),
                candidate(
                    deepSky("M31", 3.4),
                    magnitude = 3.4,
                    recommendedStart = early,
                    culmination = Instant.parse("2026-10-27T03:00:00Z"),
                ),
            ),
        )
        // Equal recommended starts order by culmination instant; a
        // relative-best span's start is a recommended start like any
        // other (it is simply the window the definition produced).
        assertEquals(
            listOf("M31", "constellation:And", "viewpoint:late"),
            board.map { it.subject.id },
        )
    }

    @Test
    fun assemblyIsDeterministicAndDoesNotMutateItsInput() {
        val input = listOf(
            candidate(viewpoint("b"), culminationAltitudeDeg = 30.0),
            candidate(constellation("Cyg"), culminationAltitudeDeg = 80.0),
            candidate(viewpoint("a"), culminationAltitudeDeg = 60.0),
        )
        val first = assembleBoard(input).map { it.subject.id }
        val second = assembleBoard(input).map { it.subject.id }
        assertEquals(first, second)
        // The same-source regression (proposal §5 ⑤): the board is a
        // function of the night alone — no display state reaches it,
        // so filtering can never change membership or quotas.
        assertEquals(
            listOf("viewpoint:b", "constellation:Cyg", "viewpoint:a"),
            input.map { it.subject.id },
        )
    }

    // --- Radiant entry conditions (proposal §5 ⑤) ----------------------------

    private fun shower(
        code: String,
        peakLon: Double,
        activityStartLon: Double,
        activityEndLon: Double,
        radiantRaHours: Double = 3.0,
        radiantDecDeg: Double = 34.0,
        zhr: Int = 50,
    ) = MeteorShower(
        iauCode = code, nameEn = "$code shower", nameZh = "$code 流星雨",
        activityStartLon = activityStartLon, activityEndLon = activityEndLon,
        peakLon = peakLon, peakZhr = zhr,
        radiantRaHours = radiantRaHours, radiantDecDeg = radiantDecDeg,
        driftRaPerDay = 0.0, driftDecPerDay = 0.0,
        parent = null, velocityKmS = 40.0, populationIndex = 2.5,
    )

    private fun fakeCatalog(showers: List<MeteorShower>): MeteorShowerCatalog =
        object : MeteorShowerCatalog {
            override fun showers(): List<MeteorShower> = showers
            override val dataVersion: String = "fake"
        }

    /** Solar longitude of a civil date under the catalogue's mean-motion convention. */
    private fun solarLongitudeOf(date: LocalDate): Double =
        ChronoUnit.DAYS.between(LocalDate.of(date.year, 3, 20), date) * (360.0 / 365.25636)

    @Test
    fun radiantLaneRequiresActivePeakNearAndWindowLongEnough() {
        val peakDate = LocalDate.of(2026, 11, 5)
        val peakLon = solarLongitudeOf(peakDate)
        val good = shower("TST", peakLon, peakLon - 10.0, peakLon + 10.0)
        val useCase = TonightOverviewUseCase(
            ephemeris = ephemeris,
            showerCatalog = fakeCatalog(listOf(good)),
        )
        val atPeak = useCase.execute(alpharetta, peakDate)
        assertEquals(1, atPeak.radiantLanes.size)
        assertEquals("shower:TST", atPeak.radiantLanes[0].subject.id)
        assertEquals(50, atPeak.radiantLanes[0].meteorZhr)
        assertTrue(
            atPeak.moments.any {
                it.kind == TonightOverviewUseCase.MomentKind.RADIANT_CULMINATION
            },
        )
        // Five days past the peak: outside the ±3-day membership.
        val after = useCase.execute(alpharetta, peakDate.plusDays(5))
        assertTrue(after.radiantLanes.isEmpty())
        // The detail of a radiant carries peak and ZHR, and never a
        // recommended window.
        val cursor = peakDate.atTime(0, 30).atZone(alpharetta.zone).toInstant()
        val detail = useCase.evaluateTargetAtCursor(
            alpharetta, peakDate, cursor, atPeak.timeline, objectId = "shower:TST",
        )
        assertNotNull(detail)
        assertEquals(50, detail!!.meteorZhr)
        assertNull(detail.recommended)
    }

    @Test
    fun radiantLaneFailsWhenInactiveOrWindowTooShort() {
        val peakDate = LocalDate.of(2026, 11, 5)
        val peakLon = solarLongitudeOf(peakDate)
        // Activity starts the day AFTER the peak: at the peak the
        // shower is not yet active.
        val inactive = shower("INA", peakLon, peakLon + 1.0, peakLon + 12.0)
        // Radiant culminates below 20° from this latitude: window 0.
        val low = shower("LOW", peakLon, peakLon - 10.0, peakLon + 10.0, radiantDecDeg = -60.0)
        val useCase = TonightOverviewUseCase(
            ephemeris = ephemeris,
            showerCatalog = fakeCatalog(listOf(inactive, low)),
        )
        val result = useCase.execute(alpharetta, peakDate)
        assertTrue(result.radiantLanes.isEmpty())
    }

    @Test
    fun showerActiveOnFollowsTheSolarLongitudeWindow() {
        val peakDate = LocalDate.of(2026, 8, 12)
        val peakLon = solarLongitudeOf(peakDate)
        val shower = shower("ACT", peakLon, peakLon - 20.0, peakLon + 12.0)
        assertTrue(showerActiveOn(shower, peakDate))
        assertTrue(showerActiveOn(shower, peakDate.minusDays(19)))
        assertTrue(!showerActiveOn(shower, peakDate.plusDays(14)))
        assertTrue(!showerActiveOn(shower, peakDate.minusDays(40)))
    }

    // --- The golden night (proposal §5 ②) -------------------------------------

    @Test
    fun goldenNightFollowsTheBoardRules() {
        // The fixed golden night of the design samples (Alpharetta,
        // 2026-10-27). MEMBERSHIP follows the approved §3.2 rules —
        // the samples were drawn illustrations (their own row counts
        // disagree: 7 in one, 9 in another) and two of their members
        // contradict the rules outright: the galactic-centre segment
        // has only a ~19-minute night-cut window this night (below
        // the one-hour viewpoint gate), and Orion's centre culminates
        // at 58°, below several qualifying constellations competing
        // for the quota of two. The board's NUMBERS, however, are
        // exact, and this test pins those through the detail path
        // below, where membership does not interfere.
        val useCase = TonightOverviewUseCase(
            ephemeris = ephemeris,
            objectCatalog = JsonObjectCatalog(),
            showerCatalog = JsonMeteorShowerCatalog(),
            constellationCatalog = JsonConstellationCatalog(),
            wideFieldCatalog = JsonWideFieldCatalog(),
        )
        val date = LocalDate.of(2026, 10, 27)
        val result = useCase.execute(alpharetta, date)
        assertTrue(result.shortlistAvailable)
        val ids = result.shortlist.map { it.subject.id }
        // The membership the RULES produce for this night (the
        // sample board's named set is not reproducible from §3.2 —
        // see the comment at the top of this test). Constellations:
        // Lyra's centre culminates at 88.9° and Triangulum's at
        // 87.4°, ahead of Andromeda's 86.1° for the quota of two.
        // Deep sky: the unchanged pre-0.9.0 scan (M44's pass
        // culminates after sunrise and does not qualify it).
        fun categoryIds(category: TonightOverviewUseCase.SubjectCategory) =
            result.shortlist.filter { it.subject.category == category }
                .map { it.subject.id }.toSet()
        assertEquals(
            setOf("M45", "NGC1980", "M15", "M31", "NGC869"),
            categoryIds(TonightOverviewUseCase.SubjectCategory.DEEP_SKY),
        )
        assertEquals(
            setOf(
                "viewpoint:cygnus-milky-way", "viewpoint:cassiopeia-milky-way",
                "viewpoint:winter-milky-way",
            ),
            categoryIds(TonightOverviewUseCase.SubjectCategory.WIDE_FIELD),
        )
        assertEquals(
            setOf("constellation:Lyr", "constellation:Tri"),
            categoryIds(TonightOverviewUseCase.SubjectCategory.CONSTELLATION),
        )
        // The galactic-centre segment is gated out by the rules (its
        // night-cut window is ~19 minutes < 1 hour) — see the class
        // comment above; its figures are verified via the detail.
        assertTrue("viewpoint:galactic-centre" !in ids)
        // Category quotas bind.
        assertTrue(
            result.shortlist.count {
                it.subject.category == TonightOverviewUseCase.SubjectCategory.DEEP_SKY
            } <= TonightOverviewUseCase.QUOTA_DEEP_SKY,
        )
        assertTrue(
            result.shortlist.count {
                it.subject.category == TonightOverviewUseCase.SubjectCategory.WIDE_FIELD
            } <= TonightOverviewUseCase.QUOTA_WIDE_FIELD,
        )
        assertTrue(
            result.shortlist.count {
                it.subject.category == TonightOverviewUseCase.SubjectCategory.CONSTELLATION
            } <= TonightOverviewUseCase.QUOTA_CONSTELLATION,
        )
        // Board order: recommended-interval start, non-decreasing.
        val starts = result.shortlist.map {
            it.recommended?.interval?.start ?: it.culmination
        }
        assertEquals(starts.sorted(), starts)
        // Lanes and board are the same list, in the same order; the
        // popup and the lanes can never disagree (proposal §5 ⑤).
        assertEquals(ids, result.lanes.map { it.subject.id })
        // No shower peaks within ±3 days of this night: no radiant
        // lanes (the Orionids appear in the events section only).
        assertTrue(result.radiantLanes.isEmpty())

        // The design board's numbers, through the detail evaluation
        // (sample ⑫ and the golden-night notes).
        fun detailAt(id: String, hour: Int, minute: Int) =
            useCase.evaluateTargetAtCursor(
                alpharetta, date,
                date.atTime(hour, minute).atZone(alpharetta.zone).toInstant(),
                result.timeline, objectId = id,
            )
        fun assertCulmination(
            detail: TonightOverviewUseCase.TargetDetail?,
            hour: Int, minute: Int, altitudeDeg: Double,
        ) {
            assertNotNull(detail)
            val culmination = detail!!.culmination!!
            val local = culmination.atZone(alpharetta.zone)
            val expected = local.toLocalDate().atTime(hour, minute)
                .atZone(alpharetta.zone).toInstant()
            assertTrue(
                "culmination $culmination vs $expected",
                Duration.between(expected, culmination).abs() <= Duration.ofMinutes(15),
            )
            assertEquals(altitudeDeg, detail.culminationAltitudeDeg!!, 3.0)
        }
        assertCulmination(detailAt("constellation:Cyg", 21, 15), 19, 42, 82.0)
        assertCulmination(detailAt("constellation:Ori", 23, 30), 4, 45, 58.0)
        val m31 = result.shortlist.first { it.subject.id == "M31" }
        val m31Expected = date.atTime(23, 53).atZone(alpharetta.zone).toInstant()
        assertTrue(
            "M31 culmination ${m31.culmination}",
            Duration.between(m31Expected, m31.culmination).abs() <= Duration.ofMinutes(15),
        )
        // The galactic-centre segment (design board sample ⑪): the
        // board's culmination-side figures agree with the ephemeris
        // (culminated before the axis opens; ≥20° until ≈19:06), but
        // its day-boundary figures (sunset 17:52, night from 18:47)
        // run about an hour behind the app's — the app's sunset for
        // this place and date is 18:47:53 EDT and its astronomical
        // dusk 20:13. The assertions therefore pin the AGREEING
        // figures: the ≥20° run is already in progress when the axis
        // opens (its start is the axis start) and ends ≈19:06, and
        // the recommended window is the relative-best form.
        val gc = detailAt("viewpoint:galactic-centre", 19, 0)
        assertNotNull(gc)
        val gcWindow = gc!!.phases!!.window
        assertEquals(result.timeline.axisStart, gcWindow.start)
        val gcEnd = gcWindow.end.atZone(alpharetta.zone).toLocalTime()
        assertTrue(
            "GC window end $gcEnd",
            !gcEnd.isBefore(java.time.LocalTime.of(18, 56)) &&
                !gcEnd.isAfter(java.time.LocalTime.of(19, 18)),
        )
        assertTrue(gc.recommended!!.relativeBest)
    }

    @Test
    fun constellationDetailDerivesFramingFromItsExtent() {
        val useCase = TonightOverviewUseCase(
            ephemeris = ephemeris,
            objectCatalog = JsonObjectCatalog(),
            constellationCatalog = JsonConstellationCatalog(),
            wideFieldCatalog = JsonWideFieldCatalog(),
        )
        val date = LocalDate.of(2026, 10, 27)
        val overview = useCase.execute(alpharetta, date)
        val cursor = date.atTime(21, 15).atZone(alpharetta.zone).toInstant()
        val detail = useCase.evaluateTargetAtCursor(
            alpharetta, date, cursor, overview.timeline, objectId = "constellation:Cyg",
        )
        assertNotNull(detail)
        // Cygnus spans 38°: the framing derivation runs off the
        // extent through the shared §7.5 function. The half-frame
        // reference focal would be ≈23 mm, below the 24 mm floor,
        // so the range starts at the floor and the reference focal
        // IS 24 mm — the figure design board sample ⑫ shows.
        val framing = detail!!.framing
        assertTrue(framing is app.astroexposure.domain.camera.FullFrameFraming.Advice.Range)
        val range = framing as app.astroexposure.domain.camera.FullFrameFraming.Advice.Range
        assertEquals(24.0, range.lowFocalMm, 0.5)
        assertEquals(24.0, range.referenceFocalMm, 0.5)
        // An unknown namespaced id resolves to nothing, not a guess.
        assertNull(
            useCase.evaluateTargetAtCursor(
                alpharetta, date, cursor, overview.timeline, objectId = "constellation:Xxx",
            ),
        )
    }
}
