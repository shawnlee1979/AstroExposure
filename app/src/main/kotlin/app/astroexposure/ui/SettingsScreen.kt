package app.astroexposure.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.astroexposure.R
import app.astroexposure.domain.camera.Camera
import app.astroexposure.domain.lens.Lens
import app.astroexposure.domain.strictness.Strictness
import app.astroexposure.domain.tracking.TrackingProfile
import app.astroexposure.persistence.FormResult
import app.astroexposure.persistence.SavedPlace
import app.astroexposure.persistence.validateCustomCamera
import app.astroexposure.persistence.validateCustomLens
import app.astroexposure.persistence.validatePlace
import app.astroexposure.support.TimeHealthCheck
import java.time.ZoneId
import java.util.UUID

/**
 * Settings tab (ui-design §3): equipment library management (custom
 * cameras/lenses are added HERE — calculators never take ad-hoc entries),
 * tracking profiles with their measurement entry, favourite places,
 * defaults, red-light mode, language, time health and the data-sources /
 * licences page.
 */
@Composable
fun SettingsScreen(
    cameras: List<Pair<Camera, Boolean>>,
    lenses: List<Pair<Lens, Boolean>>,
    profiles: List<TrackingProfile>,
    places: List<SavedPlace>,
    defaultStrictness: Strictness,
    onDefaultStrictnessChange: (Strictness) -> Unit,
    redLight: Boolean,
    onRedLightChange: (Boolean) -> Unit,
    redLightAuto: Boolean,
    onRedLightAutoChange: (Boolean) -> Unit,
    onLanguageToggle: () -> Unit,
    timeHealth: TimeHealthCheck.Result,
    dataSourcesText: String,
    currentZone: ZoneId,
    onAddCamera: (Camera) -> Unit,
    onDeleteCamera: (Camera, Boolean) -> Unit,
    onAddLens: (Lens) -> Unit,
    onDeleteLens: (Lens, Boolean) -> Unit,
    onSaveProfileDrift: (TrackingProfile, Double?, Double?) -> Unit,
    onAddPlace: (SavedPlace) -> Unit,
    onDeletePlace: (SavedPlace) -> Unit,
    onUsePlace: (SavedPlace) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle(stringResource(R.string.settings_equipment))

        // Cameras.
        Text(text = stringResource(R.string.settings_cameras), style = MaterialTheme.typography.titleSmall)
        cameras.forEach { (camera, custom) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${camera.displayName} · " + stringResource(
                        if (custom) R.string.settings_custom_badge else R.string.settings_builtin_badge,
                    ),
                )
                if (custom) {
                    TextButton(onClick = { onDeleteCamera(camera, custom) }) {
                        Text(stringResource(R.string.settings_delete))
                    }
                }
            }
        }
        CameraForm(onAddCamera)

        // Lenses.
        Text(text = stringResource(R.string.settings_lenses), style = MaterialTheme.typography.titleSmall)
        lenses.forEach { (lens, custom) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${lens.name} · " + stringResource(
                        if (custom) R.string.settings_custom_badge else R.string.settings_builtin_badge,
                    ),
                    modifier = Modifier.weight(1f),
                )
                if (custom) {
                    TextButton(onClick = { onDeleteLens(lens, custom) }) {
                        Text(stringResource(R.string.settings_delete))
                    }
                }
            }
        }
        LensForm(onAddLens)

        // Tracking profiles.
        SectionTitle(stringResource(R.string.settings_profiles))
        profiles.forEach { profile ->
            ProfileRow(profile, onSaveProfileDrift)
        }

        // Favourite places.
        SectionTitle(stringResource(R.string.settings_places))
        places.forEach { place ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = place.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "%.4f, %.4f".format(
                            place.location.latitude.value, place.location.longitude.value,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        TextButton(onClick = { onUsePlace(place) }) {
                            Text(stringResource(R.string.settings_place_use))
                        }
                        TextButton(onClick = { onDeletePlace(place) }) {
                            Text(stringResource(R.string.settings_delete))
                        }
                    }
                }
            }
        }
        PlaceForm(currentZone, onAddPlace)

        // Defaults & display.
        SectionTitle(stringResource(R.string.settings_defaults))
        Text(
            text = stringResource(R.string.settings_default_strictness),
            style = MaterialTheme.typography.labelLarge,
        )
        StrictnessSelector(value = defaultStrictness, onChange = onDefaultStrictnessChange)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = stringResource(R.string.red_light))
            Switch(checked = redLight, onCheckedChange = onRedLightChange)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = stringResource(R.string.red_light_auto))
            Switch(checked = redLightAuto, onCheckedChange = onRedLightAutoChange)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = stringResource(R.string.settings_language))
            OutlinedButton(onClick = onLanguageToggle) {
                Text(stringResource(R.string.language_switch))
            }
        }

        // Time health.
        SectionTitle(stringResource(R.string.settings_time_health))
        if (timeHealth.healthy) {
            Text(text = stringResource(R.string.time_health_ok))
        } else {
            timeHealth.warnings.forEach { warning ->
                Text(
                    text = when (warning) {
                        TimeHealthCheck.Warning.ZONE_MISMATCH ->
                            stringResource(R.string.time_health_zone_mismatch)
                        TimeHealthCheck.Warning.CLOCK_SKEW ->
                            stringResource(
                                R.string.time_health_clock_skew,
                                timeHealth.clockSkewSeconds ?: 0L,
                            )
                    },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Data sources & licences.
        SectionTitle(stringResource(R.string.settings_data_sources))
        Text(text = dataSourcesText, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CameraForm(onAdd: (Camera) -> Unit) {
    var name by remember { mutableStateOf("") }
    var width by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var px by remember { mutableStateOf("") }
    var py by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = stringResource(R.string.settings_add_camera), style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.settings_field_name)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(width, { width = it }, label = { Text(stringResource(R.string.settings_field_sensor_w)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(height, { height = it }, label = { Text(stringResource(R.string.settings_field_sensor_h)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(px, { px = it }, label = { Text(stringResource(R.string.settings_field_pixels_x)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(py, { py = it }, label = { Text(stringResource(R.string.settings_field_pixels_y)) }, modifier = Modifier.fillMaxWidth())
        if (error) {
            Text(text = stringResource(R.string.settings_form_error), color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(onClick = {
            val result = validateCustomCamera(
                id = "custom-" + UUID.randomUUID().toString(),
                name = name,
                sensorWidthMm = width.toDoubleOrNull() ?: 0.0,
                sensorHeightMm = height.toDoubleOrNull() ?: 0.0,
                pixelsX = px.toIntOrNull() ?: 0,
                pixelsY = py.toIntOrNull() ?: 0,
            )
            when (result) {
                is FormResult.Ok -> {
                    error = false
                    onAdd(result.value)
                    name = ""; width = ""; height = ""; px = ""; py = ""
                }
                is FormResult.Invalid -> error = true
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_add)) }
    }
}

@Composable
private fun LensForm(onAdd: (Lens) -> Unit) {
    var name by remember { mutableStateOf("") }
    var mount by remember { mutableStateOf("") }
    var min by remember { mutableStateOf("") }
    var max by remember { mutableStateOf("") }
    var aperture by remember { mutableStateOf("") }
    var tele by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = stringResource(R.string.settings_add_lens), style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.settings_field_name)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(mount, { mount = it }, label = { Text(stringResource(R.string.settings_field_mount)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(min, { min = it }, label = { Text(stringResource(R.string.settings_field_focal_min)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(max, { max = it }, label = { Text(stringResource(R.string.settings_field_focal_max)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(aperture, { aperture = it }, label = { Text(stringResource(R.string.settings_field_max_aperture)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(tele, { tele = it }, label = { Text(stringResource(R.string.settings_field_max_aperture_tele)) }, modifier = Modifier.fillMaxWidth())
        if (error) {
            Text(text = stringResource(R.string.settings_form_error), color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(onClick = {
            val result = validateCustomLens(
                id = "custom-" + UUID.randomUUID().toString(),
                name = name,
                mount = mount,
                focalMinMm = min.toDoubleOrNull() ?: 0.0,
                focalMaxMm = max.toDoubleOrNull() ?: 0.0,
                maxAperture = aperture.toDoubleOrNull() ?: 0.0,
                maxApertureAtMaxFocal = tele.toDoubleOrNull(),
            )
            when (result) {
                is FormResult.Ok -> {
                    error = false
                    onAdd(result.value)
                    name = ""; mount = ""; min = ""; max = ""; aperture = ""; tele = ""
                }
                is FormResult.Invalid -> error = true
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_add)) }
    }
}

@Composable
private fun ProfileRow(
    profile: TrackingProfile,
    onSaveDrift: (TrackingProfile, Double?, Double?) -> Unit,
) {
    var typical by remember(profile.id) { mutableStateOf(profile.typicalDrift?.value?.toString() ?: "") }
    var worst by remember(profile.id) { mutableStateOf(profile.worstDrift?.value?.toString() ?: "") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = profile.name, style = MaterialTheme.typography.titleSmall)
            val typicalDrift = profile.typicalDrift
            if (typicalDrift == null) {
                Text(
                    text = stringResource(R.string.settings_profile_unmeasured),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.settings_profile_measured,
                        typicalDrift.value,
                        profile.worstDrift?.value ?: typicalDrift.value,
                        profile.measuredOn?.toString() ?: "—",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (profile.kind == app.astroexposure.domain.tracking.TrackingKind.TRACKED) {
                OutlinedTextField(
                    typical, { typical = it },
                    label = { Text(stringResource(R.string.settings_profile_drift_typical)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    worst, { worst = it },
                    label = { Text(stringResource(R.string.settings_profile_drift_worst)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        onSaveDrift(profile, typical.toDoubleOrNull(), worst.toDoubleOrNull())
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_profile_save_drift)) }
            }
        }
    }
}

@Composable
private fun PlaceForm(currentZone: ZoneId, onAdd: (SavedPlace) -> Unit) {
    var name by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var bortle by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = stringResource(R.string.settings_add_place), style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.settings_field_name)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(lat, { lat = it }, label = { Text(stringResource(R.string.location_latitude)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(lon, { lon = it }, label = { Text(stringResource(R.string.location_longitude)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(bortle, { bortle = it }, label = { Text(stringResource(R.string.bortle_manual_label)) }, modifier = Modifier.fillMaxWidth())
        if (error) {
            Text(text = stringResource(R.string.settings_form_error), color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(onClick = {
            val result = validatePlace(
                id = "place-" + UUID.randomUUID().toString(),
                name = name,
                latitudeDeg = lat.toDoubleOrNull() ?: Double.NaN,
                longitudeDeg = lon.toDoubleOrNull() ?: Double.NaN,
                heightMeters = 0.0,
                zone = currentZone,
                manualBortle = bortle.toIntOrNull(),
            )
            when (result) {
                is FormResult.Ok -> {
                    error = false
                    onAdd(result.value)
                    name = ""; lat = ""; lon = ""; bortle = ""
                }
                is FormResult.Invalid -> error = true
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_add)) }
    }
}
