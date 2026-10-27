package app.astroexposure.domain.geo

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinatesTest {

    @Test
    fun rangeEdgesAreValidAndOutsideIsNot() {
        assertTrue(Coordinates.isValid(90.0, 180.0))
        assertTrue(Coordinates.isValid(-90.0, -180.0))
        assertTrue(Coordinates.isValid(0.0, 0.0))
        assertFalse(Coordinates.isValid(90.01, 0.0))
        assertFalse(Coordinates.isValid(0.0, -180.01))
        assertFalse(Coordinates.isValid(Double.NaN, 0.0))
        assertFalse(Coordinates.isValid(0.0, Double.POSITIVE_INFINITY))
    }
}

class SiteTimeZoneTest {

    private val fallback: ZoneId = ZoneId.of("UTC")

    @Test
    fun knownCitiesResolveToTheirCivilZones() {
        assertEquals(ZoneId.of("America/New_York"), SiteTimeZone.resolveOrNull(40.7128, -74.0060))
        assertEquals(ZoneId.of("America/Chicago"), SiteTimeZone.resolveOrNull(41.8781, -87.6298))
        assertEquals(ZoneId.of("America/Los_Angeles"), SiteTimeZone.resolveOrNull(37.7749, -122.4194))
        assertEquals(ZoneId.of("Europe/London"), SiteTimeZone.resolveOrNull(51.5074, -0.1278))
        assertEquals(ZoneId.of("Europe/Dublin"), SiteTimeZone.resolveOrNull(53.3498, -6.2603))
        assertEquals(ZoneId.of("Asia/Shanghai"), SiteTimeZone.resolveOrNull(39.9042, 116.4074))
        assertEquals(ZoneId.of("Asia/Tokyo"), SiteTimeZone.resolveOrNull(35.6762, 139.6503))
        assertEquals(ZoneId.of("Asia/Seoul"), SiteTimeZone.resolveOrNull(37.5665, 126.9780))
        assertEquals(ZoneId.of("Australia/Sydney"), SiteTimeZone.resolveOrNull(-33.8688, 151.2093))
    }

    @Test
    fun unresolvedCoordinatesFallBackExplicitly() {
        // Mid-Pacific: no box covers open ocean.
        assertNull(SiteTimeZone.resolveOrNull(0.0, -150.0))
        assertEquals(fallback, SiteTimeZone.resolve(0.0, -150.0, fallback))
        // Invalid coordinates never resolve.
        assertNull(SiteTimeZone.resolveOrNull(123.0, 0.0))
        assertEquals(fallback, SiteTimeZone.resolve(123.0, 0.0, fallback))
    }
}
