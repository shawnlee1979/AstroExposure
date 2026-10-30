package app.astroexposure.ui

import app.astroexposure.domain.catalog.ConstellationEntry
import app.astroexposure.domain.catalog.DeepSkyObject
import app.astroexposure.domain.catalog.DeepSkyObjectType
import app.astroexposure.domain.catalog.MeteorShower
import app.astroexposure.domain.catalog.WideFieldViewpoint
import app.astroexposure.domain.ephemeris.EquatorialCoordinates
import app.astroexposure.domain.units.Degrees
import app.astroexposure.usecase.TonightOverviewUseCase.TonightSubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The target detail dialog's step assembly (ui-design §7.5,
 * 0.12.0 three-step flow). The dialog composes exactly the steps
 * [detailSteps] returns, in list order, numbered by position —
 * so this list is the contract the layout stands on: step 1 is
 * always target info (the six-row grid lives there and nowhere
 * else), the finder is always last, and a radiant — which has
 * neither a framing chart nor a composition section — drops the
 * composition step outright and its finder renumbers to step 2
 * instead of leaving a gap.
 */
class DetailStepAssemblyTest {

    private fun deepSky(): TonightSubject = TonightSubject.DeepSky(
        DeepSkyObject(
            id = "M31", name = "M31", nameZh = null, raHours = 0.0, decDeg = 0.0,
            magnitude = 3.4, sizeMajorArcmin = 178.0, sizeMinorArcmin = 63.0,
            type = DeepSkyObjectType.GALAXY, constellation = "And",
            surfaceBrightness = null, surfaceBrightnessEstimated = false,
        ),
    )

    private fun constellation(): TonightSubject = TonightSubject.Constellation(
        ConstellationEntry(
            code = "Lyr", centerRaHours = 0.0, centerDecDeg = 0.0,
            sizeMajorDeg = 20, sizeMinorDeg = 10, brightStars = emptyList(),
            noteZh = "注", noteEn = "note",
        ),
    )

    private fun viewpoint(): TonightSubject = TonightSubject.Viewpoint(
        WideFieldViewpoint(
            id = "galactic-centre", nameZh = "银心段", nameEn = "Galactic centre",
            anchorRaHours = 0.0, anchorDecDeg = 0.0,
            sizeMajorDeg = 30, sizeMinorDeg = 12, noteZh = "注", noteEn = "note",
        ),
    )

    private fun radiant(): TonightSubject = TonightSubject.Radiant(
        shower = MeteorShower(
            iauCode = "STA", nameEn = "Southern Taurids", nameZh = "南金牛座流星雨",
            activityStartLon = 0.0, activityEndLon = 0.0, peakLon = 0.0, peakZhr = 7,
            radiantRaHours = 3.0, radiantDecDeg = 14.0,
            driftRaPerDay = 0.0, driftDecPerDay = 0.0,
            parent = null, velocityKmS = 27.0, populationIndex = 2.3,
        ),
        radiant = EquatorialCoordinates(3.0, Degrees(14.0)),
    )

    @Test
    fun deepSkyHasAllThreeStepsInUseOrder() {
        assertEquals(
            listOf(DetailStep.INFO, DetailStep.COMPOSE, DetailStep.FINDER),
            detailSteps(deepSky()),
        )
    }

    @Test
    fun constellationHasAllThreeStepsInUseOrder() {
        assertEquals(
            listOf(DetailStep.INFO, DetailStep.COMPOSE, DetailStep.FINDER),
            detailSteps(constellation()),
        )
    }

    @Test
    fun viewpointKeepsTheCompositionStep() {
        // A viewpoint has no framing chart, but its composition
        // section alone still makes a complete step 2 — the step
        // must not vanish with the chart.
        assertEquals(
            listOf(DetailStep.INFO, DetailStep.COMPOSE, DetailStep.FINDER),
            detailSteps(viewpoint()),
        )
    }

    @Test
    fun radiantDropsTheCompositionStepAndTheFinderRenumbers() {
        val steps = detailSteps(radiant())
        assertEquals(listOf(DetailStep.INFO, DetailStep.FINDER), steps)
        // Numbered by presentation position: the finder is step 2
        // of 2 — no empty step 2 is left behind for composition.
        assertEquals(2, steps.indexOf(DetailStep.FINDER) + 1)
        assertEquals(2, steps.size)
    }

    @Test
    fun everyCategoryStartsAtInfoAndEndsAtTheFinder() {
        // The navigation row lists exactly these steps, so this
        // also pins "the nav row only ever lists steps that
        // exist": its entries are this list, nothing else.
        for (subject in listOf(deepSky(), constellation(), viewpoint(), radiant())) {
            val steps = detailSteps(subject)
            assertEquals(DetailStep.INFO, steps.first())
            assertEquals(DetailStep.FINDER, steps.last())
            assertTrue(steps.size in 2..3)
            assertEquals(steps.distinct(), steps)
        }
    }
}
