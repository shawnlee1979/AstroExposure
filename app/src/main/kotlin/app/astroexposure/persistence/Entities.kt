package app.astroexposure.persistence

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * Room persistence for the application layer (design §8.4): equipment
 * library, tracking profiles, favourite places, saved plans and settings.
 *
 * Schema version 1. The tracking-profile table intentionally stores drift
 * columns as nullable and is seeded WITHOUT any drift values: profiles stay
 * unmeasured until a baseline measurement is entered (design §2.5/§2.8).
 */

@Entity(tableName = "cameras")
data class CameraEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val sensorWidthMm: Double,
    val sensorHeightMm: Double,
    val pixelsX: Int,
    val pixelsY: Int,
    val dualGainIsoMechanical: Int?,
    val dualGainIsoElectronic: Int?,
    val readNoiseAtGainElectrons: Double?,
    val readNoiseLowSegmentElectrons: Double?,
    val fullWellElectrons: Double?,
    val fullWellIsReferenceValue: Boolean,
    val photoGainVerified: Boolean,
    val fallbackDeepSkyIso: Int?,
    val rawFileSizeMb: Double?,
    /** True for user-created bodies; built-ins cannot be deleted from the UI. */
    val custom: Boolean,
)

@Entity(tableName = "lenses")
data class LensEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mount: String,
    val focalMinMm: Double,
    val focalMaxMm: Double,
    val maxAperture: Double,
    val maxApertureAtMaxFocal: Double?,
    /** True for user-created lenses; built-ins come from the lens catalogue. */
    val custom: Boolean,
)

@Entity(tableName = "tracking_profiles")
data class TrackingProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** [app.astroexposure.domain.tracking.TrackingKind] name. */
    val kind: String,
    /** Arcsec/minute; null until a baseline measurement is entered. Never seeded. */
    val typicalDriftArcsecPerMin: Double?,
    val worstDriftArcsecPerMin: Double?,
    /** [app.astroexposure.domain.tracking.DriftMorphology] name, or null. */
    val morphology: String?,
    val conditionsNote: String?,
    /** Epoch day of the measurement, or null. */
    val measuredOnEpochDay: Long?,
)

@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val heightMeters: Double,
    val zoneId: String,
    /** Optional manual Bortle override (design §2.1/§2.6); null = atlas only. */
    val manualBortle: Int?,
)

@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAtEpochMs: Long,
    /** Civil date the plan is for, ISO-8601. */
    val dateIso: String,
    /** Calculator category key: LANDSCAPE / WIDE_FIELD / DEEP_SKY / ECLIPSE / METEOR. */
    val category: String,
    val title: String,
    /** Human-readable card text (the plain-text export body). */
    val bodyText: String,
    /** Machine-readable input snapshot, JSON (own format, readers ignore unknown fields). */
    val payloadJson: String,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface CameraDao {
    @Query("SELECT * FROM cameras ORDER BY displayName")
    suspend fun all(): List<CameraEntity>

    @Query("SELECT COUNT(*) FROM cameras")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CameraEntity)

    @Delete
    suspend fun delete(entity: CameraEntity)
}

@Dao
interface LensDao {
    @Query("SELECT * FROM lenses ORDER BY name")
    suspend fun all(): List<LensEntity>

    @Query("SELECT COUNT(*) FROM lenses")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LensEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<LensEntity>)

    @Delete
    suspend fun delete(entity: LensEntity)
}

@Dao
interface TrackingProfileDao {
    @Query("SELECT * FROM tracking_profiles ORDER BY name")
    suspend fun all(): List<TrackingProfileEntity>

    @Query("SELECT COUNT(*) FROM tracking_profiles")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrackingProfileEntity)
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places ORDER BY name")
    suspend fun all(): List<PlaceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaceEntity)

    @Delete
    suspend fun delete(entity: PlaceEntity)
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM plans ORDER BY dateIso DESC, createdAtEpochMs DESC")
    suspend fun all(): List<PlanEntity>

    @Insert
    suspend fun insert(entity: PlanEntity): Long

    @Delete
    suspend fun delete(entity: PlanEntity)
}

@Dao
interface SettingDao {
    @Query("SELECT * FROM settings")
    suspend fun all(): List<SettingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SettingEntity)
}

@Database(
    entities = [
        CameraEntity::class,
        LensEntity::class,
        TrackingProfileEntity::class,
        PlaceEntity::class,
        PlanEntity::class,
        SettingEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AstroDatabase : RoomDatabase() {
    abstract fun cameraDao(): CameraDao
    abstract fun lensDao(): LensDao
    abstract fun trackingProfileDao(): TrackingProfileDao
    abstract fun placeDao(): PlaceDao
    abstract fun planDao(): PlanDao
    abstract fun settingDao(): SettingDao
}
