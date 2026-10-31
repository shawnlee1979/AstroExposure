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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import app.astroexposure.domain.timeline.LandscapeComposition
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
 * The landscape composition component library (ui-design §7.9,
 * 0.16.0): the foreground-top altitude is the one site input — the
 * subject is the anchor and its azimuth is derived, never entered —
 * written by steppers or by a compass capture that writes the pitch
 * alone; the results render the domain's verdict — the focal length
 * derived from the foreground top and the subject's span, the
 * shootable spans at that focal and their best moments, or the one
 * honest "does not fit" category with its figures. The same pieces
 * serve both entrances: the detail dialog's framing section (behind
 * its "with foreground" switch, with the snapshot canvas) and the
 * landscape calculator's always-on group card ([LandscapeGroupCard]
 * via [CompositionSection]). The site value is a field measurement
 * — session state only, never persisted, never shared between
 * entrances. Until a writer has run, the value it opens on is a
 * sample and the results say so (0.13.0's rule).
 */
class LandscapeSiteState {
    var topDeg by mutableStateOf(0)
    var topEdited by mutableStateOf(false)
    var evaluation by mutableStateOf<TonightOverviewUseCase.LandscapeEvaluation?>(null)

    /** Write the foreground-top altitude: clamps into [-20, 80] and marks edited. */
    fun writeTop(value: Int) {
        topDeg = value.coerceIn(-20, 80)
        topEdited = true
    }
}

/** The remembered site state for one entrance instance. */
@Composable
fun rememberLandscapeSiteState(): LandscapeSiteState = remember { LandscapeSiteState() }

/**
 * The evaluation driver: debounced like the cursor's (200 ms, on
 * the default dispatcher), reporting every settled state through
 * [onStateChange]. When [evaluationKey] changes the previous
 * subject's result is cleared BEFORE the new evaluation runs, so a
 * switched subject never shows the old subject's verdict while the
 * new one computes.
 */
@Composable
fun LandscapeEvaluationEffect(
    state: LandscapeSiteState,
    evaluationKey: Any?,
    onEvaluate: (Double) -> TonightOverviewUseCase.LandscapeEvaluation?,
    onStateChange: (Double, TonightOverviewUseCase.LandscapeEvaluation?) -> Unit =
        { _, _ -> },
) {
    LaunchedEffect(evaluationKey) {
        state.evaluation = null
    }
    LaunchedEffect(state.topDeg, evaluationKey) {
        delay(200)
        val result = withContext(Dispatchers.Default) {
            onEvaluate(state.topDeg.toDouble())
        }
        state.evaluation = result
    }
    LaunchedEffect(state.topDeg, state.evaluation) {
        onStateChange(state.topDeg.toDouble(), state.evaluation)
    }
}

/** The "with foreground" switch row shared by the dialog entrances (≥48dp). */
@Composable
fun LandscapeSwitchRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.composition_switch),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(R.string.composition_switch_purpose),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The site input: the foreground-top row (stepper clamps instead
 * of wrapping) and the capture row. Capture subscribes only while
 * it runs (the finder section's discipline); its live line leads
 * with the pitch, and shows the current heading — and the subject's
 * azimuth when the caller supplies one — as reference only:
 * neither azimuth reading is written to any field (display only,
 * ui-design §7.9, 0.16.0). Locking in writes the pitch alone,
 * rounded (the clamp into the field's domain lives in
 * [LandscapeSiteState.writeTop]), and needs a pitch reading only.
 * Cancelling writes nothing.
 */
@Composable
fun LandscapeSiteInputs(
    state: LandscapeSiteState,
    orientationProvider: DeviceOrientationProvider,
    siteLatitudeDeg: Double,
    siteLongitudeDeg: Double,
    siteAltitudeMeters: Double,
    subjectAzimuthDeg: Double? = null,
) {
    val available = remember(orientationProvider) { orientationProvider.isAvailable() }
    var capturing by remember { mutableStateOf(false) }
    var captureSample by remember {
        mutableStateOf<DeviceOrientationProvider.OrientationSample?>(null)
    }
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

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Foreground-top altitude: stepper and compass capture
        // write the SAME single field (ui-design §7.9).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.composition_site_top_label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            BearingStepButton(
                label = "−",
                description = stringResource(R.string.composition_step_down_top),
            ) { step -> state.writeTop(state.topDeg - step) }
            Text(
                text = "${state.topDeg}°",
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(60.dp),
            )
            if (!state.topEdited) {
                Text(
                    text = stringResource(R.string.composition_example_tag),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BearingStepButton(
                label = "+",
                description = stringResource(R.string.composition_step_up_top),
            ) { step -> state.writeTop(state.topDeg + step) }
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
            val pitch = captureSample?.pitchDeg
            Text(
                text = if (subjectAzimuthDeg == null) {
                    stringResource(
                        R.string.composition_capture_live_pitch,
                        pitch?.roundToInt() ?: 0,
                        heading?.roundToInt()?.rem(360) ?: 0,
                    )
                } else {
                    stringResource(
                        R.string.composition_capture_live_pitch_subject,
                        pitch?.roundToInt() ?: 0,
                        heading?.roundToInt()?.rem(360) ?: 0,
                        ((subjectAzimuthDeg.roundToInt() % 360) + 360) % 360,
                    )
                },
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
                        pitch?.let { state.writeTop(it.roundToInt()) }
                        capturing = false
                    },
                    enabled = pitch != null,
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
    }
}

/**
 * One step button (the cursor time bar's discipline, ui-design
 * §7.5): a tap steps 1°, a hold switches to repeated 5° steps until
 * release. [onStep] receives the unsigned step size.
 */
@Composable
internal fun BearingStepButton(
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

/** The orientation word for an outcome ("landscape" / "portrait"). */
@Composable
internal fun orientationWord(portrait: Boolean): String = stringResource(
    if (portrait) R.string.framing_portrait else R.string.framing_landscape,
)

/**
 * The outcome the primary lines describe when the caller names no
 * orientation: the domain verdict's own recommended orientation —
 * every feasible-family verdict carries it (§7.9's rule lives in
 * the domain alone; this function only reads it). Null for the two
 * blocking verdicts, where neither orientation derived a focal.
 */
internal fun recommendedOutcome(
    result: LandscapeComposition.Result,
): LandscapeComposition.OrientationOutcome? {
    val recommendedPortrait = when (val verdict = result.verdict) {
        is LandscapeComposition.Verdict.Feasible -> verdict.recommendedPortrait
        is LandscapeComposition.Verdict.WindowTooShort -> verdict.recommendedPortrait
        is LandscapeComposition.Verdict.AllOutsideNight -> verdict.recommendedPortrait
        is LandscapeComposition.Verdict.NeverAboveForegroundTop,
        is LandscapeComposition.Verdict.SpanExceedsWidestFrame -> return null
    }
    return if (recommendedPortrait) result.portrait else result.landscape
}

/**
 * One orientation's span short sentence for an orientation with no
 * derived focal (ui-design §7.9, 0.16.0): with the closed form that
 * names the focal the span would need when it undercuts the widest
 * focal, plain otherwise. Shared by the conclusion position (a
 * primary orientation without a derivation) and the contrast line.
 */
@Composable
private fun otherSpanText(outcome: LandscapeComposition.OrientationOutcome): String =
    if (outcome.closedFormFocalMm < LandscapeComposition.MIN_FOCAL_MM) {
        stringResource(
            R.string.composition_other_span,
            orientationWord(outcome.portrait),
            outcome.closedFormFocalMm.roundToInt(),
        )
    } else {
        stringResource(
            R.string.composition_other_span_plain,
            orientationWord(outcome.portrait),
        )
    }

/**
 * The verdict's rendering (ui-design §7.9, 0.16.0), in the row
 * order §4.2 fixes: the sample crown line (while the foreground
 * top is unedited), the derived-focal conclusion — gold only
 * there and on the best-moment lines — its derivation note, the
 * share line, the other orientation's contrast line, the spans
 * with the mini time bar, and the standing caliber line. The two
 * blocking verdicts state their category and figures in the
 * conclusion's place and carry no focal, share, contrast or span
 * lines. [primaryPortrait] names the orientation the primary
 * lines describe; the other orientation gets its one contrast
 * line.
 */
@Composable
fun LandscapeResults(
    evaluation: TonightOverviewUseCase.LandscapeEvaluation,
    zone: ZoneId,
    subjectIsViewpoint: Boolean,
    primaryPortrait: Boolean,
    topEdited: Boolean = true,
) {
    val result = evaluation.result
    val context = LocalContext.current
    val primary = if (primaryPortrait) result.portrait else result.landscape
    val other = if (primaryPortrait) result.landscape else result.portrait
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!topEdited) {
            Text(
                text = stringResource(
                    R.string.composition_example_note,
                    evaluation.foregroundTopDeg.roundToInt(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (val verdict = result.verdict) {
            is LandscapeComposition.Verdict.Feasible,
            is LandscapeComposition.Verdict.WindowTooShort,
            is LandscapeComposition.Verdict.AllOutsideNight -> {
                val outsideNight = verdict is LandscapeComposition.Verdict.AllOutsideNight
                val segments = if (outsideNight) primary.geometricSegments else primary.segments
                val derived = primary.derivedFocalMm
                if (derived != null) {
                    Text(
                        text = stringResource(
                            R.string.composition_derived_conclusion,
                            derived.roundToInt(),
                            orientationWord(primary.portrait),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                    )
                } else {
                    Text(
                        text = otherSpanText(primary),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.composition_derivation_note,
                        evaluation.foregroundTopDeg.roundToInt(),
                        formatTime(result.anchor.instant, zone),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (primary.focalCappedAtMax) {
                    Text(
                        text = stringResource(R.string.composition_focal_capped_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                primary.share?.let { share ->
                    Text(
                        text = stringResource(
                            if (share >= 1.0 / 3.0) {
                                R.string.composition_share_line
                            } else {
                                R.string.composition_share_low_line
                            },
                            (share * 100).roundToInt(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                // The other orientation's one contrast line.
                val otherDerived = other.derivedFocalMm
                val otherShare = other.share
                Text(
                    text = when {
                        otherDerived != null && otherShare != null -> stringResource(
                            R.string.composition_other_derived,
                            orientationWord(other.portrait),
                            otherDerived.roundToInt(),
                            compositionDurationText(
                                context, other.totalWindowMinutes.roundToInt().toLong(),
                            ),
                            (otherShare * 100).roundToInt(),
                        )
                        otherDerived != null -> stringResource(
                            R.string.composition_other_derived_no_share,
                            orientationWord(other.portrait),
                            otherDerived.roundToInt(),
                            compositionDurationText(
                                context, other.totalWindowMinutes.roundToInt().toLong(),
                            ),
                        )
                        else -> otherSpanText(other)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (outsideNight) {
                    Text(
                        text = stringResource(R.string.composition_twilight_note),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                segments.forEach { segment ->
                    LandscapeSegmentLines(segment = segment, zone = zone)
                }
                if (segments.isNotEmpty() && segments.none { segment ->
                        !result.anchor.instant.isBefore(segment.start) &&
                            !result.anchor.instant.isAfter(segment.end)
                    }
                ) {
                    Text(
                        text = stringResource(
                            R.string.composition_anchor_outside_note,
                            formatTime(result.anchor.instant, zone),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (verdict is LandscapeComposition.Verdict.WindowTooShort) {
                    val longest = segments.maxByOrNull { it.durationMinutes }
                    if (longest != null) {
                        Text(
                            text = stringResource(
                                R.string.composition_too_short_detail,
                                formatTime(longest.start, zone),
                                formatTime(longest.end, zone),
                                longest.durationMinutes.roundToInt(),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (result.lowAltitudeHint) {
                    Text(
                        text = stringResource(R.string.composition_low_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is LandscapeComposition.Verdict.NeverAboveForegroundTop -> {
                Text(
                    text = stringResource(R.string.composition_never_top_title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.composition_never_top_detail,
                        formatTime(verdict.atInstant, zone),
                        verdict.maxBottomAltitudeDeg.roundToInt(),
                        verdict.foregroundTopDeg.roundToInt(),
                        (verdict.foregroundTopDeg - verdict.maxBottomAltitudeDeg)
                            .roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.composition_never_top_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is LandscapeComposition.Verdict.SpanExceedsWidestFrame -> {
                Text(
                    text = stringResource(R.string.composition_span_title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // A null required field means the span at the
                // anchor is not positive: the sentence states that
                // instead of printing a number (0.17.0).
                val required = verdict.requiredVerticalFovDeg
                Text(
                    text = if (required != null) {
                        stringResource(
                            R.string.composition_span_detail,
                            formatTime(verdict.anchorInstant, zone),
                            required.roundToInt(),
                            LandscapeComposition.verticalFovDeg(
                                LandscapeComposition.MIN_FOCAL_MM, portrait = false,
                            ).roundToInt(),
                            LandscapeComposition.verticalFovDeg(
                                LandscapeComposition.MIN_FOCAL_MM, portrait = true,
                            ).roundToInt(),
                        )
                    } else {
                        stringResource(
                            R.string.composition_span_detail_nonpositive,
                            formatTime(verdict.anchorInstant, zone),
                            LandscapeComposition.verticalFovDeg(
                                LandscapeComposition.MIN_FOCAL_MM, portrait = false,
                            ).roundToInt(),
                            LandscapeComposition.verticalFovDeg(
                                LandscapeComposition.MIN_FOCAL_MM, portrait = true,
                            ).roundToInt(),
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        LandscapeMiniBar(evaluation = evaluation, primaryPortrait = primaryPortrait)

        // The standing caliber line: the placement rule and the
        // representative-value reading of the foreground top are
        // part of the answer and stay on screen with it. The
        // never-above verdict carries the short form — no frame
        // was ever placed for it.
        val noFrame = result.verdict is
            LandscapeComposition.Verdict.NeverAboveForegroundTop
        Text(
            text = stringResource(
                when {
                    noFrame && subjectIsViewpoint ->
                        R.string.composition_caliber_no_frame_anchor
                    noFrame -> R.string.composition_caliber_no_frame
                    subjectIsViewpoint -> R.string.composition_caliber_anchor
                    else -> R.string.composition_caliber
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
    }
}

/** One span's two lines: bounds + duration, and its best moment (gold). */
@Composable
private fun LandscapeSegmentLines(
    segment: LandscapeComposition.Segment,
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
            text = stringResource(
                R.string.composition_best,
                formatTime(segment.best.instant, zone),
                segment.best.centreAltitudeDeg.roundToInt(),
            ),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
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
 * The group's own whole-night bar (ui-design §7.9): the landscape
 * presentation stays inside the group and never joins the main
 * timeline. The astronomical night is the dark span; the primary
 * orientation's spans are gold — for the all-outside-night verdict
 * the geometric spans are drawn in the outline tone instead, since
 * they sit outside the dark span by definition; each span's best
 * moment is a gold tick.
 */
@Composable
private fun LandscapeMiniBar(
    evaluation: TonightOverviewUseCase.LandscapeEvaluation,
    primaryPortrait: Boolean,
) {
    val result = evaluation.result
    val primary = if (primaryPortrait) result.portrait else result.landscape
    val outsideNight = result.verdict is LandscapeComposition.Verdict.AllOutsideNight
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
        val segmentBox: @Composable (LandscapeComposition.Segment, Color) -> Unit =
            { segment, fill ->
                Box(
                    modifier = Modifier
                        .offset(x = x(segment.start).dp)
                        .width(maxOf(0.0, x(segment.end) - x(segment.start)).dp)
                        .fillMaxHeight()
                        .background(fill),
                )
                val tickAt = segment.best.instant
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
        if (outsideNight) {
            primary.geometricSegments.forEach { segment ->
                segmentBox(segment, twilightColor.copy(alpha = 0.55f))
            }
        } else {
            primary.segments.forEach { segment ->
                segmentBox(segment, gold.copy(alpha = 0.55f))
            }
        }
    }
}

/**
 * The always-on landscape group card (the calculator entrance):
 * title, purpose, the caller's subject selector, the site inputs
 * and the results for [state]'s evaluation. [primaryPortrait]
 * overrides the primary orientation; left null the results follow
 * the verdict's recommended orientation.
 */
@Composable
fun LandscapeGroupCard(
    state: LandscapeSiteState,
    orientationProvider: DeviceOrientationProvider,
    siteLatitudeDeg: Double,
    siteLongitudeDeg: Double,
    siteAltitudeMeters: Double,
    zone: ZoneId,
    subjectIsViewpoint: Boolean,
    modifier: Modifier = Modifier,
    subjectSelector: (@Composable () -> Unit)? = null,
    primaryPortrait: Boolean? = null,
) {
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
            subjectSelector?.invoke()
            LandscapeSiteInputs(
                state = state,
                orientationProvider = orientationProvider,
                siteLatitudeDeg = siteLatitudeDeg,
                siteLongitudeDeg = siteLongitudeDeg,
                siteAltitudeMeters = siteAltitudeMeters,
            )
            val current = state.evaluation
            if (current == null) {
                Text(text = "…", style = MaterialTheme.typography.bodyLarge)
            } else {
                LandscapeResults(
                    evaluation = current,
                    zone = zone,
                    subjectIsViewpoint = subjectIsViewpoint,
                    primaryPortrait = primaryPortrait
                        ?: recommendedOutcome(current.result)?.portrait
                        ?: false,
                    topEdited = state.topEdited,
                )
            }
        }
    }
}

/**
 * The landscape calculator's section (ui-design §7.9): owns its
 * site state, drives its evaluation and renders
 * [LandscapeGroupCard]; [onStateChange] hands the settled site and
 * evaluation to the caller (the plan text rides on it).
 */
@Composable
fun CompositionSection(
    onEvaluate: (topDeg: Double) ->
        TonightOverviewUseCase.LandscapeEvaluation?,
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
        topDeg: Double,
        evaluation: TonightOverviewUseCase.LandscapeEvaluation?,
    ) -> Unit = { _, _ -> },
) {
    val state = rememberLandscapeSiteState()
    LandscapeEvaluationEffect(
        state = state,
        evaluationKey = evaluationKey,
        onEvaluate = onEvaluate,
        onStateChange = onStateChange,
    )
    LandscapeGroupCard(
        state = state,
        orientationProvider = orientationProvider,
        siteLatitudeDeg = siteLatitudeDeg,
        siteLongitudeDeg = siteLongitudeDeg,
        siteAltitudeMeters = siteAltitudeMeters,
        zone = zone,
        subjectIsViewpoint = subjectIsViewpoint,
        modifier = modifier,
        subjectSelector = subjectSelector,
    )
}
