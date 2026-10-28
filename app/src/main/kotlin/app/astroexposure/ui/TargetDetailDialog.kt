package app.astroexposure.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.astroexposure.R
import app.astroexposure.domain.catalog.DeepSkyObjectType
import app.astroexposure.domain.orientation.DeviceOrientationProvider
import app.astroexposure.domain.timeline.TimelineCursor
import app.astroexposure.ui.theme.LocalStateColors
import app.astroexposure.usecase.TonightOverviewUseCase
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * The target detail dialog's 0.8.0 layout (ui-design §7.5 as revised
 * for 0.8.0): the shared
 * [DetailDialog] shell carries a fixed head in its title slot — the
 * target's name, its catalogue meta line, one gold conclusion line
 * and the warning bar, none of which scroll away — a scrolling
 * middle (the finder section, the two-column key-data grid, the
 * folded whole-night section) as its body, and the shell's own
 * button row as the fixed footer (calculator jump + close).
 *
 * Every value shown comes from the one on-demand evaluation
 * ([TonightOverviewUseCase.TargetDetail]); the finder only renders
 * the same altitude/azimuth pair graphically. Judgement that can be
 * shared lives in the domain (finder readout) or the use case; this
 * file assembles wording.
 */
@Composable
fun TargetDetailDialog(
    detail: TonightOverviewUseCase.TargetDetail,
    cursor: Instant,
    zone: ZoneId,
    moonDegraded: Boolean,
    moonIllumination: Double,
    orientationProvider: DeviceOrientationProvider,
    siteLatitudeDeg: Double,
    siteLongitudeDeg: Double,
    siteAltitudeMeters: Double,
    onDismiss: () -> Unit,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    DetailDialog(
        title = detail.obj.nameZh ?: detail.obj.name,
        lines = emptyList(),
        onDismiss = onDismiss,
        actionLabel = actionLabel,
        onAction = onAction,
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = detail.obj.nameZh ?: detail.obj.name,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Text(
                            text = "✕",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = detailMetaLine(detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = detailConclusion(detail, cursor, zone),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                // The warning bar: bad news lives in the fixed head,
                // never in the scrolling body (ui-design §7.5). The
                // moonlight exclusion is the target's own verdict;
                // the full-moon downgrade is tonight's, passed in.
                if (detail.moonUp &&
                    detail.moonProximity == TimelineCursor.MoonProximity.EXCLUDED
                ) {
                    DetailWarningBar(
                        text = "▲ " + stringResource(
                            R.string.cursor_moon_excluded,
                            Math.round(detail.moonSeparationDeg),
                        ),
                        severity = WarningSeverity.BAD,
                    )
                } else if (moonDegraded) {
                    DetailWarningBar(
                        text = "! " + stringResource(
                            R.string.tonight_moon_degraded,
                            percentRounded(moonIllumination),
                        ),
                        severity = WarningSeverity.WARN,
                    )
                }
            }
        },
        content = {
            Column(
                modifier = Modifier
                    .heightIn(max = MIDDLE_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FinderSection(
                    provider = orientationProvider,
                    siteLatitudeDeg = siteLatitudeDeg,
                    siteLongitudeDeg = siteLongitudeDeg,
                    siteAltitudeMeters = siteAltitudeMeters,
                    targetAltDeg = detail.altitudeDeg,
                    targetAzDeg = detail.azimuthDeg,
                    horizonRise = detail.horizonRise,
                    horizonSet = detail.horizonSet,
                    cursor = cursor,
                    zone = zone,
                )
                DetailGrid(detail = detail, zone = zone)
                if (detail.moonUp &&
                    detail.moonProximity == TimelineCursor.MoonProximity.CAUTION
                ) {
                    Text(
                        text = stringResource(
                            R.string.cursor_moon_caution,
                            Math.round(detail.moonSeparationDeg),
                            stringResource(moonInterferenceNameRes(detail.moonInterference)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                WholeNightFold(detail = detail, zone = zone)
            }
        },
    )
}

/** Which of the two house warning forms a [DetailWarningBar] takes. */
private enum class WarningSeverity { BAD, WARN }

/**
 * One fixed warning bar in the dialog head. The form follows the
 * house line-type rule (ui-design §7.5), so severity is carried by
 * line type and glyph as well as colour: BAD is a thick solid top
 * bar over the glyph line (the eclipse gate's form), WARN is a
 * dashed border around it. Neither form fills the whole bar.
 */
@Composable
private fun DetailWarningBar(text: String, severity: WarningSeverity) {
    val stateColors = LocalStateColors.current
    when (severity) {
        WarningSeverity.BAD -> Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(stateColors.bad),
            )
            Text(
                text = text,
                color = stateColors.bad,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        WarningSeverity.WARN -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    drawRect(
                        color = stateColors.warn,
                        style = Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(8.dp.toPx(), 5.dp.toPx()),
                            ),
                        ),
                    )
                    drawContent()
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = text,
                color = stateColors.warn,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * The head's meta line: type · magnitude · constellation, each part
 * present only when the catalogue actually has it (never filled in).
 */
@Composable
private fun detailMetaLine(detail: TonightOverviewUseCase.TargetDetail): String {
    val obj = detail.obj
    val parts = buildList {
        add(
            stringResource(
                when (obj.type) {
                    DeepSkyObjectType.GALAXY -> R.string.obj_type_galaxy
                    DeepSkyObjectType.OPEN_CLUSTER -> R.string.obj_type_open_cluster
                    DeepSkyObjectType.GLOBULAR_CLUSTER -> R.string.obj_type_globular_cluster
                    DeepSkyObjectType.NEBULA -> R.string.obj_type_nebula
                    DeepSkyObjectType.PLANETARY_NEBULA -> R.string.obj_type_planetary_nebula
                    DeepSkyObjectType.SUPERNOVA_REMNANT -> R.string.obj_type_supernova_remnant
                    DeepSkyObjectType.DOUBLE_STAR -> R.string.obj_type_double_star
                    DeepSkyObjectType.ASTERISM -> R.string.obj_type_asterism
                    DeepSkyObjectType.OTHER -> R.string.obj_type_other
                },
            ),
        )
        obj.magnitude?.let { add(stringResource(R.string.detail_meta_magnitude, it)) }
        obj.constellation?.let { add(it) }
    }
    return parts.joinToString(" · ")
}

/**
 * The head's one gold conclusion, assembled from the cursor state:
 * below the horizon it is the rise/set fact; a relative-best window
 * is named as merely relative (a low night is never dressed up);
 * otherwise the recommended window's cursor state, or the plain
 * shootable / still-low verdict from the altitude itself.
 */
@Composable
private fun detailConclusion(
    detail: TonightOverviewUseCase.TargetDetail,
    cursor: Instant,
    zone: ZoneId,
): String {
    if (detail.altitudeDeg < 0.0) {
        val set = detail.horizonSet
        return when {
            set != null && !cursor.isBefore(set) ->
                stringResource(R.string.detail_conclusion_set, formatTime(set, zone))
            detail.horizonRise != null ->
                stringResource(
                    R.string.detail_conclusion_not_risen,
                    formatTime(detail.horizonRise, zone),
                )
            set != null ->
                stringResource(R.string.detail_conclusion_set_no_time)
            else -> stringResource(R.string.detail_conclusion_not_risen_no_time)
        }
    }
    val recommended = detail.recommended
    if (recommended != null && recommended.relativeBest) {
        val culminationTime = detail.culmination
            ?: recommended.interval.start
        return stringResource(
            R.string.detail_conclusion_relative_best,
            recommended.culminationAltitudeDeg.roundToInt(),
            formatTime(culminationTime, zone),
        )
    }
    val shootable = if (detail.altitudeDeg >= TimelineCursor.MIN_ALTITUDE_DEG) {
        stringResource(R.string.detail_conclusion_shootable)
    } else {
        stringResource(R.string.detail_conclusion_low)
    }
    return when (detail.recommendedState) {
        TimelineCursor.RecommendedState.INSIDE ->
            "$shootable · ${stringResource(R.string.detail_conclusion_inside)}"
        TimelineCursor.RecommendedState.BEFORE -> stringResource(
            R.string.detail_conclusion_enters,
            formatTime(recommended!!.interval.start, zone),
        )
        TimelineCursor.RecommendedState.AFTER ->
            "$shootable · ${stringResource(R.string.detail_conclusion_after)}"
        null -> shootable
    }
}

/**
 * The key-data grid (ui-design §7.5): four fixed rows of two cells —
 * cursor altitude / azimuth, culmination time / altitude, the
 * recommended window / the cursor's state against it, Moon
 * separation / interference tier. A missing datum shows the dash,
 * never an invented value.
 */
@Composable
private fun DetailGrid(detail: TonightOverviewUseCase.TargetDetail, zone: ZoneId) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GridCell(
                label = stringResource(R.string.detail_grid_altitude),
                value = "${detail.altitudeDeg.roundToInt()}°",
                modifier = Modifier.weight(1f),
            )
            GridCell(
                label = stringResource(R.string.detail_grid_azimuth),
                value = "${detail.azimuthDeg.roundToInt()}°",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GridCell(
                label = stringResource(R.string.detail_grid_culmination),
                value = detail.culmination?.let { formatTime(it, zone) }
                    ?: stringResource(R.string.detail_grid_none),
                modifier = Modifier.weight(1f),
            )
            GridCell(
                label = stringResource(R.string.detail_grid_culmination_altitude),
                value = detail.culminationAltitudeDeg?.let { "${it.roundToInt()}°" }
                    ?: stringResource(R.string.detail_grid_none),
                modifier = Modifier.weight(1f),
            )
        }
        val recommended = detail.recommended
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GridCell(
                label = stringResource(R.string.detail_grid_recommended),
                value = if (recommended != null) {
                    "${formatTime(recommended.interval.start, zone)} – " +
                        formatTime(recommended.interval.end, zone)
                } else {
                    stringResource(R.string.detail_grid_none)
                },
                sub = if (recommended?.relativeBest == true) {
                    stringResource(
                        R.string.cursor_recommended_relative_best,
                        recommended.culminationAltitudeDeg,
                    )
                } else {
                    null
                },
                modifier = Modifier.weight(1f),
            )
            GridCell(
                label = stringResource(R.string.detail_grid_cursor_state),
                value = when (detail.recommendedState) {
                    TimelineCursor.RecommendedState.INSIDE ->
                        stringResource(R.string.detail_state_inside)
                    TimelineCursor.RecommendedState.BEFORE -> stringResource(
                        R.string.detail_state_before,
                        detail.recommendedMinutesUntil,
                    )
                    TimelineCursor.RecommendedState.AFTER ->
                        stringResource(R.string.detail_state_after)
                    null -> stringResource(R.string.detail_grid_none)
                },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GridCell(
                label = stringResource(R.string.detail_grid_moon_separation),
                value = "${Math.round(detail.moonSeparationDeg)}°",
                sub = if (!detail.moonUp) {
                    stringResource(R.string.cursor_moon_below_horizon)
                } else {
                    null
                },
                modifier = Modifier.weight(1f),
            )
            GridCell(
                label = stringResource(R.string.detail_grid_moon_interference),
                value = stringResource(moonInterferenceNameRes(detail.moonInterference)),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** One grid cell: a small tertiary label over a tabular value, with an optional note line. */
@Composable
private fun GridCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
        )
        if (sub != null) {
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The folded whole-night section (ui-design §7.5): collapsed on every
 * open (the state is remembered per dialog, never saved), holding
 * the low-frequency facts — rise/set, the ≥20° window, the catalogue
 * size and coordinates, and the full-frame framing advice. Nothing
 * is deleted by the fold; it is one tap away.
 */
@Composable
private fun WholeNightFold(detail: TonightOverviewUseCase.TargetDetail, zone: ZoneId) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = (if (expanded) "▾ " else "▸ ") +
                    stringResource(R.string.detail_fold_title),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        if (expanded) {
            for (line in wholeNightLines(detail, zone)) {
                Text(text = line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** The folded section's lines, from the same one evaluation. */
@Composable
private fun wholeNightLines(
    detail: TonightOverviewUseCase.TargetDetail,
    zone: ZoneId,
): List<String> = buildList {
    detail.horizonRise?.let {
        add(stringResource(R.string.detail_fold_rise, formatTime(it, zone)))
    }
    detail.horizonSet?.let {
        add(stringResource(R.string.detail_fold_set, formatTime(it, zone)))
    }
    val phases = detail.phases
    if (phases != null) {
        add(
            stringResource(
                R.string.cursor_detail_window,
                formatTime(phases.window.start, zone),
                formatTime(phases.window.end, zone),
                formatTime(phases.culmination.instant, zone),
                phases.culmination.altitudeDeg,
            ),
        )
    } else {
        add(stringResource(R.string.detail_fold_no_window))
    }
    val obj = detail.obj
    val sizeMajor = obj.sizeMajorArcmin
    val sizeMinor = obj.sizeMinorArcmin
    if (sizeMajor != null) {
        add(
            if (sizeMinor != null) {
                stringResource(
                    R.string.cursor_detail_size_both,
                    Math.round(sizeMajor), Math.round(sizeMinor),
                )
            } else {
                stringResource(R.string.cursor_detail_size_major, Math.round(sizeMajor))
            },
        )
    }
    add(stringResource(R.string.detail_fold_ra_dec, obj.raHours, obj.decDeg))
    add(framingAdviceText(detail))
}

/**
 * The full-frame framing advice line (36.0 x 24.0 mm; the domain's
 * FullFrameFraming owns the formulas and boundary rules — this only
 * renders the form it is given, including the honest capped forms).
 */
@Composable
private fun framingAdviceText(detail: TonightOverviewUseCase.TargetDetail): String =
    when (val framing = detail.framing) {
        is app.astroexposure.domain.camera.FullFrameFraming.Advice.NoSizeData ->
            stringResource(R.string.cursor_framing_no_size)
        is app.astroexposure.domain.camera.FullFrameFraming.Advice.Range ->
            stringResource(
                R.string.cursor_framing_range,
                Math.round(framing.lowFocalMm),
                Math.round(framing.highFocalMm),
                Math.round(framing.referenceFocalMm),
                percentRounded(framing.referenceShare),
            )
        is app.astroexposure.domain.camera.FullFrameFraming.Advice.CappedAtMaxFocal ->
            stringResource(
                R.string.cursor_framing_capped_max,
                Math.round(
                    app.astroexposure.domain.camera.FullFrameFraming.MAX_FOCAL_MM,
                ).toInt(),
                percentRounded(framing.shareAtMaxFocal),
            )
        is app.astroexposure.domain.camera.FullFrameFraming.Advice.CappedAtMinFocal ->
            stringResource(
                R.string.cursor_framing_capped_min,
                Math.round(
                    app.astroexposure.domain.camera.FullFrameFraming.MIN_FOCAL_MM,
                ).toInt(),
                percentRounded(framing.shareAtMinFocal),
            )
    }

/** The scrolling middle's height cap: the head and footer stay put, the middle scrolls. */
private val MIDDLE_MAX_HEIGHT = 520.dp
