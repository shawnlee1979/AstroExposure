package app.astroexposure.support

import app.astroexposure.data.catalog.JsonMini
import app.astroexposure.data.catalog.JsonParseException
import app.astroexposure.data.catalog.JsonValue
import app.astroexposure.domain.camera.Camera
import app.astroexposure.domain.lens.Lens
import app.astroexposure.domain.tracking.DriftMorphology
import app.astroexposure.domain.tracking.TrackingKind
import app.astroexposure.domain.tracking.TrackingProfile
import app.astroexposure.domain.units.ArcsecPerMinute
import app.astroexposure.persistence.SavedPlace
import app.astroexposure.persistence.SavedPlan
import app.astroexposure.persistence.validateCustomCamera
import app.astroexposure.persistence.validateCustomLens
import app.astroexposure.persistence.validatePlace
import app.astroexposure.persistence.FormResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * JSON backup / restore for plans and the equipment library (design §7.3).
 *
 * Own format, `schemaVersion` 1. Readers ignore unknown fields and skip
 * entries that fail validation, counting the skips — a newer backup must
 * never hard-fail an older reader (DEVELOPMENT-RULES.md G.4). Export and
 * import are pure functions; the file pickers are thin intent wrappers in
 * the UI layer. The writer is hand-rolled; the reader reuses the project's
 * own minimal JSON parser from :data (ADR-0003).
 */
data class LibraryBackup(
    val cameras: List<Camera>,
    val customCameraIds: Set<String>,
    val lenses: List<Lens>,
    val customLensIds: Set<String>,
    val trackingProfiles: List<TrackingProfile>,
    val places: List<SavedPlace>,
    val plans: List<SavedPlan>,
    /** Entries skipped because they were malformed or failed validation. */
    val skippedEntries: Int,
)

object LibraryTransfer {

    const val FORMAT = "astroexposure-backup"
    const val SCHEMA_VERSION = 1

    // --- Export -------------------------------------------------------------

    fun export(
        cameras: List<Pair<Camera, Boolean>>,
        lenses: List<Pair<Lens, Boolean>>,
        trackingProfiles: List<TrackingProfile>,
        places: List<SavedPlace>,
        plans: List<SavedPlan>,
        exportedAt: Instant,
    ): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"format\": ").append(quoted(FORMAT)).append(",\n")
        sb.append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n")
        sb.append("  \"exportedAt\": ").append(quoted(exportedAt.toString())).append(",\n")
        sb.append("  \"cameras\": [")
        sb.append(cameras.joinToString(", ") { (c, custom) -> cameraJson(c, custom) })
        sb.append("],\n")
        sb.append("  \"lenses\": [")
        sb.append(lenses.joinToString(", ") { (l, custom) -> lensJson(l, custom) })
        sb.append("],\n")
        sb.append("  \"trackingProfiles\": [")
        sb.append(trackingProfiles.joinToString(", ") { profileJson(it) })
        sb.append("],\n")
        sb.append("  \"places\": [")
        sb.append(places.joinToString(", ") { placeJson(it) })
        sb.append("],\n")
        sb.append("  \"plans\": [")
        sb.append(plans.joinToString(", ") { planJson(it) })
        sb.append("]\n}\n")
        return sb.toString()
    }

    private fun cameraJson(c: Camera, custom: Boolean): String = obj(
        "id" to quoted(c.id),
        "displayName" to quoted(c.displayName),
        "sensorWidthMm" to num(c.sensorWidthMm),
        "sensorHeightMm" to num(c.sensorHeightMm),
        "pixelsX" to c.pixelsX.toString(),
        "pixelsY" to c.pixelsY.toString(),
        "dualGainIsoMechanical" to optInt(c.dualGainIsoMechanical),
        "dualGainIsoElectronic" to optInt(c.dualGainIsoElectronic),
        "readNoiseAtGainElectrons" to optNum(c.readNoiseAtGainElectrons),
        "readNoiseLowSegmentElectrons" to optNum(c.readNoiseLowSegmentElectrons),
        "fullWellElectrons" to optNum(c.fullWellElectrons),
        "fullWellIsReferenceValue" to c.fullWellIsReferenceValue.toString(),
        "photoGainVerified" to c.photoGainVerified.toString(),
        "fallbackDeepSkyIso" to optInt(c.fallbackDeepSkyIso),
        "rawFileSizeMb" to optNum(c.rawFileSizeMb),
        "custom" to custom.toString(),
    )

    private fun lensJson(l: Lens, custom: Boolean): String = obj(
        "id" to quoted(l.id),
        "name" to quoted(l.name),
        "mount" to quoted(l.mount),
        "focalMinMm" to num(l.focalMinMm),
        "focalMaxMm" to num(l.focalMaxMm),
        "maxAperture" to num(l.maxAperture),
        "maxApertureAtMaxFocal" to optNum(l.maxApertureAtMaxFocal),
        "custom" to custom.toString(),
    )

    private fun profileJson(p: TrackingProfile): String = obj(
        "id" to quoted(p.id),
        "name" to quoted(p.name),
        "kind" to quoted(p.kind.name),
        "typicalDriftArcsecPerMin" to optNum(p.typicalDrift?.value),
        "worstDriftArcsecPerMin" to optNum(p.worstDrift?.value),
        "morphology" to (p.morphology?.let { quoted(it.name) } ?: "null"),
        "conditionsNote" to (p.conditionsNote?.let { quoted(it) } ?: "null"),
        "measuredOn" to (p.measuredOn?.let { quoted(it.toString()) } ?: "null"),
    )

    private fun placeJson(p: SavedPlace): String = obj(
        "id" to quoted(p.id),
        "name" to quoted(p.name),
        "latitudeDeg" to num(p.location.latitude.value),
        "longitudeDeg" to num(p.location.longitude.value),
        "heightMeters" to num(p.location.heightMeters),
        "zoneId" to quoted(p.location.zone.id),
        "manualBortle" to optInt(p.manualBortle),
    )

    private fun planJson(p: SavedPlan): String = obj(
        "createdAtEpochMs" to p.createdAt.toEpochMilli().toString(),
        "dateIso" to quoted(p.date.toString()),
        "category" to quoted(p.category),
        "title" to quoted(p.title),
        "bodyText" to quoted(p.bodyText),
        "payloadJson" to quoted(p.payloadJson),
    )

    // --- Import -------------------------------------------------------------

    sealed interface ImportResult {
        data class Ok(val backup: LibraryBackup) : ImportResult
        data class Failed(val reason: String) : ImportResult
    }

    fun import(text: String): ImportResult {
        val root = try {
            JsonMini.parse(text)
        } catch (e: JsonParseException) {
            return ImportResult.Failed("JSON parse error: ${e.message}")
        }
        val obj = root as? JsonValue.Obj
            ?: return ImportResult.Failed("top level is not a JSON object")
        if (obj.str("format") != FORMAT) {
            return ImportResult.Failed("not an $FORMAT document")
        }
        var skipped = 0

        val cameras = mutableListOf<Camera>()
        val customCameras = mutableSetOf<String>()
        for (item in obj.arr("cameras")) {
            val o = item as? JsonValue.Obj
            if (o == null) {
                skipped++
                continue
            }
            val parsed = parseCamera(o)
            if (parsed == null) {
                skipped++
            } else {
                cameras += parsed
                if (o.bool("custom") == true) customCameras += parsed.id
            }
        }

        val lenses = mutableListOf<Lens>()
        val customLenses = mutableSetOf<String>()
        for (item in obj.arr("lenses")) {
            val o = item as? JsonValue.Obj
            if (o == null) {
                skipped++
                continue
            }
            val parsed = parseLens(o)
            if (parsed == null) {
                skipped++
            } else {
                lenses += parsed
                if (o.bool("custom") == true) customLenses += parsed.id
            }
        }

        val profiles = mutableListOf<TrackingProfile>()
        for (item in obj.arr("trackingProfiles")) {
            val o = item as? JsonValue.Obj
            if (o == null) {
                skipped++
                continue
            }
            val parsed = parseProfile(o)
            if (parsed == null) skipped++ else profiles += parsed
        }

        val places = mutableListOf<SavedPlace>()
        for (item in obj.arr("places")) {
            val o = item as? JsonValue.Obj
            if (o == null) {
                skipped++
                continue
            }
            val parsed = parsePlace(o)
            if (parsed == null) skipped++ else places += parsed
        }

        val plans = mutableListOf<SavedPlan>()
        for (item in obj.arr("plans")) {
            val o = item as? JsonValue.Obj
            if (o == null) {
                skipped++
                continue
            }
            val parsed = parsePlan(o)
            if (parsed == null) skipped++ else plans += parsed
        }

        return ImportResult.Ok(
            LibraryBackup(
                cameras = cameras,
                customCameraIds = customCameras,
                lenses = lenses,
                customLensIds = customLenses,
                trackingProfiles = profiles,
                places = places,
                plans = plans,
                skippedEntries = skipped,
            ),
        )
    }

    private fun parseCamera(o: JsonValue.Obj): Camera? {
        val id = o.str("id") ?: return null
        val name = o.str("displayName") ?: return null
        val w = o.num("sensorWidthMm") ?: return null
        val h = o.num("sensorHeightMm") ?: return null
        val px = o.num("pixelsX")?.toInt() ?: return null
        val py = o.num("pixelsY")?.toInt() ?: return null
        val base = when (
            val r = validateCustomCamera(id, name, w, h, px, py)
        ) {
            is FormResult.Ok -> r.value
            is FormResult.Invalid -> return null
        }
        return base.copy(
            dualGainIsoMechanical = o.num("dualGainIsoMechanical")?.toInt(),
            dualGainIsoElectronic = o.num("dualGainIsoElectronic")?.toInt(),
            readNoiseAtGainElectrons = o.num("readNoiseAtGainElectrons"),
            readNoiseLowSegmentElectrons = o.num("readNoiseLowSegmentElectrons"),
            fullWellElectrons = o.num("fullWellElectrons"),
            fullWellIsReferenceValue = o.bool("fullWellIsReferenceValue") ?: false,
            photoGainVerified = o.bool("photoGainVerified") ?: true,
            fallbackDeepSkyIso = o.num("fallbackDeepSkyIso")?.toInt(),
            rawFileSizeMb = o.num("rawFileSizeMb"),
        )
    }

    private fun parseLens(o: JsonValue.Obj): Lens? {
        val id = o.str("id") ?: return null
        val name = o.str("name") ?: return null
        val min = o.num("focalMinMm") ?: return null
        val max = o.num("focalMaxMm") ?: return null
        val aperture = o.num("maxAperture") ?: return null
        return when (
            val r = validateCustomLens(
                id, name, o.str("mount") ?: "", min, max, aperture,
                o.num("maxApertureAtMaxFocal"),
            )
        ) {
            is FormResult.Ok -> r.value
            is FormResult.Invalid -> null
        }
    }

    private fun parseProfile(o: JsonValue.Obj): TrackingProfile? {
        val id = o.str("id") ?: return null
        val name = o.str("name") ?: return null
        val kind = o.str("kind")?.let {
            runCatching { TrackingKind.valueOf(it) }.getOrNull()
        } ?: return null
        return TrackingProfile(
            id = id,
            name = name,
            kind = kind,
            typicalDrift = o.num("typicalDriftArcsecPerMin")?.let { ArcsecPerMinute(it) },
            worstDrift = o.num("worstDriftArcsecPerMin")?.let { ArcsecPerMinute(it) },
            morphology = o.str("morphology")?.let {
                runCatching { DriftMorphology.valueOf(it) }.getOrNull()
            },
            conditionsNote = o.str("conditionsNote"),
            measuredOn = o.str("measuredOn")?.let {
                runCatching { LocalDate.parse(it) }.getOrNull()
            },
        )
    }

    private fun parsePlace(o: JsonValue.Obj): SavedPlace? {
        val id = o.str("id") ?: return null
        val name = o.str("name") ?: return null
        val lat = o.num("latitudeDeg") ?: return null
        val lon = o.num("longitudeDeg") ?: return null
        val zone = o.str("zoneId")?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: return null
        return when (
            val r = validatePlace(
                id, name, lat, lon, o.num("heightMeters") ?: 0.0, zone,
                o.num("manualBortle")?.toInt(),
            )
        ) {
            is FormResult.Ok -> r.value
            is FormResult.Invalid -> null
        }
    }

    private fun parsePlan(o: JsonValue.Obj): SavedPlan? {
        val created = o.num("createdAtEpochMs")?.toLong() ?: return null
        val date = o.str("dateIso")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return null
        return SavedPlan(
            id = 0, // the database assigns a fresh id on insert
            createdAt = Instant.ofEpochMilli(created),
            date = date,
            category = o.str("category") ?: return null,
            title = o.str("title") ?: return null,
            bodyText = o.str("bodyText") ?: "",
            payloadJson = o.str("payloadJson") ?: "{}",
        )
    }

    // --- JSON primitives ------------------------------------------------------

    private fun obj(vararg fields: Pair<String, String>): String =
        fields.joinToString(", ", "{", "}") { (k, v) -> "${quoted(k)}: $v" }

    private fun num(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun optNum(v: Double?): String = v?.let { num(it) } ?: "null"

    private fun optInt(v: Int?): String = v?.toString() ?: "null"

    private fun quoted(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    private fun JsonValue.Obj.str(key: String): String? =
        (entries[key] as? JsonValue.Str)?.value

    private fun JsonValue.Obj.num(key: String): Double? =
        (entries[key] as? JsonValue.Num)?.value

    private fun JsonValue.Obj.bool(key: String): Boolean? =
        (entries[key] as? JsonValue.Bool)?.value

    private fun JsonValue.Obj.arr(key: String): List<JsonValue> =
        (entries[key] as? JsonValue.Arr)?.items ?: emptyList()
}

/** Plain-text export of plans (design §7.3): for humans, not re-import. */
object PlanTextExport {
    fun planToText(plan: SavedPlan): String = buildString {
        appendLine("${plan.date} · ${plan.title}")
        appendLine(plan.bodyText)
    }

    fun plansToText(plans: List<SavedPlan>): String = buildString {
        appendLine("AstroExposure plans (${plans.size})")
        appendLine()
        for (plan in plans) {
            appendLine(planToText(plan))
            appendLine("---")
        }
    }
}
