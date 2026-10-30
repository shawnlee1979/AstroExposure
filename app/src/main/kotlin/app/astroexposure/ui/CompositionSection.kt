package app.astroexposure.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.astroexposure.R
import app.astroexposure.domain.orientation.DeviceOrientationProvider
import app.astroexposure.domain.timeline.CompositionWindow
import app.astroexposure.domain.timeline.NightTimeline
import app.astroexposure.ui.theme.LocalStateColors
import app.astroexposure.usecase.TonightOverviewUseCase
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The landscape composition section (ui-design §7.9): ONE section
 * behind both entrances — the target detail dialog (its subject is
 * the entry being viewed) and the landscape calculator (its subject
 * comes from the selector the calculator renders into
 * [subjectSelector]). Both feed the same use-case evaluation and
 * render the same result structure; the section owns the bearing
 * and tolerance as session state (a bearing is a field measurement
 * — nothing is persisted and nothing is shared between entrances).
 *
 * The bearing has two writers on one field: the stepper (tap ±1°,
 * hold for repeated ±5° steps, the cursor time bar's button
 * discipline) and compass capture (subscribe only while capturing,
 * cancel on lock-in / cancel / disposal — the finder section's
 * subscription discipline). Evaluation is debounced like the
 * cursor's, so stepping does not evaluate every intermediate value.
 * Both writers also raise the bearing's edited flag: until either
 * has run, the 180° the field opens on is an unedited placeholder
 * and the section marks the evaluation as a sample (§7.9, 0.13.0)
 * — the flag follows the edit, never the value.
 */
@Composable
fun CompositionSection(
    onEvaluate: (bearingDeg: Double, toleranceDeg: Double) ->
        TonightOverviewUseCase.CompositionEvaluation?,
    evaluationKey: Any?,
    orientationProvider: DeviceOrientationProvider,
    siteLatitudeDeg: Double,
    siteLongitudeDeg: Double,
    siteAltitudeMeters: Double,
    zone: ZoneId,
    subjectIsViewpoint: Boolean,
    modifier: Modifier = Modifier,
    subjectSelector: (@Composable () -> Unit)? = null,
    onStateChange: (
        bearingDeg: Double,
        toleranceDeg: Double,
        evaluation: TonightOverviewUseCase.CompositionEvaluation?,
    ) -> Unit = { _, _, _ -> },
) {
    var bearingDeg by remember { mutableStateOf(180) }
    // Whether the bearing is the user's own yet (ui-design §7.9,
    // 0.13.0): the field opens on a 180° placeholder, and until a
    // stepper step or a compass lock-in actually writes it, the
    // evaluation below is a SAMPLE — the value carries a marker
    // and the results carry a header saying so. The flag follows
    // the edit, never the value: a bearing the user really set
    // to 180° is not a sample. Session state like the bearing
    // itself (not persisted, not shared between entrances).
    var bearingEdited by remember { mutableStateOf(false) }
    var toleranceDeg by remember {
        mutableStateOf(CompositionWindow.DEFAULT_TOLERANCE_DEG)
    }
    var evaluation by remember {
        mutableStateOf<TonightOverviewUseCase.CompositionEvaluation?>(null)
    }
    LaunchedEffect(bearingDeg, toleranceDeg, evaluationKey) {
        delay(200)
        val result = withContext(Dispatchers.Default) {
            onEvaluate(bearingDeg.toDouble(), toleranceDeg)
        }
        evaluation = result
    }
    LaunchedEffect(bearingDeg, toleranceDeg, evaluation) {
        onStateChange(bearingDeg.toDouble(), toleranceDeg, evaluation)
    }

    val available = remember(orientationProvider) { orientationProvider.isAvailable() }
    var capturing by remember { mutableStateOf(false) }
    var captureSample by remember {
        mutableStateOf<DeviceOrientationProvider.OrientationSample?>(null)
    }
    // Capture subscribes only while it runs (the finder section's
    // discipline): locking in, cancelling or leaving the section
    // disposes the effect and cancels the subscription.
    DisposableEffect(
        capturing, orientationProvider, siteLatitudeDeg, siteLongitudeDeg,
    ) {
        if (!capturing) {
            onDispose { }
        } else {
            captureSample = null
            val subscription = orientationProvider.observe(
                DeviceOrientationProvider.OrientationRequest(
                    latitudeDeg = siteLatitudeDeg,
                    longitudeDeg = siteLongitudeDeg,
                    altitudeMeters = siteAltitudeMeters,
                    epochMillis = System.currentTimeMillis(),
                ),
            ) { sample -> captureSample = sample }
            onDispose { subscription.cancel() }
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle(stringResource(R.string.composition_title))
            Text(
                text = stringResource(R.string.composition_purpose),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    if (subjectIsViewpoint) {
                        R.string.composition_caliber_anchor
                    } else {
                        R.string.composition_caliber
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subjectIsViewpoint) {
                Text(
                    text = stringResource(R.string.composition_anchor_extends),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            subjectSelector?.invoke()

            // Foreground bearing: stepper and compass capture write
            // the SAME field (ui-design §7.9).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.composition_bearing_label),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                BearingStepButton(
                    label = "−",
                    description = stringResource(R.string.composition_step_down),
                ) { step ->
                    bearingDeg = (bearingDeg - step + 360) % 360
                    bearingEdited = true
                }
                Text(
                    text = "$bearingDeg°",
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(60.dp),
                )
                if (!bearingEdited) {
                    Text(
                        text = stringResource(R.string.composition_example_tag),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BearingStepButton(
                    label = "+",
                    description = stringResource(R.string.composition_step_up),
                ) { step ->
                    bearingDeg = (bearingDeg + step) % 360
                    bearingEdited = true
                }
            }
            if (!capturing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { capturing = true },
                        enabled = available,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.composition_capture))
                    }
                }
                if (!available) {
                    Text(
                        text = stringResource(R.string.composition_capture_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val heading = captureSample?.headingTrueDeg?.let { raw ->
                    ((raw % 360.0) + 360.0) % 360.0
                }
                Text(
                    text = stringResource(
                        R.string.composition_capture_live,
                        heading?.roundToInt()?.rem(360) ?: 0,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (captureSample?.accuracy ==
                    DeviceOrientationProvider.OrientationAccuracy.LOW ||
                    captureSample?.accuracy ==
                    DeviceOrientationProvider.OrientationAccuracy.UNRELIABLE
                ) {
                    Text(
                        text = stringResource(R.string.finder_calibration_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalStateColors.current.warn,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            heading?.let {
                                bearingDeg = it.roundToInt() % 360
                                bearingEdited = true
                            }
                            capturing = false
                        },
                        enabled = heading != null,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.composition_capture_lock))
                    }
                    TextButton(
                        onClick = { capturing = false },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.composition_capture_cancel))
                    }
                }
            }

            // Tolerance tiers (ui-design §7.9): the band's width is
            // the user's field call; the altitude band is not a
            // setting and stays fixed.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.composition_tolerance_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                CompositionWindow.TOLERANCE_TIERS_DEG.forEach { tier ->
                    FilterChip(
                        modifier = Modifier.heightIn(min = 48.dp),
                        selected = toleranceDeg == tier,
                        onClick = { toleranceDeg = tier },
                        label = { Text("±${tier.roundToInt()}°") },
                    )
                }
            }

            val current = evaluation
            if (current == null) {
                Text(text = "…", style = MaterialTheme.typography.bodyLarge)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!bearingEdited) {
                        Text(
                            text = stringResource(
                                R.string.composition_example_note, bearingDeg,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CompositionResults(evaluation = current, zone = zone)
                }
            }
        }
    }
}

/**
 * One bearing step button (the cursor time bar's discipline,
 * ui-design §7.5): a tap steps 1°, a hold switches to repeated 5°
 * steps until release. [onStep] receives the unsigned step size.
 */
@Composable
private fun BearingStepButton(
    label: String,
    description: String,
    onStep: (Int) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .border(
                1.dp, MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(12.dp),
            )
            .semantics {
                role = Role.Button
                contentDescription = description
                onClick(label = description) {
                    onStep(1)
                    true
                }
            }
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        onStep(1)
                        try {
                            withTimeout(450L) { tryAwaitRelease() }
                        } catch (e: PointerEventTimeoutCancellationException) {
                            var released = false
                            while (!released) {
                                onStep(5)
                                try {
                                    withTimeout(160L) { tryAwaitRelease() }
                                    released = true
                                } catch (e: PointerEventTimeoutCancellationException) {
                                    // Still held: keep stepping.
                                }
                            }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The evaluation's verdict: spans with their best moments, or the one diagnosis. */
@Composable
private fun CompositionResults(
    evaluation: TonightOverviewUseCase.CompositionEvaluation,
    zone: ZoneId,
) {
    val result = evaluation.result
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (result.segments.isNotEmpty()) {
            result.segments.forEach { segment ->
                CompositionSegmentLines(segment = segment, zone = zone)
            }
        } else {
            when (val diagnosis = result.diagnosis) {
                is CompositionWindow.Diagnosis.TwilightOnly -> {
                    Text(
                        text = stringResource(R.string.composition_twilight_note),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    diagnosis.segments.forEach { segment ->
                        CompositionSegmentLines(segment = segment, zone = zone)
                    }
                }
                is CompositionWindow.Diagnosis.AltitudeOutsideBand ->
                    Text(
                        text = compositionAltitudeDiagnosisText(diagnosis),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                is CompositionWindow.Diagnosis.NeverInAzimuthBand ->
                    Text(
                        text = stringResource(
                            R.string.composition_empty_never,
                            diagnosis.closestDiffDeg.roundToInt(),
                            formatTime(diagnosis.closestInstant, zone),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                null -> Unit
            }
        }
        CompositionMiniBar(evaluation = evaluation)
    }
}

/** The altitude diagnosis's wording: which bound the band fails, from its range. */
@Composable
private fun compositionAltitudeDiagnosisText(
    diagnosis: CompositionWindow.Diagnosis.AltitudeOutsideBand,
): String = when {
    diagnosis.azimuthBandMaxAltitudeDeg < CompositionWindow.MIN_ALTITUDE_DEG ->
        stringResource(
            R.string.composition_empty_below,
            diagnosis.azimuthBandMaxAltitudeDeg.roundToInt(),
        )
    diagnosis.azimuthBandMinAltitudeDeg > CompositionWindow.MAX_ALTITUDE_DEG ->
        stringResource(
            R.string.composition_empty_above,
            diagnosis.azimuthBandMinAltitudeDeg.roundToInt(),
        )
    else -> stringResource(
        R.string.composition_empty_altitude_range,
        diagnosis.azimuthBandMinAltitudeDeg.roundToInt(),
        diagnosis.azimuthBandMaxAltitudeDeg.roundToInt(),
    )
}

/** One span's three lines: bounds + duration, the best moment (gold), the altitude range. */
@Composable
private fun CompositionSegmentLines(
    segment: CompositionWindow.Segment,
    zone: ZoneId,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "${formatTime(segment.start, zone)} – ${formatTime(segment.end, zone)}" +
                " · " + compositionDurationText(
                    context, Duration.between(segment.start, segment.end).toMinutes(),
                ),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = when (val best = segment.best) {
                is CompositionWindow.BestMoment.Crossing -> stringResource(
                    R.string.composition_best_crossing,
                    formatTime(best.instant, zone),
                    best.altitudeDeg.roundToInt(),
                )
                is CompositionWindow.BestMoment.ClosestInSegment -> stringResource(
                    R.string.composition_best_closest,
                    formatTime(best.instant, zone),
                    best.residualDiffDeg.roundToInt(),
                    best.altitudeDeg.roundToInt(),
                )
                is CompositionWindow.BestMoment.CrossingOutsideNight -> stringResource(
                    if (best.edge == CompositionWindow.SegmentEdge.START) {
                        R.string.composition_best_outside_start
                    } else {
                        R.string.composition_best_outside_end
                    },
                    formatTime(best.crossingInstant, zone),
                    formatTime(best.endpoint, zone),
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = stringResource(
                R.string.composition_altitude_range,
                segment.minAltitudeDeg.roundToInt(),
                segment.maxAltitudeDeg.roundToInt(),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A span's duration in the house short form (hours + minutes, or minutes only). */
internal fun compositionDurationText(context: android.content.Context, minutes: Long): String {
    val safe = maxOf(0L, minutes)
    return if (safe >= 60) {
        context.getString(
            R.string.composition_duration_hm, (safe / 60).toInt(), (safe % 60).toInt(),
        )
    } else {
        context.getString(R.string.composition_duration_m, safe.toInt())
    }
}

/**
 * The section's own whole-night bar (ui-design §7.9): the
 * composition window's time presentation stays inside the section
 * and never joins the main timeline. The astronomical night is the
 * dark span; composition spans are gold (twilight-only spans in the
 * outline tone, since they sit outside the dark span by
 * definition); each span's best moment is a gold tick.
 */
@Composable
private fun CompositionMiniBar(evaluation: TonightOverviewUseCase.CompositionEvaluation) {
    val result = evaluation.result
    val twilightSegments =
        (result.diagnosis as? CompositionWindow.Diagnosis.TwilightOnly)?.segments
            ?: emptyList()
    val description = stringResource(R.string.composition_minibar_cd)
    val gold = MaterialTheme.colorScheme.primary
    val twilightColor = MaterialTheme.colorScheme.outline
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(4.dp),
            )
            .semantics { contentDescription = description },
    ) {
        val widthDp = maxWidth.value
        fun x(instant: Instant): Double =
            NightTimeline.fractionOf(instant, evaluation.axisStart, evaluation.axisEnd) *
                widthDp
        // One span on the bar: its fill plus the best moment's tick.
        val segmentBox: @Composable (CompositionWindow.Segment, Color) -> Unit =
            { segment, fill ->
                Box(
                    modifier = Modifier
                        .offset(x = x(segment.start).dp)
                        .width(maxOf(0.0, x(segment.end) - x(segment.start)).dp)
                        .fillMaxHeight()
                        .background(fill),
                )
                val tickAt = when (val best = segment.best) {
                    is CompositionWindow.BestMoment.Crossing -> best.instant
                    is CompositionWindow.BestMoment.ClosestInSegment -> best.instant
                    is CompositionWindow.BestMoment.CrossingOutsideNight -> best.endpoint
                }
                Box(
                    modifier = Modifier
                        .offset(x = (x(tickAt) - 1.0).dp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(gold),
                )
            }
        evaluation.nightSpan?.let { span ->
            Box(
                modifier = Modifier
                    .offset(x = x(span.start).dp)
                    .width(maxOf(0.0, x(span.end) - x(span.start)).dp)
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = 0.35f)),
            )
        }
        twilightSegments.forEach { segment ->
            segmentBox(segment, twilightColor.copy(alpha = 0.55f))
        }
        result.segments.forEach { segment ->
            segmentBox(segment, gold.copy(alpha = 0.55f))
        }
    }
}
