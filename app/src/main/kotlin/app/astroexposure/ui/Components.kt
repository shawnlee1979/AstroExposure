package app.astroexposure.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.astroexposure.R
import app.astroexposure.domain.camera.Camera
import app.astroexposure.domain.lens.Lens
import app.astroexposure.domain.strictness.Strictness
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun formatTime(instant: Instant?, zone: ZoneId): String =
    instant?.let { TIME_FORMAT.format(it.atZone(zone)) } ?: "—"

/** Camera-dial shutter notation: fractions as 1/N, long exposures in seconds. */
fun formatShutter(seconds: Double): String = when {
    seconds < 1.0 -> "1/${(1.0 / seconds).roundToInt()}"
    seconds < 10.0 -> "%.1f s".format(seconds)
    else -> "%.0f s".format(seconds)
}

fun formatAperture(aperture: Double): String =
    if (aperture == aperture.roundToInt().toDouble() && aperture >= 8.0) {
        "f/${aperture.roundToInt()}"
    } else {
        "f/$aperture"
    }

/** A label + button + dropdown selection control (large touch targets, no tiny spinners). */
@Composable
fun <T> SelectionMenu(
    label: String,
    items: List<T>,
    selected: T?,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = selected?.let(itemLabel) ?: "—")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                items.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(itemLabel(item)) },
                        onClick = {
                            expanded = false
                            onSelect(item)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The aperture stop picker (ui-design §4.3, a recorded product decision):
 * the scale starts at the lens's effective maximum aperture for the chosen
 * focal length and runs to f/22 in standard 1/3-stop steps; interaction is
 * steppers plus a horizontally scrollable tick band that selects on tap.
 * No free numeric entry, no non-standard values.
 */
@Composable
fun AperturePicker(
    scale: List<Double>,
    selected: Double,
    onSelect: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = scale.indexOfFirst { it == selected }.coerceAtLeast(0)
    Column(modifier = modifier) {
        Text(text = stringResource(R.string.calc_aperture), style = MaterialTheme.typography.labelLarge)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { if (index > 0) onSelect(scale[index - 1]) },
                enabled = index > 0,
            ) { Text("−") }
            Text(
                text = formatAperture(selected),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            OutlinedButton(
                onClick = { if (index < scale.lastIndex) onSelect(scale[index + 1]) },
                enabled = index < scale.lastIndex,
            ) { Text("+") }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            scale.forEach { stop ->
                FilterChip(
                    modifier = Modifier.heightIn(min = 48.dp),
                    selected = stop == selected,
                    onClick = { onSelect(stop) },
                    label = { Text(formatAperture(stop)) },
                )
            }
        }
    }
}

/**
 * Focal-length control per the library-driven input flow: primes render no
 * control at all (their focal length comes from the library entry); zooms
 * get a slider + steppers inside their own range.
 */
@Composable
fun FocalControl(
    lens: Lens,
    focalMm: Double,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lens.isPrime) return
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.calc_focal) + " · ${focalMm.roundToInt()} mm",
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = focalMm.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = lens.focalMinMm.toFloat()..lens.focalMaxMm.toFloat(),
        )
    }
}

/** Camera-then-lens equipment selection, shared by all five calculators. */
@Composable
fun EquipmentPicker(
    cameras: List<Camera>,
    lenses: List<Lens>,
    camera: Camera?,
    lens: Lens?,
    focalMm: Double,
    onCamera: (Camera) -> Unit,
    onLens: (Lens) -> Unit,
    onFocal: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectionMenu(
            label = stringResource(R.string.calc_camera),
            items = cameras,
            selected = camera,
            itemLabel = { it.displayName },
            onSelect = onCamera,
        )
        SelectionMenu(
            label = stringResource(R.string.calc_lens),
            items = lenses,
            selected = lens,
            itemLabel = { "${it.name} (${it.mount})" },
            onSelect = onLens,
        )
        if (lens != null) {
            FocalControl(lens = lens, focalMm = focalMm, onChange = onFocal)
        }
        Text(
            text = stringResource(R.string.calc_lens_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun StrictnessSelector(
    value: Strictness,
    onChange: (Strictness) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = stringResource(R.string.strictness_label), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Strictness.entries.forEach { tier ->
                FilterChip(
                    modifier = Modifier.heightIn(min = 48.dp),
                    selected = tier == value,
                    onClick = { onChange(tier) },
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
    }
}

/**
 * A static badge: looks like a chip but is not a control — no click,
 * no focus, and accessibility services read it as plain text. (The
 * strictness and unmeasured badges previously used a disabled-purpose
 * FilterChip, which screen readers announced as a tappable chip that
 * did nothing.)
 */
@Composable
fun BadgeChip(text: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

fun strictnessNameRes(tier: Strictness): Int = when (tier) {
    Strictness.STRICT -> R.string.strictness_strict
    Strictness.STANDARD -> R.string.strictness_standard
    Strictness.RELAXED -> R.string.strictness_relaxed
}

/** One big conclusion value on a result card (ui-design §2: tabular numerals ≥ 34sp for conclusions). */
@Composable
fun BigStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            fontSize = 34.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Result card shell (ui-design §4.2 / design §7.1): title row with the
 * strictness badge, an optional straight-talking warning banner, the big
 * values, reason lines and the fixed starting-values footer.
 */
@Composable
fun ResultCard(
    title: String,
    badge: String?,
    warning: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        // Degradation is stated by a full-width warn bar across the very
        // top of the card (ui-design §4.2), not an inset sub-card in a
        // bad-semantic colour: the "!" glyph carries the state alongside
        // the colour.
        if (warning != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        app.astroexposure.ui.theme.LocalStateColors.current.warn,
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "! $warning",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
            }
        }
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                if (badge != null) {
                    BadgeChip(badge)
                }
            }
            content()
            Text(
                text = stringResource(R.string.calc_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
fun ActionButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, modifier = modifier.fillMaxWidth(), enabled = enabled) {
        Text(text)
    }
}

/**
 * The one detail dialog (ui-design §7.4 / §7.5): a title, plain text
 * lines and a close button. Timeline mark details and target details
 * render through this single component so every popup on the tonight
 * page shares one shape; a caller with a follow-on action (the target
 * detail's calculator jump) adds it beside the close button.
 */
@Composable
fun DetailDialog(
    title: String,
    lines: List<String>,
    onDismiss: () -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(lines.joinToString("\n")) },
        confirmButton = {
            Row {
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction) { Text(actionLabel) }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_close))
                }
            }
        },
    )
}
