package app.astroexposure.data.catalog

import app.astroexposure.domain.catalog.DeepSkyObjectType
import app.astroexposure.domain.catalog.EclipseCatalog
import app.astroexposure.domain.catalog.EclipseType
import app.astroexposure.domain.catalog.LensCatalog
import app.astroexposure.domain.catalog.LightPollutionSource
import app.astroexposure.domain.catalog.MeteorShowerCatalog
import app.astroexposure.domain.catalog.ObjectCatalog
import app.astroexposure.domain.ephemeris.EclipseKind
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.units.Degrees
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the catalogue ports (DEVELOPMENT-RULES.md C.1):
 * written against the ports only. They currently run on the TEST FIXTURE
 * resources in data/src/test/resources/catalog/ (see FIXTURE.md there);
 * when the pipeline-produced main resources land, the integrating change
 * re-runs this same suite against them.
 */

class ObjectCatalogContractTest {

    private val catalog: ObjectCatalog = JsonObjectCatalog()

    @Test
    fun `M31 fields are mapped from the document`() {
        val m31 = catalog.byId("M31")
        assertNotNull(m31)
        assertEquals("Andromeda Galaxy", m31!!.name)
        assertEquals(0.712, m31.raHours, 1e-9)
        assertEquals(41.269, m31.decDeg, 1e-9)
        assertEquals(3.4, m31.magnitude!!, 1e-9)
        assertEquals(DeepSkyObjectType.GALAXY, m31.type)
        assertEquals(false, m31.surfaceBrightnessEstimated)
    }

    @Test
    fun `object type strings map to the domain enum`() {
        assertEquals(DeepSkyObjectType.NEBULA, catalog.byId("M42")!!.type)
        assertEquals(DeepSkyObjectType.OPEN_CLUSTER, catalog.byId("M45")!!.type)
        assertEquals(DeepSkyObjectType.GLOBULAR_CLUSTER, catalog.byId("M13")!!.type)
    }

    @Test
    fun `catalogue lists all fixture objects and misses unknown ids`() {
        assertEquals(4, catalog.allObjects().size)
        assertNull(catalog.byId("NGC-0000"))
        assertEquals("test-fixture-1", catalog.dataVersion)
    }

    @Test
    fun `a missing resource fails explicitly instead of returning empty`() {
        try {
            JsonObjectCatalog("/catalog/does-not-exist.json").allObjects()
            org.junit.Assert.fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("does-not-exist"))
        }
    }
}

class EclipseCatalogContractTest {

    private val catalog: EclipseCatalog = JsonEclipseCatalog()

    @Test
    fun `range filter includes the four 2026 events and honours the bounds`() {
        val events = catalog.eclipses(2026, 2035)
        assertEquals(4, events.size)
        assertTrue(events.all { it.date.year == 2026 })
        assertEquals(4, catalog.eclipses(2026, 2026).size)
        assertTrue(catalog.eclipses(2027, 2035).isEmpty())
    }

    @Test
    fun `the 2026-08-12 record is a total solar eclipse with path data`() {
        val record = catalog.eclipses(2026, 2035).first { it.date == LocalDate.of(2026, 8, 12) }
        assertEquals(EclipseKind.SOLAR, record.kind)
        assertEquals(EclipseType.TOTAL, record.type)
        assertEquals(294, record.pathWidthKm)
        assertNotNull(record.peakUtc)
    }

    @Test
    fun `lunar records carry no path width and the version is surfaced`() {
        val lunar = catalog.eclipses(2026, 2035).filter { it.kind == EclipseKind.LUNAR }
        assertEquals(2, lunar.size)
        assertTrue(lunar.all { it.pathWidthKm == null })
        assertEquals(EclipseType.TOTAL, lunar.first { it.date == LocalDate.of(2026, 3, 3) }.type)
        assertEquals(EclipseType.PARTIAL, lunar.first { it.date == LocalDate.of(2026, 8, 28) }.type)
        assertEquals("test-fixture-1", catalog.dataVersion)
    }
}

class MeteorShowerCatalogContractTest {

    private val catalog: MeteorShowerCatalog = JsonMeteorShowerCatalog()

    @Test
    fun `Perseids carry the fixture peak longitude and ZHR`() {
        val per = catalog.showers().first { it.iauCode == "PER" }
        assertEquals(140.0, per.peakLon, 1e-9)
        assertEquals(100, per.peakZhr)
        assertEquals("109P/Swift-Tuttle", per.parent)
    }

    @Test
    fun `Geminids are present and the version is surfaced`() {
        val gem = catalog.showers().first { it.iauCode == "GEM" }
        assertEquals(120, gem.peakZhr)
        assertEquals(262.2, gem.peakLon, 1e-9)
        assertEquals("test-fixture-1", catalog.dataVersion)
    }
}

class LensCatalogContractTest {

    private val catalog: LensCatalog = JsonLensCatalog()

    @Test
    fun `Sony 50mm GM prime has no tele aperture`() {
        val gm = catalog.builtInLenses().first { it.id == "sony-fe-50mm-f1.4-gm" }
        assertEquals("Sony FE 50mm F1.4 GM", gm.name)
        assertEquals(50.0, gm.focalMinMm, 1e-9)
        assertEquals(50.0, gm.focalMaxMm, 1e-9)
        assertEquals(1.4, gm.maxAperture, 1e-9)
        assertNull(gm.maxApertureAtMaxFocal)
        assertTrue(gm.isPrime)
    }

    @Test
    fun `variable aperture zoom maps maxApertureTele to the tele end`() {
        val zoom = catalog.builtInLenses().first { it.id == "tamron-28-200mm-f2.8-5.6" }
        assertEquals(5.6, zoom.maxApertureAtMaxFocal!!, 1e-9)
        assertEquals(2.8, zoom.maxApertureAt(28.0), 1e-9)
        assertEquals(5.6, zoom.maxApertureAt(200.0), 1e-9)
        assertEquals("test-fixture-1", catalog.dataVersion)
    }
}

class LightPollutionContractTest {

    private val source: LightPollutionSource = GridLightPollutionSource()

    private fun site(lat: Double, lon: Double) = GeoLocation(
        latitude = Degrees(lat),
        longitude = Degrees(lon),
        zone = ZoneId.of("America/New_York"),
    )

    @Test
    fun `the finest grid wins and its scale converts the raw cell`() {
        // NYC sits in the fine grid's raw-800 cell: 800 * 0.25 = 200.0.
        assertEquals(200.0, source.artificialBrightnessMcdPerM2(site(40.7128, -74.0060))!!, 1e-9)
    }

    @Test
    fun `coarse grid answers outside the fine grid`() {
        // Coarse raw 100 * 0.5 = 50.0.
        assertEquals(50.0, source.artificialBrightnessMcdPerM2(site(48.0, -72.0))!!, 1e-9)
    }

    @Test
    fun `a no-data fine cell falls back to the coarse grid`() {
        assertEquals(50.0, source.artificialBrightnessMcdPerM2(site(44.5, -74.5))!!, 1e-9)
    }

    @Test
    fun `no-data cells and off-grid points return null`() {
        assertNull(source.artificialBrightnessMcdPerM2(site(42.0, -77.0)))
        assertNull(source.artificialBrightnessMcdPerM2(site(20.0, 0.0)))
        assertEquals("test-fixture-1", source.dataVersion)
    }
}
