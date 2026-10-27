package app.astroexposure.data.catalog

import app.astroexposure.domain.catalog.LightPollutionSource
import app.astroexposure.domain.geo.GeoLocation
import kotlin.math.floor

/**
 * [LightPollutionSource] over the bundled light-pollution grid product
 * `catalog/lp-grid.bin` (Falchi atlas resampled at build time, design
 * section 2.6). Binary layout:
 *
 *   [4 bytes magic "AEGL"]
 *   [4 bytes big-endian header length]
 *   [header JSON, UTF-8: {schemaVersion, dataVersion?, grids: [...]}]
 *   [data area: uint16 little-endian cells]
 *
 * Each grid descriptor carries latMin/latMax/lonMin/lonMax/stepDeg,
 * rows/cols, `offset` (byte offset of the grid's first cell RELATIVE to
 * the start of the data area) and `scale`. A stored cell value times
 * `scale` is the artificial sky luminance in mcd/m²; 0xFFFF marks
 * "no data". Cells are row-major, row 0 at the latMax (northern) edge,
 * column 0 at lonMin.
 *
 * Lookup: grids are tried finest-first (smallest stepDeg); a no-data cell
 * falls through to the next coarser grid covering the point. A point
 * outside every grid, or no-data in all of them, returns null so the
 * caller takes the explicit design section 2.8 fallback.
 */
class GridLightPollutionSource(
    private val resourcePath: String = "/catalog/lp-grid.bin",
) : LightPollutionSource {

    private data class Grid(
        val latMin: Double,
        val latMax: Double,
        val lonMin: Double,
        val lonMax: Double,
        val stepDeg: Double,
        val rows: Int,
        val cols: Int,
        val offset: Int,
        val scale: Double,
    )

    private data class Loaded(
        val dataVersion: String,
        val grids: List<Grid>,
        val dataArea: ByteArray,
    )

    private val loaded: Loaded by lazy { load() }

    private fun load(): Loaded {
        val bytes = CatalogResources.readBytes(resourcePath)
        val context = resourcePath
        if (bytes.size < HEADER_PREFIX_BYTES || !bytes.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw IllegalStateException("$context: bad magic, expected 'AEGL'")
        }
        val headerLength = ((bytes[4].toInt() and 0xFF) shl 24) or
            ((bytes[5].toInt() and 0xFF) shl 16) or
            ((bytes[6].toInt() and 0xFF) shl 8) or
            (bytes[7].toInt() and 0xFF)
        val headerEnd = HEADER_PREFIX_BYTES + headerLength
        if (headerLength <= 0 || headerEnd > bytes.size) {
            throw IllegalStateException("$context: header length $headerLength out of bounds")
        }
        val header = JsonMini.parse(
            bytes.copyOfRange(HEADER_PREFIX_BYTES, headerEnd).toString(Charsets.UTF_8),
        ).asObject(context)
        val schema = header.intField("schemaVersion", context)
        if (schema != SUPPORTED_SCHEMA_VERSION) {
            throw IllegalStateException(
                "$context: unsupported schemaVersion $schema (supported: $SUPPORTED_SCHEMA_VERSION)",
            )
        }
        val grids = header.required("grids", context).asArray(context).map { item ->
            val g = item.asObject(context)
            Grid(
                latMin = g.doubleField("latMin", context),
                latMax = g.doubleField("latMax", context),
                lonMin = g.doubleField("lonMin", context),
                lonMax = g.doubleField("lonMax", context),
                stepDeg = g.doubleField("stepDeg", context),
                rows = g.intField("rows", context),
                cols = g.intField("cols", context),
                offset = g.intField("offset", context),
                scale = g.doubleField("scale", context),
            )
        }.sortedBy { it.stepDeg } // finest first, regardless of header order
        val dataArea = bytes.copyOfRange(headerEnd, bytes.size)
        for (grid in grids) {
            val needed = grid.offset + grid.rows * grid.cols * 2
            if (grid.offset < 0 || needed > dataArea.size) {
                throw IllegalStateException(
                    "$context: grid at offset ${grid.offset} needs $needed data bytes, " +
                        "data area has ${dataArea.size}",
                )
            }
        }
        val version = header.optionalString("dataVersion", context)
            ?: "AEGL schema $schema"
        return Loaded(version, grids, dataArea)
    }

    override fun artificialBrightnessMcdPerM2(location: GeoLocation): Double? {
        val lat = location.latitude.value
        val lon = location.longitude.value
        val data = loaded.dataArea
        for (grid in loaded.grids) {
            if (lat < grid.latMin || lat > grid.latMax || lon < grid.lonMin || lon > grid.lonMax) {
                continue
            }
            val row = floor((grid.latMax - lat) / grid.stepDeg).toInt()
                .coerceIn(0, grid.rows - 1)
            val col = floor((lon - grid.lonMin) / grid.stepDeg).toInt()
                .coerceIn(0, grid.cols - 1)
            val index = grid.offset + (row * grid.cols + col) * 2
            val raw = (data[index].toInt() and 0xFF) or ((data[index + 1].toInt() and 0xFF) shl 8)
            if (raw == NO_DATA) continue // fall through to a coarser grid
            return raw * grid.scale
        }
        return null
    }

    override val dataVersion: String get() = loaded.dataVersion

    private companion object {
        val MAGIC = byteArrayOf('A'.code.toByte(), 'E'.code.toByte(), 'G'.code.toByte(), 'L'.code.toByte())
        const val HEADER_PREFIX_BYTES = 8
        const val NO_DATA = 0xFFFF
    }
}
