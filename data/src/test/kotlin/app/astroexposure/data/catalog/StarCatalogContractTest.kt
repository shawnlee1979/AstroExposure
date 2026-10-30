package app.astroexposure.data.catalog

import app.astroexposure.domain.catalog.ConstellationLines
import app.astroexposure.domain.catalog.StarCatalog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the framing chart's star and line ports
 * (ui-design §7.10), run on the fixture products in
 * data/src/test/resources/catalog/ (see FIXTURE.md there): three
 * stars, two line figures.
 */
class StarCatalogContractTest {

    private val catalog: StarCatalog = JsonStarCatalog()
    private val lines: ConstellationLines = JsonConstellationLines()

    @Test
    fun `stars are mapped with their names joined from the side table`() {
        val stars = catalog.stars()
        assertEquals(3, stars.size)
        val betelgeuse = stars.first { it.id == 1001 }
        assertEquals(88.7929, betelgeuse.raDeg, 1e-9)
        assertEquals(7.4071, betelgeuse.decDeg, 1e-9)
        assertEquals(0.42, betelgeuse.magnitude, 1e-9)
        assertEquals("Betelgeuse", betelgeuse.properName)
        assertEquals("α", betelgeuse.bayerName)
        assertEquals("参宿四", betelgeuse.nameZh)
        val unnamed = stars.first { it.id == 1002 }
        assertNull(unnamed.properName)
        assertNull(unnamed.bayerName)
        assertNull(unnamed.nameZh)
        assertEquals("test-fixture-1", catalog.dataVersion)
    }

    @Test
    fun `line figures arrive as star id chains`() {
        assertEquals(
            mapOf(
                "Ori" to listOf(listOf(1001, 1002)),
                "Cyg" to listOf(listOf(1003, 1001)),
            ),
            lines.lines(),
        )
        assertEquals("test-fixture-1", lines.dataVersion)
    }

    @Test
    fun `the binary record parser rejects a bad magic and a truncated body`() {
        assertThrows(IllegalStateException::class.java) {
            JsonStarCatalog.parseStarRecords(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), "test")
        }
        val oneRecord = ByteBuffer.allocate(8 + 14).order(ByteOrder.LITTLE_ENDIAN)
        oneRecord.put("AEST".toByteArray(Charsets.US_ASCII))
        oneRecord.putInt(2) // claims two records, carries one
        oneRecord.putInt(7)
        oneRecord.putInt(887929)
        oneRecord.putInt(74071)
        oneRecord.putShort(42)
        assertThrows(IllegalStateException::class.java) {
            JsonStarCatalog.parseStarRecords(oneRecord.array(), "test")
        }
    }

    @Test
    fun `the binary record parser dequantises degrees and magnitudes`() {
        val bytes = ByteBuffer.allocate(8 + 14).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("AEST".toByteArray(Charsets.US_ASCII))
        bytes.putInt(1)
        bytes.putInt(27919)
        bytes.putInt(887929) // 88.7929 degrees
        bytes.putInt(74071) // 7.4071 degrees
        bytes.putShort(42) // mag 0.42
        val records = JsonStarCatalog.parseStarRecords(bytes.array(), "test")
        assertEquals(1, records.size)
        assertEquals(27919, records[0].id)
        assertEquals(88.7929, records[0].raDeg, 1e-9)
        assertEquals(7.4071, records[0].decDeg, 1e-9)
        assertEquals(0.42, records[0].magnitude, 1e-9)
        assertTrue(records[0].raDeg in 88.79..88.80)
    }
}
