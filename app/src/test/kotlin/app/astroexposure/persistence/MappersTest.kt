package app.astroexposure.persistence

import app.astroexposure.domain.camera.BuiltInCameras
import app.astroexposure.domain.lens.Lens
import app.astroexposure.domain.tracking.BuiltInTrackingProfiles
import app.astroexposure.domain.units.Degrees
import app.astroexposure.domain.geo.GeoLocation
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MappersTest {

    @Test
    fun cameraRoundTripPreservesAllFields() {
        val camera = BuiltInCameras.ZV_E1
        assertEquals(camera, camera.toEntity(custom = false).toDomain())
    }

    @Test
    fun lensRoundTripPreservesVariableAperture() {
        val lens = Lens(
            id = "x", name = "Zoom", mount = "E",
            focalMinMm = 24.0, focalMaxMm = 70.0,
            maxAperture = 2.8, maxApertureAtMaxFocal = 4.0,
        )
        assertEquals(lens, lens.toEntity(custom = true).toDomain())
    }

    @Test
    fun trackingProfileRoundTripKeepsDriftNull() {
        val profile = BuiltInTrackingProfiles.POLARIS_SINGLE_STAR
        val back = profile.toEntity().toDomain()
        assertEquals(profile, back)
        assertNull(back.typicalDrift)
        assertNull(back.worstDrift)
    }

    @Test
    fun seedTrackingProfilesCarryNoDriftValues() {
        // Design §2.5/Q11: profiles are seeded empty; any drift value here
        // would be a fabricated placeholder.
        for (entity in SeedData.trackingProfileEntities()) {
            assertNull(entity.typicalDriftArcsecPerMin)
            assertNull(entity.worstDriftArcsecPerMin)
            assertNull(entity.measuredOnEpochDay)
        }
    }

    @Test
    fun seedCamerasAreTheTwoBuiltIns() {
        assertEquals(
            setOf(BuiltInCameras.A1_II.id, BuiltInCameras.ZV_E1.id),
            SeedData.cameraEntities().map { it.id }.toSet(),
        )
    }

    @Test
    fun placeRoundTrip() {
        val place = SavedPlace(
            id = "p1",
            name = "Home",
            location = GeoLocation(Degrees(40.7), Degrees(-74.0), 10.0, ZoneId.of("America/New_York"), "Home"),
            manualBortle = 6,
        )
        assertEquals(place, place.toEntity().toDomain())
    }

    @Test
    fun planRoundTrip() {
        val plan = SavedPlan(
            id = 7,
            createdAt = java.time.Instant.ofEpochMilli(123456789L),
            date = LocalDate.of(2026, 8, 12),
            category = "WIDE_FIELD",
            title = "Wide field",
            bodyText = "body",
            payloadJson = "{}",
        )
        assertEquals(plan, plan.toEntity().toDomain())
    }

    @Test
    fun customCameraValidationRejectsBlankNameAndBadGeometry() {
        assertTrue(
            validateCustomCamera("c", " ", 36.0, 24.0, 6000, 4000)
                is FormResult.Invalid,
        )
        val bad = validateCustomCamera("c", "Cam", 0.0, 24.0, 6000, 4000)
        assertEquals(FormError.NON_POSITIVE_DIMENSION, (bad as FormResult.Invalid).error)
        assertTrue(
            validateCustomCamera("c", "Cam", 36.0, 24.0, 6000, 4000)
                is FormResult.Ok,
        )
    }

    @Test
    fun customLensValidationRejectsInvertedRange() {
        val r = validateCustomLens("l", "L", "E", 70.0, 24.0, 2.8, null)
        assertEquals(FormError.FOCAL_RANGE_INVERTED, (r as FormResult.Invalid).error)
        val ok = validateCustomLens("l", "L", "E", 24.0, 70.0, 2.8, 4.0)
        assertTrue(ok is FormResult.Ok)
    }

    @Test
    fun placeValidationRejectsOutOfRangeCoordinates() {
        val r = validatePlace(
            "p", "P", 91.0, 0.0, 0.0, ZoneId.of("UTC"), null,
        )
        assertEquals(FormError.LATITUDE_OUT_OF_RANGE, (r as FormResult.Invalid).error)
        val badBortle = validatePlace("p", "P", 0.0, 0.0, 0.0, ZoneId.of("UTC"), 10)
        assertEquals(FormError.BORTLE_OUT_OF_RANGE, (badBortle as FormResult.Invalid).error)
    }

    @Test
    fun driftEntryValidationRejectsNegative() {
        assertEquals(FormError.DRIFT_NEGATIVE, validateDriftEntry(-1.0, null))
        assertNull(validateDriftEntry(10.0, 20.0))
        assertNull(validateDriftEntry(null, null))
    }

    @Test
    fun driftEntryValidationRejectsZero() {
        // Zero is not a physical measurement: it would divide by zero
        // in the drift model and silently clamp to the longest shutter.
        assertEquals(FormError.DRIFT_NEGATIVE, validateDriftEntry(0.0, null))
        assertEquals(FormError.DRIFT_NEGATIVE, validateDriftEntry(null, 0.0))
    }

    @Test
    fun validationRejectsNonFiniteNumbers() {
        // NaN passes every ordered comparison, so without an explicit
        // finiteness check it used to sail into the database.
        val cam = validateCustomCamera("c", "Cam", Double.NaN, 24.0, 6000, 4000)
        assertEquals(FormError.NON_FINITE_VALUE, (cam as FormResult.Invalid).error)
        val lens = validateCustomLens("l", "L", "E", 24.0, 70.0, Double.NaN, null)
        assertEquals(FormError.NON_FINITE_VALUE, (lens as FormResult.Invalid).error)
        val lensInf = validateCustomLens("l", "L", "E", 24.0, Double.POSITIVE_INFINITY, 2.8, null)
        assertEquals(FormError.NON_FINITE_VALUE, (lensInf as FormResult.Invalid).error)
        assertEquals(FormError.NON_FINITE_VALUE, validateDriftEntry(Double.NaN, null))
        assertEquals(FormError.NON_FINITE_VALUE, validateDriftEntry(null, Double.NEGATIVE_INFINITY))
    }
}
