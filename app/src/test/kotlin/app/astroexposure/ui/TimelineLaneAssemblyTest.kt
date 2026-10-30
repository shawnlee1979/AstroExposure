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
