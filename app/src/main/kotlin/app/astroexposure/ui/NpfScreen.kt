package app.astroexposure.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.astroexposure.R
import app.astroexposure.domain.camera.BuiltInCameras
import app.astroexposure.domain.camera.Camera
import app.astroexposure.domain.strictness.Strictness
import app.astroexposure.usecase.NpfCalculatorUseCase

/**
 * NPF calculator slice. Collects inputs and renders the use case's card;
 * contains no exposure formula (DEVELOPMENT-RULES.md D.4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NpfScreen(
    useCase: NpfCalculatorUseCase,
    modifier: Modifier = Modifier,
) {
    var camera by remember { mutableStateOf<Camera>(BuiltInCameras.A1_II) }
    var focalText by remember { mutableStateOf("50") }
    var apertureText by remember { mutableStateOf("2.0") }
    var declinationText by remember { mutableStateOf("-29") }
    var strictness by remember { mutableStateOf(Strictness.STRICT) }
    var cameraMenuOpen by remember { mutableStateOf(false) }

    val focal = (focalText.toDoubleOrNull() ?: 50.0).coerceAtLeast(1.0)
    val aperture = (apertureText.toDoubleOrNull() ?: 2.0).coerceAtLeast(0.7)
    val declination = declinationText.toDoubleOrNull() ?: -29.0

    val card = remember(camera, focal, aperture, declination, strictness) {
        useCase.execute(
            NpfCalculatorUseCase.Input(camera, focal, aperture, declination, strictness),
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ExposedDropdownMenuBox(
            expanded = cameraMenuOpen,
            onExpandedChange = { cameraMenuOpen = it },
        ) {
            OutlinedTextField(
                value = camera.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.npf_camera)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cameraMenuOpen) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = cameraMenuOpen,
                onDismissRequest = { cameraMenuOpen = false },
            ) {
                BuiltInCameras.ALL.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(candidate.displayName) },
                        onClick = {
                            camera = candidate
                            cameraMenuOpen = false
                        },
                    )
                }
            }
        }

        NumberField(focalText, { focalText = it }, stringResource(R.string.npf_focal))
        NumberField(apertureText, { apertureText = it }, stringResource(R.string.npf_aperture))
        NumberField(declinationText, { declinationText = it }, stringResource(R.string.npf_declination))

        Text(
            text = stringResource(R.string.npf_strictness),
            style = MaterialTheme.typography.labelLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Strictness.entries.forEach { tier ->
                FilterChip(
                    selected = strictness == tier,
                    onClick = { strictness = tier },
                    label = {
                        Text(
                            stringResource(
                                when (tier) {
                                    Strictness.STRICT -> R.string.strictness_strict
                                    Strictness.STANDARD -> R.string.strictness_standard
                                    Strictness.RELAXED -> R.string.strictness_relaxed
                                },
                            ),
                        )
                    },
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.npf_result_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.npf_result_value, card.maxShutterSeconds),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(
                        R.string.npf_reason,
                        card.kUsed,
                        stringResource(
                            when (strictness) {
                                Strictness.STRICT -> R.string.strictness_strict
                                Strictness.STANDARD -> R.string.strictness_standard
                                Strictness.RELAXED -> R.string.strictness_relaxed
                            },
                        ),
                        card.pixelPitchUm,
                        card.ruleOf500Seconds,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (card.declinationCapped) {
                    Text(
                        text = stringResource(R.string.npf_capped_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
