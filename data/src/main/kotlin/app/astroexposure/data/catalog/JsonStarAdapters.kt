package app.astroexposure.data.catalog

import app.astroexposure.domain.catalog.ChartStar
import app.astroexposure.domain.catalog.ConstellationLines
import app.astroexposure.domain.catalog.StarCatalog
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * Star and line adapters for the framing chart (ui-design §7.10),
 * reading the products of scripts/build_stars.py and
 * scripts/build_lines.py. The star records live in a quantized
 * binary (`catalog/stars.bin.gz`, built from the HYG v4.4 bright
 * subset); names ride in `catalog/star-names.json`, which also
 * carries the pair's dataVersion (one pipeline emits both). A
 * malformed product throws [IllegalStateException] — adapters never
 * fall back to an empty sky (DEVELOPMENT-RULES.md E.5).
 */

/** [StarCatalog] over `catalog/stars.bin.gz` + `catalog/star-names.json`. */
class JsonStarCatalog(
    private val binPath: String = "/catalog/stars.bin.gz",
    private val namesPath: String = "/catalog/star-names.json",
) : StarCatalog {

    private data class Loaded(val dataVersion: String, val stars: List<ChartStar>)

    private val loaded: Loaded by lazy {
        val raw = GZIPInputStream(
            ByteArrayInputStream(CatalogResources.readBytes(binPath)),
        ).readBytes()
        val records = parseStarRecords(raw, binPath)
        val root = CatalogResources.readDocument(namesPath)
        val names = root.required("names", namesPath).asObject(namesPath)
        val stars = records.map { record ->
            val entry = names[record.id.toString()]?.asObject(namesPath)
            ChartStar(
                id = record.id,
                raDeg = record.raDeg,
                decDeg = record.decDeg,
                magnitude = record.magnitude,
                properName = entry?.optionalString("proper", namesPath),
                bayerName = entry?.optionalString("bayer", namesPath),
                nameZh = entry?.optionalString("nameZh", namesPath),
            )
        }
        Loaded(root.stringField("dataVersion", namesPath), stars)
    }

    override fun stars(): List<ChartStar> = loaded.stars

    override val dataVersion: String get() = loaded.dataVersion

    internal data class StarRecord(
        val id: Int, val raDeg: Double, val decDeg: Double, val magnitude: Double,
    )

    internal companion object {
        /** Record size in bytes: id + ra + dec (int32 each) + magnitude (int16). */
        const val RECORD_BYTES = 14

        /** Header size: 4-byte magic + int32 record count. */
        const val HEADER_BYTES = 8

        /**
         * Parses the decompressed star product: magic "AEST", an
         * int32 record count, then fixed records of quantized values
         * (degrees x 1e4, magnitudes x 100 — build_stars.py owns the
         * format). Exposed for the adapter's own tests.
         */
        fun parseStarRecords(raw: ByteArray, context: String): List<StarRecord> {
            if (raw.size < HEADER_BYTES ||
                raw.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "AEST"
            ) {
                throw IllegalStateException("$context: bad magic, expected 'AEST'")
            }
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val count = buffer.getInt(4)
            if (count < 0 || raw.size != HEADER_BYTES + count * RECORD_BYTES) {
                throw IllegalStateException(
                    "$context: size ${raw.size} does not match record count $count",
                )
            }
            return (0 until count).map { index ->
                val offset = HEADER_BYTES + index * RECORD_BYTES
                StarRecord(
                    id = buffer.getInt(offset),
                    raDeg = buffer.getInt(offset + 4) / 10000.0,
                    decDeg = buffer.getInt(offset + 8) / 10000.0,
                    magnitude = buffer.getShort(offset + 12) / 100.0,
                )
            }
        }
    }
}

/** [ConstellationLines] over `catalog/constellation-lines.json`. */
class JsonConstellationLines(
    private val resourcePath: String = "/catalog/constellation-lines.json",
) : ConstellationLines {

    private data class Loaded(
        val dataVersion: String,
        val lines: Map<String, List<List<Int>>>,
    )

    private val loaded: Loaded by lazy {
        val root = CatalogResources.readDocument(resourcePath)
        val linesObject = root.required("lines", resourcePath).asObject(resourcePath)
        val lines = linesObject.mapValues { (_, value) ->
            value.asArray(resourcePath).map { polyline ->
                polyline.asArray(resourcePath).map { vertex ->
                    (vertex as? JsonValue.Num)?.value?.toCheckedInt("vertex", resourcePath)
                        ?: throw IllegalStateException("$resourcePath: line vertex is not a number")
                }
            }
        }
        Loaded(root.stringField("dataVersion", resourcePath), lines)
    }

    override fun lines(): Map<String, List<List<Int>>> = loaded.lines

    override val dataVersion: String get() = loaded.dataVersion
}
