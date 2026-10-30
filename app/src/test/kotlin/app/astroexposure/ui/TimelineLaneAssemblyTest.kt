package app.astroexposure.ui

import app.astroexposure.usecase.TonightOverviewUseCase.SubjectCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Regression tests for the 0.9.0 lane-assembly defects (fixed in
 * 0.9.1): the category filter chips counted the DISPLAY set — in
 * the cursor state the ranked shootable set of one instant — so
 * their numbers drifted as the cursor moved, and the cursor state's
 * lane assembly appended the whole-night radiant lanes on top of a
 * cursor evaluation whose universe already contained them, drawing
 * every radiant twice.
 */
class TimelineLaneAssemblyTest {

    private fun arc(
        id: String,
        category: SubjectCategory?,
        label: String = id,
    ): TimelineArc = TimelineArc(
        intervals = emptyList(),
        label = label,
        subjectId = id,
        subjectName = label,
        category = category,
    )

    /** Tonight's whole-night lanes: the board quotas plus one radiant. */
    private fun tonightArcs(): List<TimelineArc> = buildList {
        repeat(5) { add(arc("dso-$it", SubjectCategory.DEEP_SKY)) }
        repeat(3) { add(arc("viewpoint-$it", SubjectCategory.WIDE_FIELD)) }
        repeat(2) { add(arc("constellation-$it", SubjectCategory.CONSTELLATION)) }
        add(arc("shower:PER", SubjectCategory.METEOR, label = "radiant-lane"))
        add(arc("", null, label = "environment"))
    }

    @Test
    fun laneCountsComeFromTheWholeNightLanes() {
        val counts = laneCategoryCounts(tonightArcs())
        assertEquals(
            mapOf(
                SubjectCategory.DEEP_SKY to 5,
                SubjectCategory.WIDE_FIELD to 3,
                SubjectCategory.CONSTELLATION to 2,
                SubjectCategory.METEOR to 1,
            ),
            counts,
        )
    }

    @Test
    fun laneCountsAreTheSameWhateverTheDisplaySetHolds() {
        // A cursor-state display set at some instant: a different
        // composition entirely (deep-sky heavy, the radiant present
        // from the cursor evaluation). The chip counts are derived
        // from the whole-night lanes only, so they pin to the
        // night's values in both states — the pre-fix code counted
        // this display set and drifted with the cursor.
        val tonight = tonightArcs()
        val cursorDisplay = buildList {
            repeat(8) { add(arc("cursor-dso-$it", SubjectCategory.DEEP_SKY)) }
            add(arc("cursor-constellation", SubjectCategory.CONSTELLATION))
            add(arc("shower:PER", SubjectCategory.METEOR, label = "cursor-radiant"))
        }
        assertNotEquals(laneCategoryCounts(cursorDisplay), laneCategoryCounts(tonight))
        assertEquals(
            mapOf(
                SubjectCategory.DEEP_SKY to 5,
                SubjectCategory.WIDE_FIELD to 3,
                SubjectCategory.CONSTELLATION to 2,
                SubjectCategory.METEOR to 1,
            ),
            laneCategoryCounts(tonight),
        )
    }

    @Test
    fun cursorAssemblyKeepsExactlyOneLanePerRadiant() {
        val cursorRadiant = arc("shower:PER", SubjectCategory.METEOR, label = "cursor")
        val targets = listOf(
            arc("M31", SubjectCategory.DEEP_SKY),
            cursorRadiant,
        )
        val radiantLanes = listOf(
            arc("shower:PER", SubjectCategory.METEOR, label = "whole-night"),
            arc("shower:GEM", SubjectCategory.METEOR, label = "whole-night"),
        )
        val merged = mergeCursorStateArcs(targets, radiantLanes)
        assertEquals(
            listOf("M31", "shower:PER", "shower:GEM"),
            merged.map { it.subjectId },
        )
        // The surviving PER lane is the cursor-evaluated one.
        assertSame(cursorRadiant, merged.single { it.subjectId == "shower:PER" })
        assertEquals(1, merged.count { it.category == SubjectCategory.METEOR && it.subjectId == "shower:PER" })
    }

    // --- Targets sheet sets (ui-design §7.8, 0.10.0) -------------------------

    private fun target(
        id: String,
        category: SubjectCategory,
        onBoard: Boolean = false,
    ): TonightTarget = TonightTarget(
        subjectId = id,
        category = category,
        name = id,
        tag = "",
        keyLine = "",
        onBoard = onBoard,
        recommendedStart = null,
        recommendedEnd = null,
        relativeBest = false,
        culmination = java.time.Instant.parse("2026-10-29T05:00:00Z"),
    )

    /** A qualified set in the golden night's shape: 45/5/31/1, board 5/3/2. */
    private fun goldenTargets(): List<TonightTarget> = buildList {
        repeat(45) { add(target("dso-$it", SubjectCategory.DEEP_SKY, onBoard = it < 5)) }
        repeat(5) { add(target("viewpoint-$it", SubjectCategory.WIDE_FIELD, onBoard = it < 3)) }
        repeat(31) { add(target("constellation-$it", SubjectCategory.CONSTELLATION, onBoard = it < 2)) }
        add(target("shower:STA", SubjectCategory.METEOR))
    }

    @Test
    fun targetsBadgeIsTheFilteredQualifiedTotal() {
        val all = goldenTargets()
        // Unfiltered: the badge is the whole qualified set, and the
        // header line's second number is the board prefix.
        assertEquals(82, visibleTargets(all, emptySet()).size)
        assertEquals(10, visibleTargets(all, emptySet()).count { it.onBoard })
        // One category off: both numbers shrink by that category's
        // qualified rows and its board members respectively.
        val filtered = visibleTargets(all, setOf(SubjectCategory.CONSTELLATION))
        assertEquals(51, filtered.size)
        assertEquals(8, filtered.count { it.onBoard })
    }

    @Test
    fun targetsZonesFollowChipOrderAndHideEmptyCategories() {
        val zones = targetsZones(goldenTargets())
        assertEquals(
            listOf(
                SubjectCategory.DEEP_SKY, SubjectCategory.WIDE_FIELD,
                SubjectCategory.CONSTELLATION, SubjectCategory.METEOR,
            ),
            zones.map { it.first },
        )
        assertEquals(listOf(45, 5, 31, 1), zones.map { it.second.size })
        // A night with no qualifying radiant has no meteor zone at
        // all — never an empty header.
        val noRadiant = goldenTargets().filter { it.category != SubjectCategory.METEOR }
        assertEquals(3, targetsZones(noRadiant).size)
        // The filtered-out category's zone is absent as well.
        val filtered = visibleTargets(noRadiant, setOf(SubjectCategory.WIDE_FIELD))
        assertEquals(
            listOf(SubjectCategory.DEEP_SKY, SubjectCategory.CONSTELLATION),
            targetsZones(filtered).map { it.first },
        )
    }

    @Test
    fun hiddenTargetsCategoriesCarryTheirQualifiedCounts() {
        val all = goldenTargets()
        assertEquals(
            listOf(SubjectCategory.CONSTELLATION to 31),
            hiddenTargetsCategories(all, setOf(SubjectCategory.CONSTELLATION)),
        )
        // A hidden category with nothing qualified tonight is not
        // named: the notice speaks only of sets the sheet is
        // actually withholding.
        val noRadiant = all.filter { it.category != SubjectCategory.METEOR }
        assertEquals(
            emptyList<Pair<SubjectCategory, Int>>(),
            hiddenTargetsCategories(noRadiant, setOf(SubjectCategory.METEOR)),
        )
        // Several hidden: chip order, each with its own count.
        assertEquals(
            listOf(
                SubjectCategory.DEEP_SKY to 45,
                SubjectCategory.METEOR to 1,
            ),
            hiddenTargetsCategories(
                all,
                setOf(SubjectCategory.METEOR, SubjectCategory.DEEP_SKY),
            ),
        )
    }

    @Test
    fun onlyLaneOwningRowsCanHighlight() {
        // Board rows and radiants own lanes; a non-board qualified
        // row must never light one (and none is drawn for it).
        assertEquals(true, target("b", SubjectCategory.DEEP_SKY, onBoard = true).hasLane)
        assertEquals(true, target("r", SubjectCategory.METEOR).hasLane)
        assertEquals(false, target("q", SubjectCategory.CONSTELLATION).hasLane)
    }

    @Test
    fun cursorAssemblyCollapsesRadiantDuplicatesAndPassesIdlessLanes() {
        val targets = listOf(arc("M45", SubjectCategory.DEEP_SKY))
        val radiantLanes = listOf(
            arc("shower:PER", SubjectCategory.METEOR),
            arc("shower:PER", SubjectCategory.METEOR),
            arc("", null, label = "environment-a"),
            arc("", null, label = "environment-b"),
        )
        val merged = mergeCursorStateArcs(targets, radiantLanes)
        assertEquals(1, merged.count { it.subjectId == "shower:PER" })
        // Id-less lanes have no identity to collide on: both pass.
        assertEquals(2, merged.count { it.subjectId.isEmpty() })
        assertEquals(4, merged.size)
    }
}
