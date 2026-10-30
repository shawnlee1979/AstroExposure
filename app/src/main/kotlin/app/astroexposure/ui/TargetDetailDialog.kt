package app.astroexposure.ui

import androidx.compose.foundation.background
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
import app.astroexposure.domain.camera.FullFrameFraming
import app.astroexposure.domain.catalog.ConstellationNames
import app.astroexposure.domain.catalog.DeepSkyObjectType
import app.astroexposure.domain.orientation.DeviceOrientationProvider
import app.astroexposure.domain.timeline.TimelineCursor
import app.astroexposure.ui.theme.LocalStateColors
import app.astroexposure.usecase.TonightOverviewUseCase
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * The target detail dialog's layout (ui-design §7.5): the shared
 * [DetailDialog] shell carries a fixed head in its title slot — the
 * target's name, its catalogue meta line, one gold conclusion line
 * and the warning bar, none of which scroll away — a scrolling
 * middle (the finder section, then the one six-row two-column data
 * grid and its small-print notes) as its body, and the shell's own
 * button row as the fixed footer (calculator jump + close). The
 * 0.8.0 folded whole-night section is gone since 0.8.2: every datum
 * lives in the one grid, each exactly once.
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
        title = detail.subject.displayName(),
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
                        text = detail.subject.displayName(),
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
                // The grid's small-print notes: the relative-best
                // qualification of the recommended window, the neutral
                // moonlight caution, and the framing remainder the
                // focal cells cannot carry (ui-design §7.5).
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val recommended = detail.recommended
                    if (recommended?.relativeBest == true) {
                        Text(
                            text = stringResource(
                                R.string.cursor_recommended_relative_best,
                                recommended.culminationAltitudeDeg,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (detail.moonUp &&
                        detail.moonProximity == TimelineCursor.MoonProximity.CAUTION
                    ) {
                        Text(
                            text = stringResource(
                                R.string.cursor_moon_caution,
                                Math.round(detail.moonSeparationDeg),
                                stringResource(
                                    moonInterferenceNameRes(detail.moonInterference),
                                ),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Category notes (ui-design §7.8): a
                    // constellation names its representative bright
                    // stars and its editorial note; a viewpoint shows
                    // its note; both state the centre-point caliber
                    // their arc and windows were computed under. A
                    // radiant has no note lines.
                    when (val subject = detail.subject) {
                        is TonightOverviewUseCase.TonightSubject.Constellation -> {
                            val english = isEnglishUiLanguage()
                            val stars = subject.entry.brightStars.joinToString(" · ") { star ->
                                (if (english) star.nameEn else star.nameZh) +
                                    " " + star.magnitude
                            }
                            Text(
                                text = stringResource(
                                    R.string.detail_note_bright_stars, stars,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = if (english) {
                                    subject.entry.noteEn
                                } else {
                                    subject.entry.noteZh
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(
                                    R.string.detail_note_center_point,
                                    subject.entry.sizeMajorDeg,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        is TonightOverviewUseCase.TonightSubject.Viewpoint -> {
                            val english = isEnglishUiLanguage()
                            Text(
                                text = if (english) {
                                    subject.viewpoint.noteEn
                                } else {
                                    subject.viewpoint.noteZh
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(
                                    R.string.detail_note_center_point,
                                    subject.viewpoint.sizeMajorDeg,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        else -> Unit
                    }
                    if (detail.subject !is TonightOverviewUseCase.TonightSubject.Radiant) {
                        Text(
                            text = framingNoteText(detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
 * The head's meta line, per subject category (ui-design §7.5, §7.8):
 * a deep-sky object keeps type · magnitude · constellation, each
 * part present only when the catalogue actually has it (never
 * filled in); a constellation or viewpoint shows its category and
 * its approximate angular extent; a radiant names itself. The DSO
 * catalogue stores the IAU three-letter code; the line shows the
 * localized name ([ConstellationNames]), never the raw code.
 */
@Composable
private fun detailMetaLine(detail: TonightOverviewUseCase.TargetDetail): String {
    val english = isEnglishUiLanguage()
    return when (val subject = detail.subject) {
        is TonightOverviewUseCase.TonightSubject.DeepSky -> {
            val obj = subject.obj
            val parts = mutableListOf<String>()
            parts += stringResource(
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
            )
            obj.magnitude?.let {
                parts += stringResource(R.string.detail_meta_magnitude, it)
            }
            obj.constellation?.let {
                parts += ConstellationNames.displayName(it, english)
            }
            parts.joinToString(" · ")
        }
        is TonightOverviewUseCase.TonightSubject.Constellation ->
            stringResource(R.string.detail_meta_constellation) + " · " +
                stringResource(
                    R.string.detail_meta_extent,
                    subject.entry.sizeMajorDeg,
                    subject.entry.sizeMinorDeg,
                )
        is TonightOverviewUseCase.TonightSubject.Viewpoint ->
            stringResource(R.string.detail_meta_viewpoint) + " · " +
                stringResource(
                    R.string.detail_meta_extent,
                    subject.viewpoint.sizeMajorDeg,
                    subject.viewpoint.sizeMinorDeg,
                )
        is TonightOverviewUseCase.TonightSubject.Radiant ->
            stringResource(R.string.detail_meta_radiant)
    }
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
 * The one data grid (ui-design §7.5, consolidated in 0.8.2): six
 * fixed rows of two cells — cursor altitude / azimuth, catalogue
 * right ascension / declination, culmination time / altitude, the
 * shootable window / the recommended window, Moon separation /
 * interference tier, suggested focal range / reference focal. Every
 * datum appears exactly once in the whole dialog; the cursor's state
 * against the recommended window is the head conclusion's business,
 * not a cell. A missing datum shows the dash, never an invented
 * value.
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
        // Row 2 names what the coordinates ARE (ui-design §7.8):
        // a centre for constellations and viewpoints, the radiant
        // for a shower, the object itself for deep-sky entries.
        val raLabel = when (detail.subject) {
            is TonightOverviewUseCase.TonightSubject.Constellation,
            is TonightOverviewUseCase.TonightSubject.Viewpoint ->
                R.string.detail_grid_ra_center
            is TonightOverviewUseCase.TonightSubject.Radiant ->
                R.string.detail_grid_ra_radiant
            is TonightOverviewUseCase.TonightSubject.DeepSky -> R.string.detail_grid_ra
        }
        val decLabel = when (detail.subject) {
            is TonightOverviewUseCase.TonightSubject.Constellation,
            is TonightOverviewUseCase.TonightSubject.Viewpoint ->
                R.string.detail_grid_dec_center
            is TonightOverviewUseCase.TonightSubject.Radiant ->
                R.string.detail_grid_dec_radiant
            is TonightOverviewUseCase.TonightSubject.DeepSky -> R.string.detail_grid_dec
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GridCell(
                label = stringResource(raLabel),
                value = stringResource(
                    R.string.detail_grid_ra_value,
                    detail.subject.coordinates.raHours,
                ),
                modifier = Modifier.weight(1f),
            )
            GridCell(
                label = stringResource(decLabel),
                value = stringResource(
                    R.string.detail_grid_dec_value,
                    detail.subject.coordinates.declination.value,
                ),
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
        val phases = detail.phases
        val recommended = detail.recommended
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GridCell(
                label = stringResource(R.string.detail_grid_shootable_window),
                value = if (phases != null) {
                    "${formatTime(phases.window.start, zone)} – " +
                        formatTime(phases.window.end, zone)
                } else {
                    stringResource(R.string.detail_grid_none)
                },
                sub = if (phases == null) {
                    stringResource(R.string.detail_grid_no_window)
                } else {
                    null
                },
                modifier = Modifier.weight(1f),
            )
            GridCell(
                label = stringResource(R.string.detail_grid_recommended),
                value = if (recommended != null) {
                    "${formatTime(recommended.interval.start, zone)} – " +
                        formatTime(recommended.interval.end, zone)
                } else {
                    stringResource(R.string.detail_grid_none)
                },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GridCell(
                // The separation is measured at the calculation
                // point; for an extended entry the label says so
                // (ui-design §7.8: 距中心).
                label = stringResource(
                    if (
                        detail.subject is TonightOverviewUseCase.TonightSubject.Constellation ||
                        detail.subject is TonightOverviewUseCase.TonightSubject.Viewpoint
                    ) {
                        R.string.detail_grid_moon_separation_center
                    } else {
                        R.string.detail_grid_moon_separation
                    },
                ),
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
        if (detail.subject is TonightOverviewUseCase.TonightSubject.Radiant) {
            // Row 6 for a radiant (ui-design §7.8): peak date and
            // ZHR instead of focal lengths — a radiant has no size
            // to frame.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GridCell(
                    label = stringResource(R.string.detail_grid_peak_date),
                    value = detail.meteorPeakDate?.let {
                        stringResource(R.string.detail_grid_peak_value, it.toString())
                    } ?: stringResource(R.string.detail_grid_none),
                    modifier = Modifier.weight(1f),
                )
                GridCell(
                    label = stringResource(R.string.detail_grid_zhr),
                    value = detail.meteorZhr?.toString()
                        ?: stringResource(R.string.detail_grid_none),
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            val focals = focalCells(detail)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GridCell(
                    label = stringResource(R.string.detail_grid_suggested_focal),
                    value = focals.first,
                    modifier = Modifier.weight(1f),
                )
                GridCell(
                    label = stringResource(R.string.detail_grid_reference_focal),
                    value = focals.second,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The focal row's two cell values (suggested range, reference) from
 * the domain's framing advice. Only the plain range form fills the
 * cells; the capped forms and the no-size form name no range and no
 * reference, so both cells show the dash and the note below the grid
 * ([framingNoteText]) states the boundary honestly.
 */
@Composable
private fun focalCells(
    detail: TonightOverviewUseCase.TargetDetail,
): Pair<String, String> = when (val framing = detail.framing) {
    is FullFrameFraming.Advice.Range -> Pair(
        stringResource(
            R.string.detail_grid_focal_range,
            Math.round(framing.lowFocalMm),
            Math.round(framing.highFocalMm),
        ),
        stringResource(
            R.string.detail_grid_focal_single,
            Math.round(framing.referenceFocalMm),
        ),
    )
    else -> Pair(
        stringResource(R.string.detail_grid_none),
        stringResource(R.string.detail_grid_none),
    )
}

/**
 * The framing note below the grid: what the focal cells cannot
 * carry — the reference focal's share of the long side for the range
 * form, and the honest boundary statement for the capped and
 * no-size forms. 36.0 x 24.0 mm throughout; the domain's
 * [FullFrameFraming] owns the formulas and boundary rules.
 */
@Composable
private fun framingNoteText(detail: TonightOverviewUseCase.TargetDetail): String =
    when (val framing = detail.framing) {
        is FullFrameFraming.Advice.NoSizeData ->
            stringResource(R.string.cursor_framing_no_size)
        is FullFrameFraming.Advice.Range ->
            stringResource(
                R.string.detail_framing_note_range,
                percentRounded(framing.referenceShare),
            )
        is FullFrameFraming.Advice.CappedAtMaxFocal ->
            stringResource(
                R.string.cursor_framing_capped_max,
                Math.round(FullFrameFraming.MAX_FOCAL_MM).toInt(),
                percentRounded(framing.shareAtMaxFocal),
            )
        is FullFrameFraming.Advice.CappedAtMinFocal ->
            stringResource(
                R.string.cursor_framing_capped_min,
                Math.round(FullFrameFraming.MIN_FOCAL_MM).toInt(),
                percentRounded(framing.shareAtMinFocal),
            )
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

/** The scrolling middle's height cap: the head and footer stay put, the middle scrolls. */
private val MIDDLE_MAX_HEIGHT = 520.dp
