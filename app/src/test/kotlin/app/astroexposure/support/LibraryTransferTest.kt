package app.astroexposure.support

import app.astroexposure.domain.camera.BuiltInCameras
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.lens.Lens
import app.astroexposure.domain.tracking.BuiltInTrackingProfiles
import app.astroexposure.domain.units.Degrees
import app.astroexposure.persistence.SavedPlace
import app.astroexposure.persistence.SavedPlan
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTransferTest {

    private val lens = Lens(
        id = "custom-1", name = "My 35mm \"fast\" lens", mount = "E",
        focalMinMm = 35.0, focalMaxMm = 35.0, maxAperture = 1.4,
    )
    private val place = SavedPlace(
        id = "p1", name = "Dark site",
        location = GeoLocation(
            Degrees(41.0), Degrees(-73.5), 120.0, ZoneId.of("America/New_York"), "Dark site",
        ),
        manualBortle = null,
    )
    private val plan = SavedPlan(
        id = 3,
        createdAt = Instant.parse("2026-08-01T12:00:00Z"),
        date = LocalDate.of(2026, 8, 12),
        category = "METEOR",
        title = "Perseids",
        bodyText = "20 s × many\nsecond line",
        payloadJson = "{\"focal\":35}",
    )

    private fun sampleExport(): String = LibraryTransfer.export(
        cameras = listOf(BuiltInCameras.A1_II to false),
        lenses = listOf(lens to true),
        trackingProfiles = listOf(BuiltInTrackingProfiles.POLARIS_SINGLE_STAR),
        places = listOf(place),
        plans = listOf(plan),
        exportedAt = Instant.parse("2026-10-26T00:00:00Z"),
    )

    @Test
    fun roundTripPreservesLibraryAndPlans() {
        val result = LibraryTransfer.import(sampleExport())
        val backup = (result as LibraryTransfer.ImportResult.Ok).backup
        assertEquals(0, backup.skippedEntries)
        assertEquals(listOf(BuiltInCameras.A1_II), backup.cameras)
        assertEquals(listOf(lens), backup.lenses)
        assertEquals(setOf("custom-1"), backup.customLensIds)
        assertEquals(listOf(place), backup.places)
        assertEquals(1, backup.plans.size)
        assertEquals(plan.title, backup.plans[0].title)
        assertEquals(plan.bodyText, backup.plans[0].bodyText)
        assertEquals(plan.payloadJson, backup.plans[0].payloadJson)
        // Profiles come back with drift still unmeasured.
        assertEquals(null, backup.trackingProfiles[0].typicalDrift)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val text = sampleExport().replace(
            "\"format\": \"${LibraryTransfer.FORMAT}\"",
            "\"format\": \"${LibraryTransfer.FORMAT}\", \"futureField\": {\"x\": 1}",
        )
        val result = LibraryTransfer.import(text)
        assertTrue(result is LibraryTransfer.ImportResult.Ok)
    }

    @Test
    fun wrongFormatIsRejected() {
        val result = LibraryTransfer.import("{\"format\": \"something-else\"}")
        assertTrue(result is LibraryTransfer.ImportResult.Failed)
    }

    @Test
    fun malformedJsonIsRejected() {
        val result = LibraryTransfer.import("{not json")
        assertTrue(result is LibraryTransfer.ImportResult.Failed)
    }

    @Test
    fun invalidEntriesAreSkippedAndCounted() {
        val text = """
            {"format": "astroexposure-backup", "schemaVersion": 1,
             "lenses": [{"id": "bad", "name": "Bad", "mount": "E",
                          "focalMinMm": 70, "focalMaxMm": 24, "maxAperture": 2.8},
                         {"id": "ok", "name": "Ok", "mount": "E",
                          "focalMinMm": 24, "focalMaxMm": 24, "maxAperture": 2.0}]}
        """.trimIndent()
        val backup = (LibraryTransfer.import(text) as LibraryTransfer.ImportResult.Ok).backup
        assertEquals(1, backup.skippedEntries)
        assertEquals(listOf("ok"), backup.lenses.map { it.id })
    }

    @Test
    fun planTextExportContainsBody() {
        val text = PlanTextExport.plansToText(listOf(plan))
        assertTrue(text.contains("Perseids"))
        assertTrue(text.contains("second line"))
    }
}
