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
 * 0.15.0): the site (foreground bearing + foreground-top altitude)
 * is one grouped input, written by steppers or by a single compass
 * capture that takes both readings at once; the results render the
 * domain's verdict — the focal interval that includes the
 * foreground, the recommended focal, the shootable spans at that
 * focal and their best moments, or the one honest "does not fit"
 * category with its figures. The same pieces serve both entrances:
 * the detail dialog's framing section (behind its "with
 * foreground" switch, with the snapshot canvas) and the landscape
 * calculator's always-on group card ([LandscapeGroupCard] via
 * [CompositionSection]). A site is a field measurement — session
 * state only, never persisted, never shared between entrances.
 * Each field carries its own edited flag: until a writer has run,
 * the value it opens on is a sample and the results say so
 * (0.13.0's rule, extended to the two-field site).
 */
class LandscapeSiteState {
    var bearingDeg by mutableStateOf(180)
    var bearingEdited by mutableStateOf(false)
    var topDeg by mutableStateOf(0)
    var topEdited by mutableStateOf(false)
    var evaluation by mutableStateOf<TonightOverviewUseCase.LandscapeEvaluation?>(null)

    /** Write the bearing: wraps into [0, 360) and marks the field edited. */
    fun writeBearing(value: Int) {
        bearingDeg = ((value % 360) + 360) % 360
        bearingEdited = true
    }

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
    onEvaluate: (Double, Double) -> TonightOverviewUseCase.LandscapeEvaluation?,
    onStateChange: (Double, Double, TonightOverviewUseCase.LandscapeEvaluation?) -> Unit =
        { _, _, _ -> },
) {
    LaunchedEffect(evaluationKey) {
        state.evaluation = null
    }
    LaunchedEffect(state.bearingDeg, state.topDeg, evaluationKey) {
        delay(200)
        val result = withContext(Dispatchers.Default) {
            onEvaluate(state.bearingDeg.toDouble(), state.topDeg.toDouble())
        }
        state.evaluation = result
    }
    LaunchedEffect(state.bearingDeg, state.topDeg, state.evaluation) {
        onStateChange(state.bearingDeg.toDouble(), state.topDeg.toDouble(), state.evaluation)
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
 * The site inputs: the bearing row, the foreground-top row (same
 * shape; its stepper clamps instead of wrapping) and the capture
 * row. Capture subscribes only while it runs (the finder section's
 * discipline); its live line shows heading and pitch together, and
 * locking in writes both — heading rounded into the bearing, pitch
 * rounded and clamped into the top altitude — and raises both
 * edited flags at once. Cancelling writes neither.
 */
@Composable
fun LandscapeSiteInputs(
    state: LandscapeSiteState,
    orientationProvider: DeviceOrientationProvider,
    siteLatitudeDeg: Double,
    siteLongitudeDeg: Double,
    siteAltitudeMeters: Double,
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
        // Foreground bearing: stepper and compass capture write the
        // SAME field (ui-design §7.9).
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
            ) { step -> state.writeBearing(state.bearingDeg - step) }
            Text(
                text = "${state.bearingDeg}°",
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(60.dp),
            )
            if (!state.bearingEdited) {
                Text(
                    text = stringResource(R.string.composition_example_tag),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BearingStepButton(
                label = "+",
                description = stringResource(R.string.composition_step_up),
            ) { step -> state.writeBearing(state.bearingDeg + step) }
        }

        // Foreground-top altitude: same stepper shape, clamped.
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
                text = stringResource(
                    R.string.composition_capture_live2,
                    heading?.roundToInt()?.rem(360) ?: 0,
                    pitch?.roundToInt() ?: 0,
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
                        heading?.let { state.writeBearing(it.roundToInt()) }
                        pitch?.let { state.writeTop(it.roundToInt()) }
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
 * The outcome the verdict recommends: the feasible one with the
 * longer total window (landscape on a tie) — the domain's own
 * recommendation rule, re-derived here only to pick which outcome
 * the primary lines describe when the caller names no orientation.
 * Null when neither orientation is feasible.
 */
internal fun recommendedOutcome(
    result: LandscapeComposition.Result,
): LandscapeComposition.OrientationOutcome? {
    val landscape = result.landscape
    val portrait = result.portrait
    return when {
        landscape.recommendedFocalMm == null -> portrait.takeIf { it.recommendedFocalMm != null }
        portrait.recommendedFocalMm == null -> landscape
        landscape.totalWindowMinutes >= portrait.totalWindowMinutes -> landscape
        else -> portrait
    }
}

/**
 * The verdict's rendering (ui-design §7.9 §4.2), in row order: the
 * sample-site crown line (while either field is unedited), the
 * verdict's own lines — gold only on the focal conclusion and the
 * best-moment lines — the mini time bar, and the standing caliber
 * line. [primaryPortrait] names the orientation the primary lines
 * describe; the other orientation gets its one contrast line.
 */
@Composable
fun LandscapeResults(
    evaluation: TonightOverviewUseCase.LandscapeEvaluation,
    zone: ZoneId,
    subjectIsViewpoint: Boolean,
    primaryPortrait: Boolean,
    bearingEdited: Boolean = true,
    topEdited: Boolean = true,
) {
    val result = evaluation.result
    val context = LocalContext.current
    val primary = if (primaryPortrait) result.portrait else result.landscape
    val other = if (primaryPortrait) result.landscape else result.portrait
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!bearingEdited || !topEdited) {
            val tag = stringResource(R.string.composition_example_tag)
            val bearingBase = stringResource(
                R.string.composition_site_bearing_part,
                evaluation.bearingDeg.roundToInt(),
            )
            val topBase = stringResource(
                R.string.composition_site_top_part,
                evaluation.foregroundTopDeg.roundToInt(),
            )
            Text(
                text = stringResource(
                    R.string.composition_example_note,
                    if (bearingEdited) bearingBase else "$bearingBase（$tag）",
                    if (topEdited) topBase else "$topBase（$tag）",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (val verdict = result.verdict) {
            is LandscapeComposition.Verdict.Feasible,
            LandscapeComposition.Verdict.WindowTooShort,
            LandscapeComposition.Verdict.AllOutsideNight -> {
                val outsideNight = verdict == LandscapeComposition.Verdict.AllOutsideNight
                val segments = if (outsideNight) primary.geometricSegments else primary.segments
                val low = primary.feasibleLowMm
                val high = primary.feasibleHighMm
                val recommended = primary.recommendedFocalMm
                if (low != null && high != null && recommended != null) {
                    Text(
                        text = stringResource(
                            R.string.composition_focal_conclusion,
                            low.roundToInt(), high.roundToInt(), recommended.roundToInt(),
                            orientationWord(primary.portrait),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                // The other orientation's one contrast line.
                val otherLow = other.feasibleLowMm
                val otherHigh = other.feasibleHighMm
                val otherCap = other.capFocalMm
                Text(
                    text = when {
                        otherLow != null && otherHigh != null -> stringResource(
                            R.string.composition_other_feasible,
                            orientationWord(other.portrait),
                            otherLow.roundToInt(), otherHigh.roundToInt(),
                            compositionDurationText(
                                context, other.totalWindowMinutes.roundToInt().toLong(),
                            ),
                        )
                        otherCap == null -> stringResource(
                            R.string.composition_other_no_cap,
                            orientationWord(other.portrait),
                        )
                        else -> stringResource(
                            R.string.composition_other_capped,
                            orientationWord(other.portrait),
                            otherCap.roundToInt(),
                        )
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
                if (verdict == LandscapeComposition.Verdict.WindowTooShort) {
                    val longest = segments.maxByOrNull { it.durationMinutes }
                    Text(
                        text = stringResource(R.string.composition_too_short_title),
                        style = MaterialTheme.typography.bodyMedium,
                    )
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
            LandscapeComposition.Verdict.NeverCrossesBearing -> {
                Text(
                    text = stringResource(R.string.composition_never_title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.composition_never_detail,
                        formatTime(result.keyMoment.instant, zone),
                        result.keyMoment.residualDeg.roundToInt(),
                        LandscapeComposition.widestHalfWidthDeg.roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            is LandscapeComposition.Verdict.VerticalOverflow -> {
                Text(
                    text = stringResource(R.string.composition_overflow_title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.composition_overflow_detail,
                        verdict.subjectTopAltitudeDeg.roundToInt(),
                        verdict.reachableTopDeg.roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            is LandscapeComposition.Verdict.ShareConflict -> {
                Text(
                    text = stringResource(R.string.composition_conflict_title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.composition_conflict_detail,
                        result.landscape.capFocalMm?.roundToInt()?.toString() ?: "—",
                        result.portrait.capFocalMm?.roundToInt()?.toString() ?: "—",
                        result.adviceLowMm?.roundToInt() ?: 0,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                verdict.liveFact?.let { fact ->
                    val spans = fact.segments.joinToString(" / ") { segment ->
                        "${formatTime(segment.start, zone)}–${formatTime(segment.end, zone)}"
                    }
                    val spansText = if (fact.outsideNight) {
                        spans + " · " + stringResource(R.string.composition_twilight_note)
                    } else {
                        spans
                    }
                    Text(
                        text = stringResource(
                            R.string.composition_live_fact,
                            (fact.share * 100).roundToInt(),
                            orientationWord(fact.portrait),
                            fact.capFocalMm.roundToInt(),
                            spansText,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        LandscapeMiniBar(evaluation = evaluation, primaryPortrait = primaryPortrait)

        // The standing caliber line: the fixed placement rule is
        // part of the answer and stays on screen with it.
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
    }
}

/** One span's three lines: bounds + duration, the best moment (gold), the altitude range. */
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
            text = when (val best = segment.best) {
                is LandscapeComposition.BestMoment.Crossing -> stringResource(
                    R.string.composition_best_crossing,
                    formatTime(best.instant, zone),
                    best.altitudeDeg.roundToInt(),
                )
                is LandscapeComposition.BestMoment.ClosestInSegment -> stringResource(
                    R.string.composition_best_closest,
                    formatTime(best.instant, zone),
                    best.residualDiffDeg.roundToInt(),
                    best.altitudeDeg.roundToInt(),
                )
                is LandscapeComposition.BestMoment.CrossingOutsideNight -> stringResource(
                    if (best.edge == LandscapeComposition.SegmentEdge.START) {
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
    val outsideNight = result.verdict == LandscapeComposition.Verdict.AllOutsideNight
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
                val tickAt = when (val best = segment.best) {
                    is LandscapeComposition.BestMoment.Crossing -> best.instant
                    is LandscapeComposition.BestMoment.ClosestInSegment -> best.instant
                    is LandscapeComposition.BestMoment.CrossingOutsideNight -> best.endpoint
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
                    bearingEdited = state.bearingEdited,
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
    onEvaluate: (bearingDeg: Double, topDeg: Double) ->
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
        bearingDeg: Double,
        topDeg: Double,
        evaluation: TonightOverviewUseCase.LandscapeEvaluation?,
    ) -> Unit = { _, _, _ -> },
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
