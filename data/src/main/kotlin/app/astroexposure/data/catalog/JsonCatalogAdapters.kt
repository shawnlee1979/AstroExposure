package app.astroexposure.data.catalog

import app.astroexposure.domain.catalog.BrightStar
import app.astroexposure.domain.catalog.ConstellationCatalog
import app.astroexposure.domain.catalog.ConstellationEntry
import app.astroexposure.domain.catalog.DeepSkyObject
import app.astroexposure.domain.catalog.DeepSkyObjectType
import app.astroexposure.domain.catalog.EclipseCatalog
import app.astroexposure.domain.catalog.EclipseRecord
import app.astroexposure.domain.catalog.EclipseType
import app.astroexposure.domain.catalog.LensCatalog
import app.astroexposure.domain.catalog.MeteorShower
import app.astroexposure.domain.catalog.MeteorShowerCatalog
import app.astroexposure.domain.catalog.ObjectCatalog
import app.astroexposure.domain.catalog.WideFieldCatalog
import app.astroexposure.domain.catalog.WideFieldViewpoint
import app.astroexposure.domain.ephemeris.EclipseKind
import app.astroexposure.domain.lens.Lens
import java.time.Instant
import java.time.LocalDate

/**
 * Catalogue adapters reading the bundled JSON data products from the
 * classpath (the JSON files under `catalog/`, produced by the build-time data pipelines).
 * Document schema: a top-level object with `schemaVersion` (currently 1),
 * `dataVersion` (string, surfaced through the port) and one array field
 * with the records. A missing resource, an unsupported schema version or
 * a malformed document throws [IllegalStateException] — adapters never
 * fall back to an empty catalogue (DEVELOPMENT-RULES.md E.5).
 */

internal const val SUPPORTED_SCHEMA_VERSION = 1

internal object CatalogResources {
    fun readBytes(resourcePath: String): ByteArray =
        CatalogResources::class.java.getResourceAsStream(resourcePath)?.readBytes()
            ?: throw IllegalStateException(
                "catalogue resource missing on the classpath: $resourcePath " +
                    "(the data pipeline product has not been bundled)",
            )

    fun readDocument(resourcePath: String): Map<String, JsonValue> {
        val text = readBytes(resourcePath).toString(Charsets.UTF_8)
        val root = JsonMini.parse(text).asObject(resourcePath)
        requireSupportedSchema(root.intField("schemaVersion", resourcePath), resourcePath)
        return root
    }
}

/** [ObjectCatalog] over `catalog/objects.json` (OpenNGC-derived subset, design section 6.1). */
class JsonObjectCatalog(
    private val resourcePath: String = "/catalog/objects.json",
) : ObjectCatalog {

    private data class Loaded(val dataVersion: String, val objects: List<DeepSkyObject>)

    private val loaded: Loaded by lazy {
        val root = CatalogResources.readDocument(resourcePath)
        val context = resourcePath
        val objects = root.required("objects", context).asArray(context).map { item ->
            val o = item.asObject(context)
            DeepSkyObject(
                id = o.stringField("id", context),
                name = o.stringField("name", context),
                nameZh = o.optionalString("nameZh", context),
                raHours = o.doubleField("raHours", context),
                decDeg = o.doubleField("decDeg", context),
                magnitude = o.optionalDouble("magnitude", context),
                sizeMajorArcmin = o.optionalDouble("sizeMajorArcmin", context),
                sizeMinorArcmin = o.optionalDouble("sizeMinorArcmin", context),
                positionAngleDeg = o.optionalDouble("positionAngleDeg", context),
                type = parseObjectType(o.stringField("type", context), context),
                constellation = o.optionalString("constellation", context),
                surfaceBrightness = o.optionalDouble("surfaceBrightness", context),
                surfaceBrightnessEstimated = o.optionalBoolean("surfaceBrightnessEstimated", context) ?: false,
            )
        }
        Loaded(root.stringField("dataVersion", context), objects)
    }

    override fun allObjects(): List<DeepSkyObject> = loaded.objects

    override val dataVersion: String get() = loaded.dataVersion

    private fun parseObjectType(raw: String, context: String): DeepSkyObjectType = when (raw) {
        "GALAXY" -> DeepSkyObjectType.GALAXY
        "NEBULA" -> DeepSkyObjectType.NEBULA
        "CLUSTER_OPEN" -> DeepSkyObjectType.OPEN_CLUSTER
        "CLUSTER_GLOBULAR" -> DeepSkyObjectType.GLOBULAR_CLUSTER
        "PLANETARY_NEBULA" -> DeepSkyObjectType.PLANETARY_NEBULA
        "SUPERNOVA_REMNANT" -> DeepSkyObjectType.SUPERNOVA_REMNANT
        "DOUBLE_STAR" -> DeepSkyObjectType.DOUBLE_STAR
        "ASTERISM" -> DeepSkyObjectType.ASTERISM
        "OTHER" -> DeepSkyObjectType.OTHER
        else -> throw IllegalStateException("$context: unknown object type '$raw'")
    }
}

/** [EclipseCatalog] over `catalog/eclipses.json` (NASA GSFC-derived table, design section 6.1). */
class JsonEclipseCatalog(
    private val resourcePath: String = "/catalog/eclipses.json",
) : EclipseCatalog {

    private data class Loaded(val dataVersion: String, val records: List<EclipseRecord>)

    private val loaded: Loaded by lazy {
        val root = CatalogResources.readDocument(resourcePath)
        val context = resourcePath
        val records = root.required("eclipses", context).asArray(context).map { item ->
            val o = item.asObject(context)
            EclipseRecord(
                date = LocalDate.parse(o.stringField("date", context)),
                kind = parseKind(o.stringField("kind", context), context),
                type = parseType(o.stringField("type", context), context),
                peakUtc = o.optionalString("peakUtc", context)?.let { Instant.parse(it) },
                magnitude = o.optionalDouble("magnitude", context),
                saros = o.optionalInt("saros", context),
                pathWidthKm = o.optionalInt("pathWidthKm", context),
                centralDurationSec = o.optionalInt("centralDurationSec", context),
                visibility = o.optionalString("visibility", context),
            )
        }
        Loaded(root.stringField("dataVersion", context), records.sortedBy { it.date })
    }

    override fun eclipses(fromYear: Int, toYear: Int): List<EclipseRecord> =
        loaded.records.filter { it.date.year in fromYear..toYear }

    override val dataVersion: String get() = loaded.dataVersion

    private fun parseKind(raw: String, context: String): EclipseKind = when (raw) {
        "SOLAR" -> EclipseKind.SOLAR
        "LUNAR" -> EclipseKind.LUNAR
        else -> throw IllegalStateException("$context: unknown eclipse kind '$raw'")
    }

    private fun parseType(raw: String, context: String): EclipseType = when (raw) {
        "TOTAL" -> EclipseType.TOTAL
        "ANNULAR" -> EclipseType.ANNULAR
        "PARTIAL" -> EclipseType.PARTIAL
        "HYBRID" -> EclipseType.HYBRID
        "PENUMBRAL" -> EclipseType.PENUMBRAL
        else -> throw IllegalStateException("$context: unknown eclipse type '$raw'")
    }
}

/** [MeteorShowerCatalog] over `catalog/showers.json` (IMO / IAU MDC footing, design section 5.5). */
class JsonMeteorShowerCatalog(
    private val resourcePath: String = "/catalog/showers.json",
) : MeteorShowerCatalog {

    private data class Loaded(val dataVersion: String, val showers: List<MeteorShower>)

    private val loaded: Loaded by lazy {
        val root = CatalogResources.readDocument(resourcePath)
        val context = resourcePath
        val showers = root.required("showers", context).asArray(context).map { item ->
            val o = item.asObject(context)
            MeteorShower(
                iauCode = o.stringField("iauCode", context),
                nameEn = o.stringField("nameEn", context),
                nameZh = o.stringField("nameZh", context),
                activityStartLon = o.doubleField("activityStartLon", context),
                activityEndLon = o.doubleField("activityEndLon", context),
                peakLon = o.doubleField("peakLon", context),
                peakZhr = o.intField("peakZhr", context),
                radiantRaHours = o.doubleField("radiantRaHours", context),
                radiantDecDeg = o.doubleField("radiantDecDeg", context),
                driftRaPerDay = o.doubleField("driftRaPerDay", context),
                driftDecPerDay = o.doubleField("driftDecPerDay", context),
                parent = o.optionalString("parent", context),
                velocityKmS = o.doubleField("velocityKmS", context),
                populationIndex = o.doubleField("populationIndex", context),
            )
        }
        Loaded(root.stringField("dataVersion", context), showers)
    }

    override fun showers(): List<MeteorShower> = loaded.showers

    override val dataVersion: String get() = loaded.dataVersion
}

/** [ConstellationCatalog] over `catalog/constellations.json` (editorial 88-entry table, design section 6.1). */
class JsonConstellationCatalog(
    private val resourcePath: String = "/catalog/constellations.json",
) : ConstellationCatalog {

    private data class Loaded(val dataVersion: String, val entries: List<ConstellationEntry>)

    private val loaded: Loaded by lazy {
        val root = CatalogResources.readDocument(resourcePath)
        val context = resourcePath
        val entries = root.required("constellations", context).asArray(context).map { item ->
            val o = item.asObject(context)
            ConstellationEntry(
                code = o.stringField("code", context),
                centerRaHours = o.doubleField("centerRaHours", context),
                centerDecDeg = o.doubleField("centerDecDeg", context),
                sizeMajorDeg = o.intField("sizeMajorDeg", context),
                sizeMinorDeg = o.intField("sizeMinorDeg", context),
                brightStars = o.required("brightStars", context).asArray(context).map { starItem ->
                    val s = starItem.asObject(context)
                    BrightStar(
                        nameZh = s.stringField("nameZh", context),
                        nameEn = s.stringField("nameEn", context),
                        magnitude = s.doubleField("magnitude", context),
                    )
                },
                noteZh = o.stringField("noteZh", context),
                noteEn = o.stringField("noteEn", context),
            )
        }
        Loaded(root.stringField("dataVersion", context), entries)
    }

    override fun constellations(): List<ConstellationEntry> = loaded.entries

    override val dataVersion: String get() = loaded.dataVersion
}

/** [WideFieldCatalog] over `catalog/widefield.json` (editorial viewpoint list, design section 6.1). */
class JsonWideFieldCatalog(
    private val resourcePath: String = "/catalog/widefield.json",
) : WideFieldCatalog {

    private data class Loaded(val dataVersion: String, val viewpoints: List<WideFieldViewpoint>)

    private val loaded: Loaded by lazy {
        val root = CatalogResources.readDocument(resourcePath)
        val context = resourcePath
        val viewpoints = root.required("viewpoints", context).asArray(context).map { item ->
            val o = item.asObject(context)
            WideFieldViewpoint(
                id = o.stringField("id", context),
                nameZh = o.stringField("nameZh", context),
                nameEn = o.stringField("nameEn", context),
                anchorRaHours = o.doubleField("anchorRaHours", context),
                anchorDecDeg = o.doubleField("anchorDecDeg", context),
                sizeMajorDeg = o.intField("sizeMajorDeg", context),
                sizeMinorDeg = o.intField("sizeMinorDeg", context),
                noteZh = o.stringField("noteZh", context),
                noteEn = o.stringField("noteEn", context),
            )
        }
        Loaded(root.stringField("dataVersion", context), viewpoints)
    }

    override fun viewpoints(): List<WideFieldViewpoint> = loaded.viewpoints

    override val dataVersion: String get() = loaded.dataVersion
}

/** [LensCatalog] over `catalog/lenses.json` (built-in lens lists, design section 2.4). */
class JsonLensCatalog(
    private val resourcePath: String = "/catalog/lenses.json",
) : LensCatalog {

    private data class Loaded(val dataVersion: String, val lenses: List<Lens>)

    private val loaded: Loaded by lazy {
        val root = CatalogResources.readDocument(resourcePath)
        val context = resourcePath
        val lenses = root.required("lenses", context).asArray(context).map { item ->
            val o = item.asObject(context)
            Lens(
                id = o.stringField("id", context),
                name = o.stringField("name", context),
                mount = o.stringField("mount", context),
                focalMinMm = o.doubleField("focalMinMm", context),
                focalMaxMm = o.doubleField("focalMaxMm", context),
                maxAperture = o.doubleField("maxAperture", context),
                // Optional in the document: absent/null for constant-aperture lenses.
                maxApertureAtMaxFocal = o.optionalDouble("maxApertureTele", context),
            )
        }
        Loaded(root.stringField("dataVersion", context), lenses)
    }

    override fun builtInLenses(): List<Lens> = loaded.lenses

    override val dataVersion: String get() = loaded.dataVersion
}
