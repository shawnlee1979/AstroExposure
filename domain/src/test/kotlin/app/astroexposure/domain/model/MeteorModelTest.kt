package app.astroexposure.domain.model

import app.astroexposure.domain.camera.BuiltInCameras
import app.astroexposure.domain.camera.Camera
import app.astroexposure.domain.catalog.MeteorShower
import app.astroexposure.domain.sky.BaselineSource
import app.astroexposure.domain.sky.SkyBrightness
import app.astroexposure.domain.sky.TotalSky
import app.astroexposure.domain.sky.skyElectronRate
import app.astroexposure.domain.strictness.Strictness
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenario goldens (design §5.5): under a dark sky the NPF trailing cap
 * arrives first; under an extremely bright sky (15 mpsas) with a wide fast
 * lens the sky-background cap arrives first. Expected caps are recomputed
 * from the single-implementation formulas (NPF and R_sky) — the model
 * under test owns only the min/limiting-factor/budget logic.
 */
class MeteorModelTest {

    private val shower = MeteorShower(
        iauCode = "PER", nameEn = "Perseids", nameZh = "英仙座",
        activityStartLon = 120.0, activityEndLon = 155.0, peakLon = 140.0,
        peakZhr = 100, radiantRaHours = 3.1, radiantDecDeg = 30.0,
        driftRaPerDay = 0.05, driftDecPerDay = 0.2, parent = "109P/Swift-Tuttle",
        velocityKmS = 59.0, populationIndex = 2.2,
    )

    private fun skyAt(mpsas: Double): TotalSky = TotalSky.compose(
        baselineArtificialMcdPerM2 = SkyBrightness.mpsasToTotalMcd(mpsas) - SkyBrightness.NATURAL_SKY_MCD_M2,
        baselineSource = BaselineSource.ATLAS,
        moonlightMcdPerM2 = 0.0,
    )

    private fun input(
        camera: app.astroexposure.domain.camera.Camera = BuiltInCameras.A1_II,
        focal: Double = 50.0,
        aperture: Double = 1.4,
        sky: TotalSky = skyAt(22.0),
        hours: Double = 2.0,
        moonIllum: Double = 0.0,
        moonUp: Boolean = false,
        daysFromPeak: Double = 0.0,
    ) = MeteorModel.Input(
        shower = shower, camera = camera, focalMm = focal, aperture = aperture,
        totalSky = sky, strictness = Strictness.STANDARD,
        shootingHours = hours, moonIllumination = moonIllum, moonUp = moonUp,
        daysFromPeak = daysFromPeak,
    )

    @Test
    fun `dark sky is trailing limited`() {
        val r = MeteorModel.evaluate(input())
        assertEquals(MeteorModel.LimitingFactor.TRAILING, r.limitingFactor)
        val npf = NpfModel.evaluate(
            NpfModel.Input(BuiltInCameras.A1_II, 50.0, 1.4, app.astroexposure.domain.units.Degrees(30.0), Strictness.STANDARD),
        ).maxShutter.value
        assertEquals(npf, r.singleFrameSeconds, 1e-9)
    }

    @Test
    fun `extremely bright sky is sky limited`() {
        val sky = skyAt(15.0)
        val r = MeteorModel.evaluate(
            input(camera = BuiltInCameras.ZV_E1, focal = 24.0, aperture = 1.4, sky = sky),
        )
        assertEquals(MeteorModel.LimitingFactor.SKY_BRIGHTNESS, r.limitingFactor)
        val expectedSkyCap = Strictness.STANDARD.backgroundTargetFraction *
            BuiltInCameras.ZV_E1.fullWellElectrons!! / skyElectronRate(15.0, 1.4, BuiltInCameras.ZV_E1, app.astroexposure.domain.sky.DEFAULT_QE_WHEN_UNRECORDED)
        assertEquals(expectedSkyCap, r.singleFrameSeconds, 0.01)
    }

    @Test
    fun `budget arithmetic counts frames and gigabytes`() {
        val r = MeteorModel.evaluate(input(hours = 2.0))
        val expectedFrames = floor(2.0 * 3600.0 / (r.singleFrameSeconds + 1.0)).toInt()
        assertEquals(expectedFrames, r.frameCount)
        assertEquals(expectedFrames * 60.0 / 1024.0, r.dataGb!!, 1e-9)
    }

    @Test
    fun `missing full well disables the sky cap explicitly`() {
        val bare = Camera(
            "bare", "Bare", 35.6, 23.8, 4240, 2832,
            readNoiseAtGainElectrons = 1.0, rawFileSizeMb = 14.0,
        )
        val r = MeteorModel.evaluate(input(camera = bare, sky = skyAt(15.0)))
        assertTrue(r.skyCapUnavailable)
        assertEquals(MeteorModel.LimitingFactor.TRAILING, r.limitingFactor)
    }

    @Test
    fun `missing file size leaves data volume null`() {
        val bare = Camera(
            "bare2", "Bare2", 35.6, 23.8, 4240, 2832,
            fullWellElectrons = 100000.0,
        )
        val r = MeteorModel.evaluate(input(camera = bare))
        assertNull(r.dataGb)
    }

    @Test
    fun `ISO adsorbs to gain point or flagged fallback`() {
        assertEquals(640, MeteorModel.evaluate(input()).iso)
        val zv = MeteorModel.evaluate(input(camera = BuiltInCameras.ZV_E1))
        assertEquals(800, zv.iso)
        assertTrue(zv.unverifiedGainFlag)
    }

    @Test
    fun `moon verdict thresholds are as recorded`() {
        // Peak night (|days| <= 0.5), Moon up, near-full: SEVERE.
        assertEquals(MeteorModel.MoonVerdict.SEVERE, MeteorModel.moonVerdict(0.95, moonUp = true, daysFromPeak = 0.0))
        // Moon down: no moonlight interference whatever the phase.
        assertEquals(MeteorModel.MoonVerdict.LOW, MeteorModel.moonVerdict(0.95, moonUp = false, daysFromPeak = 0.0))
        assertEquals(MeteorModel.MoonVerdict.LOW, MeteorModel.moonVerdict(0.6, moonUp = false, daysFromPeak = 0.0))
        // Full Moon up but off the peak night: MODERATE, not SEVERE.
        assertEquals(MeteorModel.MoonVerdict.MODERATE, MeteorModel.moonVerdict(0.95, moonUp = true, daysFromPeak = 3.0))
        assertEquals(MeteorModel.MoonVerdict.MODERATE, MeteorModel.moonVerdict(0.6, moonUp = true, daysFromPeak = 0.0))
        assertEquals(MeteorModel.MoonVerdict.LOW, MeteorModel.moonVerdict(0.3, moonUp = true, daysFromPeak = 0.0))
    }

    @Test
    fun `activity level follows the peak offset bands`() {
        assertEquals(MeteorModel.ActivityLevel.PEAK, MeteorModel.activityLevel(0.5))
        assertEquals(MeteorModel.ActivityLevel.PEAK, MeteorModel.activityLevel(-0.4))
        assertEquals(MeteorModel.ActivityLevel.NEAR, MeteorModel.activityLevel(1.5))
        assertEquals(MeteorModel.ActivityLevel.OFF_PEAK, MeteorModel.activityLevel(2.0))
    }
}
