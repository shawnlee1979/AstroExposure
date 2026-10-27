package app.astroexposure.persistence

import app.astroexposure.domain.camera.Camera
import app.astroexposure.domain.catalog.LensCatalog
import app.astroexposure.domain.lens.Lens
import app.astroexposure.domain.tracking.TrackingProfile

/**
 * The single door between the UI and Room. All functions are suspending;
 * callers run them from a lifecycle scope. First-run seeding happens here:
 * cameras from the domain built-ins, lenses from the lens catalogue,
 * tracking profiles from the domain built-ins — with drift values left
 * empty (design §2.5: never seed numbers that were not measured).
 */
class AppRepository(
    private val db: AstroDatabase,
    private val lensCatalog: LensCatalog,
) {

    suspend fun seedIfEmpty() {
        if (db.cameraDao().count() == 0) {
            SeedData.cameraEntities().forEach { db.cameraDao().upsert(it) }
        }
        if (db.lensDao().count() == 0) {
            db.lensDao().upsertAll(SeedData.lensEntities(lensCatalog.builtInLenses()))
        }
        if (db.trackingProfileDao().count() == 0) {
            SeedData.trackingProfileEntities().forEach { db.trackingProfileDao().upsert(it) }
        }
    }

    // Cameras: domain values paired with their custom flag.
    suspend fun cameras(): List<Pair<Camera, Boolean>> =
        db.cameraDao().all().map { it.toDomain() to it.custom }

    suspend fun upsertCamera(camera: Camera, custom: Boolean) =
        db.cameraDao().upsert(camera.toEntity(custom))

    suspend fun deleteCamera(camera: Camera, custom: Boolean) =
        db.cameraDao().delete(camera.toEntity(custom))

    suspend fun lenses(): List<Pair<Lens, Boolean>> =
        db.lensDao().all().map { it.toDomain() to it.custom }

    suspend fun upsertLens(lens: Lens, custom: Boolean) =
        db.lensDao().upsert(lens.toEntity(custom))

    suspend fun deleteLens(lens: Lens, custom: Boolean) =
        db.lensDao().delete(lens.toEntity(custom))

    suspend fun trackingProfiles(): List<TrackingProfile> =
        db.trackingProfileDao().all().map { it.toDomain() }

    suspend fun upsertProfile(profile: TrackingProfile) =
        db.trackingProfileDao().upsert(profile.toEntity())

    suspend fun places(): List<SavedPlace> = db.placeDao().all().map { it.toDomain() }

    suspend fun upsertPlace(place: SavedPlace) = db.placeDao().upsert(place.toEntity())

    suspend fun deletePlace(place: SavedPlace) = db.placeDao().delete(place.toEntity())

    suspend fun plans(): List<SavedPlan> = db.planDao().all().map { it.toDomain() }

    suspend fun addPlan(plan: SavedPlan): Long = db.planDao().insert(plan.toEntity())

    suspend fun deletePlan(plan: SavedPlan) = db.planDao().delete(plan.toEntity())

    suspend fun settings(): Map<String, String> =
        db.settingDao().all().associate { it.key to it.value }

    suspend fun putSetting(key: String, value: String) =
        db.settingDao().upsert(SettingEntity(key, value))

    companion object {
        const val KEY_DEFAULT_STRICTNESS = "defaultStrictness"
        const val KEY_RED_LIGHT_AUTO = "redLightAuto"
        const val KEY_LAST_CAMERA_ID = "lastCameraId"
        const val KEY_LAST_LENS_ID = "lastLensId"
        const val KEY_LAST_PROFILE_ID = "lastProfileId"
    }
}
