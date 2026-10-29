package app.astroexposure.persistence

import app.astroexposure.domain.camera.Camera
import app.astroexposure.domain.camera.BuiltInCameras
import app.astroexposure.domain.geo.Coordinates
import app.astroexposure.domain.geo.GeoLocation
import app.astroexposure.domain.lens.Lens
import app.astroexposure.domain.tracking.BuiltInTrackingProfiles
import app.astroexposure.domain.tracking.DriftMorphology
import app.astroexposure.domain.tracking.TrackingKind
import app.astroexposure.domain.tracking.TrackingProfile
import app.astroexposure.domain.units.ArcsecPerMinute
import app.astroexposure.domain.units.Degrees
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure conversions between Room rows and domain values, plus the form
 * validation used by the equipment-library editors. Everything here is a
 * plain function of its inputs so it is covered by JVM unit tests; Room
 * itself is only compile-verified (no instrumented tests, per phase scope).
 */

/** A favourite observing site as the app layer carries it. */
data class SavedPlace(
    val id: String,
    val name: String,
    val location: GeoLocation,
    /** Manual Bortle override from the place record, or null. */
    val manualBortle: Int?,
    /**
     * True only when the record was saved from the location card off a
     * GPS fix that carried an altitude: the record's
     * [GeoLocation.heightMeters] then has a known GPS source and the
     * card may present it when the place is selected (ui-design §7.2).
     * Records typed in on the Settings tab and records from before this
     * flag existed stay false — their height's source is not knowable
     * and is never presented. Never inferred from the height value.
     */
    val altitudeFromGps: Boolean = false,
)

/** A saved plan (design §7.3). */
data class SavedPlan(
    val id: Long,
    val createdAt: Instant,
    val date: LocalDate,
    val category: String,
    val title: String,
    val bodyText: String,
    val payloadJson: String,
)

/** Why a library-editor form was rejected; the UI maps these to resources. */
enum class FormError {
    BLANK_NAME,
    NON_POSITIVE_DIMENSION,
    NON_POSITIVE_RESOLUTION,
    FOCAL_RANGE_INVERTED,
    APERTURE_OUT_OF_RANGE,
    LATITUDE_OUT_OF_RANGE,
    LONGITUDE_OUT_OF_RANGE,
    BORTLE_OUT_OF_RANGE,
    DRIFT_NEGATIVE,
    NON_FINITE_VALUE,
}

sealed interface FormResult<out T> {
    data class Ok<T>(val value: T) : FormResult<T>
    data class Invalid(val error: FormError) : FormResult<Nothing>
}

// --- Camera ---------------------------------------------------------------

fun Camera.toEntity(custom: Boolean): CameraEntity = CameraEntity(
    id = id,
    displayName = displayName,
    sensorWidthMm = sensorWidthMm,
    sensorHeightMm = sensorHeightMm,
    pixelsX = pixelsX,
    pixelsY = pixelsY,
    dualGainIsoMechanical = dualGainIsoMechanical,
    dualGainIsoElectronic = dualGainIsoElectronic,
    readNoiseAtGainElectrons = readNoiseAtGainElectrons,
    readNoiseLowSegmentElectrons = readNoiseLowSegmentElectrons,
    fullWellElectrons = fullWellElectrons,
    fullWellIsReferenceValue = fullWellIsReferenceValue,
    photoGainVerified = photoGainVerified,
    fallbackDeepSkyIso = fallbackDeepSkyIso,
    rawFileSizeMb = rawFileSizeMb,
    custom = custom,
)

fun CameraEntity.toDomain(): Camera = Camera(
    id = id,
    displayName = displayName,
    sensorWidthMm = sensorWidthMm,
    sensorHeightMm = sensorHeightMm,
    pixelsX = pixelsX,
    pixelsY = pixelsY,
    dualGainIsoMechanical = dualGainIsoMechanical,
    dualGainIsoElectronic = dualGainIsoElectronic,
    readNoiseAtGainElectrons = readNoiseAtGainElectrons,
    readNoiseLowSegmentElectrons = readNoiseLowSegmentElectrons,
    fullWellElectrons = fullWellElectrons,
    fullWellIsReferenceValue = fullWellIsReferenceValue,
    photoGainVerified = photoGainVerified,
    fallbackDeepSkyIso = fallbackDeepSkyIso,
    rawFileSizeMb = rawFileSizeMb,
)

/**
 * Validate the minimal custom-camera form (design §2.3: sensor size and
 * resolution suffice; the deep-sky fields stay unrecorded and the models
 * degrade explicitly).
 */
fun validateCustomCamera(
    id: String,
    name: String,
    sensorWidthMm: Double,
    sensorHeightMm: Double,
    pixelsX: Int,
    pixelsY: Int,
): FormResult<Camera> {
    if (name.isBlank()) return FormResult.Invalid(FormError.BLANK_NAME)
    // NaN fails every comparison below, so finiteness is checked first:
    // a non-finite dimension must never reach the database (it would also
    // poison the JSON backup export).
    if (!sensorWidthMm.isFinite() || !sensorHeightMm.isFinite()) {
        return FormResult.Invalid(FormError.NON_FINITE_VALUE)
    }
    if (sensorWidthMm <= 0.0 || sensorHeightMm <= 0.0) {
        return FormResult.Invalid(FormError.NON_POSITIVE_DIMENSION)
    }
    if (pixelsX <= 0 || pixelsY <= 0) return FormResult.Invalid(FormError.NON_POSITIVE_RESOLUTION)
    return FormResult.Ok(
        Camera(
            id = id,
            displayName = name.trim(),
            sensorWidthMm = sensorWidthMm,
            sensorHeightMm = sensorHeightMm,
            pixelsX = pixelsX,
            pixelsY = pixelsY,
        ),
    )
}

// --- Lens -----------------------------------------------------------------

fun Lens.toEntity(custom: Boolean): LensEntity = LensEntity(
    id = id,
    name = name,
    mount = mount,
    focalMinMm = focalMinMm,
    focalMaxMm = focalMaxMm,
    maxAperture = maxAperture,
    maxApertureAtMaxFocal = maxApertureAtMaxFocal,
    custom = custom,
)

fun LensEntity.toDomain(): Lens = Lens(
    id = id,
    name = name,
    mount = mount,
    focalMinMm = focalMinMm,
    focalMaxMm = focalMaxMm,
    maxAperture = maxAperture,
    maxApertureAtMaxFocal = maxApertureAtMaxFocal,
)

fun validateCustomLens(
    id: String,
    name: String,
    mount: String,
    focalMinMm: Double,
    focalMaxMm: Double,
    maxAperture: Double,
    maxApertureAtMaxFocal: Double?,
): FormResult<Lens> {
    if (name.isBlank()) return FormResult.Invalid(FormError.BLANK_NAME)
    if (!focalMinMm.isFinite() || !focalMaxMm.isFinite() || !maxAperture.isFinite() ||
        (maxApertureAtMaxFocal != null && !maxApertureAtMaxFocal.isFinite())
    ) {
        return FormResult.Invalid(FormError.NON_FINITE_VALUE)
    }
    if (focalMinMm <= 0.0 || focalMaxMm <= 0.0) {
        return FormResult.Invalid(FormError.NON_POSITIVE_DIMENSION)
    }
    if (focalMaxMm < focalMinMm) return FormResult.Invalid(FormError.FOCAL_RANGE_INVERTED)
    // Physical f-numbers for photographic lenses live between f/0.95 and f/32;
    // the range is a form sanity bound, not a catalogue claim.
    if (maxAperture < 0.9 || maxAperture > 32.0) {
        return FormResult.Invalid(FormError.APERTURE_OUT_OF_RANGE)
    }
    if (maxApertureAtMaxFocal != null && maxApertureAtMaxFocal < maxAperture) {
        return FormResult.Invalid(FormError.APERTURE_OUT_OF_RANGE)
    }
    return FormResult.Ok(
        Lens(
            id = id,
            name = name.trim(),
            mount = mount.ifBlank { "—" },
            focalMinMm = focalMinMm,
            focalMaxMm = focalMaxMm,
            maxAperture = maxAperture,
            maxApertureAtMaxFocal = maxApertureAtMaxFocal,
        ),
    )
}

// --- Tracking profiles ------------------------------------------------------

fun TrackingProfile.toEntity(): TrackingProfileEntity = TrackingProfileEntity(
    id = id,
    name = name,
    kind = kind.name,
    typicalDriftArcsecPerMin = typicalDrift?.value,
    worstDriftArcsecPerMin = worstDrift?.value,
    morphology = morphology?.name,
    conditionsNote = conditionsNote,
    measuredOnEpochDay = measuredOn?.toEpochDay(),
)

fun TrackingProfileEntity.toDomain(): TrackingProfile = TrackingProfile(
    id = id,
    name = name,
    kind = TrackingKind.valueOf(kind),
    typicalDrift = typicalDriftArcsecPerMin?.let { ArcsecPerMinute(it) },
    worstDrift = worstDriftArcsecPerMin?.let { ArcsecPerMinute(it) },
    morphology = morphology?.let { DriftMorphology.valueOf(it) },
    conditionsNote = conditionsNote,
    measuredOn = measuredOnEpochDay?.let { LocalDate.ofEpochDay(it) },
)

/**
 * Validate a baseline-measurement entry for a profile (design §2.5).
 * A present value must be finite and strictly positive: a negative drift
 * would produce negative shutter times downstream, NaN would poison the
 * store (and break JSON export), and an exact zero is not a physical
 * measurement — it would divide by zero in the drift model. Every entry
 * point (settings form, save wiring, library import) must run this check;
 * it previously existed with tests but was wired to nothing.
 */
fun validateDriftEntry(typicalArcsecPerMin: Double?, worstArcsecPerMin: Double?): FormError? {
    if ((typicalArcsecPerMin != null && !typicalArcsecPerMin.isFinite()) ||
        (worstArcsecPerMin != null && !worstArcsecPerMin.isFinite())
    ) {
        return FormError.NON_FINITE_VALUE
    }
    if ((typicalArcsecPerMin != null && typicalArcsecPerMin <= 0.0) ||
        (worstArcsecPerMin != null && worstArcsecPerMin <= 0.0)
    ) {
        return FormError.DRIFT_NEGATIVE
    }
    return null
}

// --- Places -----------------------------------------------------------------

fun SavedPlace.toEntity(): PlaceEntity = PlaceEntity(
    id = id,
    name = name,
    latitudeDeg = location.latitude.value,
    longitudeDeg = location.longitude.value,
    heightMeters = location.heightMeters,
    zoneId = location.zone.id,
    manualBortle = manualBortle,
    altitudeFromGps = altitudeFromGps,
)

fun PlaceEntity.toDomain(): SavedPlace = SavedPlace(
    id = id,
    name = name,
    location = GeoLocation(
        latitude = Degrees(latitudeDeg),
        longitude = Degrees(longitudeDeg),
        heightMeters = heightMeters,
        zone = ZoneId.of(zoneId),
        label = name,
    ),
    manualBortle = manualBortle,
    altitudeFromGps = altitudeFromGps,
)

fun validatePlace(
    id: String,
    name: String,
    latitudeDeg: Double,
    longitudeDeg: Double,
    heightMeters: Double,
    zone: ZoneId,
    manualBortle: Int?,
): FormResult<SavedPlace> {
    if (name.isBlank()) return FormResult.Invalid(FormError.BLANK_NAME)
    if (!Coordinates.isValidLatitude(latitudeDeg)) {
        return FormResult.Invalid(FormError.LATITUDE_OUT_OF_RANGE)
    }
    if (!Coordinates.isValidLongitude(longitudeDeg)) {
        return FormResult.Invalid(FormError.LONGITUDE_OUT_OF_RANGE)
    }
    if (manualBortle != null && manualBortle !in 1..9) {
        return FormResult.Invalid(FormError.BORTLE_OUT_OF_RANGE)
    }
    return FormResult.Ok(
        SavedPlace(
            id = id,
            name = name.trim(),
            location = GeoLocation(
                latitude = Degrees(latitudeDeg),
                longitude = Degrees(longitudeDeg),
                heightMeters = heightMeters,
                zone = zone,
                label = name.trim(),
            ),
            manualBortle = manualBortle,
        ),
    )
}

// --- Plans ------------------------------------------------------------------

fun SavedPlan.toEntity(): PlanEntity = PlanEntity(
    id = id,
    createdAtEpochMs = createdAt.toEpochMilli(),
    dateIso = date.toString(),
    category = category,
    title = title,
    bodyText = bodyText,
    payloadJson = payloadJson,
)

fun PlanEntity.toDomain(): SavedPlan = SavedPlan(
    id = id,
    createdAt = Instant.ofEpochMilli(createdAtEpochMs),
    date = LocalDate.parse(dateIso),
    category = category,
    title = title,
    bodyText = bodyText,
    payloadJson = payloadJson,
)

// --- Seed data ---------------------------------------------------------------

/** First-run seed rows. Tracking profiles are seeded with NO drift values. */
object SeedData {
    fun cameraEntities(): List<CameraEntity> = BuiltInCameras.ALL.map { it.toEntity(custom = false) }

    fun trackingProfileEntities(): List<TrackingProfileEntity> =
        BuiltInTrackingProfiles.ALL.map { it.toEntity() }

    fun lensEntities(catalogueLenses: List<Lens>): List<LensEntity> =
        catalogueLenses.map { it.toEntity(custom = false) }
}
