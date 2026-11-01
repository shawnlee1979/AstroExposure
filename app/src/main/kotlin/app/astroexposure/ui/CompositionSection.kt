package app.astroexposure.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.astroexposure.R
import app.astroexposure.camera.CameraViewfinderPort
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
 * 0.16.0; input layer 0.17.0): the foreground-top altitude is the
 * one site input — the subject is the anchor and its azimuth is
 * derived, never entered — written by steppers, by direct typing
 * (the one entry that rejects out-of-domain values instead of
 * clamping them), or by a capture that writes its angle alone
 * (the viewfinder's camera-axis elevation, the compass's pitch);
 * the results render the domain's verdict — the focal length
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
        topDeg = value.coerceIn(TOP_MIN_DEG, TOP_MAX_DEG)
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

/** Orientation samples retained for the viewfinder hold's trailing mean. */
private const val SAMPLE_HISTORY = 24

/** The capture area's states (ui-design §7.9, 0.17.0). */
private enum class CaptureMode {
    NONE, EXPLAIN, VIEWFINDER, COMPASS, DENIED_ONCE, DENIED_FOREVER, UNAVAILABLE,
}

/**
 * The site input: the foreground-top row and the capture area
 * (ui-design §7.9, 0.17.0). The foreground top T stays the ONE
 * field; three entries write it — the steppers, direct typing, and
 * the captures — all through [LandscapeSiteState.writeTop]. Typed
 * input is the one entry that never clamps: its submit is the
 * four-way decision in [submitTopInput] (empty cancels, in-domain
 * writes, out-of-domain is rejected in the open with the text kept
 * and an error line, and a capture starting mid-edit preempts with
 * cancel semantics). The bounded entries (steppers, captures)
 * clamp inside [LandscapeSiteState.writeTop].
 *
 * Capture runs on two channels over the same single write: the
 * viewfinder (primary — the camera preview's centre marker aimed
 * at the foreground top; the hold writes the mean camera-axis
 * elevation γ over the trailing 600 ms) and the compass (fallback
 * — the top edge's pitch, with the aiming posture stated on
 * screen). Both channels show the current heading, and the
 * subject's azimuth when the caller supplies one, as reference
 * only: neither azimuth reading is written to any field (display
 * only). Captures subscribe only while one runs (the finder
 * section's discipline); cancelling writes nothing.
 */
@Composable
fun LandscapeSiteInputs(
    state: LandscapeSiteState,
    orientationProvider: DeviceOrientationProvider,
    siteLatitudeDeg: Double,
    siteLongitudeDeg: Double,
    siteAltitudeMeters: Double,
    subjectAzimuthDeg: Double? = null,
    viewfinderPort: CameraViewfinderPort? = null,
    redLight: Boolean = false,
) {
    val context = LocalContext.current
    val available = remember(orientationProvider) { orientationProvider.isAvailable() }
    var captureMode by remember { mutableStateOf(CaptureMode.NONE) }
    var samples by remember {
        mutableStateOf<List<DeviceOrientationProvider.OrientationSample>>(emptyList())
    }
    val capturing = captureMode != CaptureMode.NONE && captureMode != CaptureMode.EXPLAIN
    DisposableEffect(
        capturing, orientationProvider, siteLatitudeDeg, siteLongitudeDeg,
    ) {
        if (!capturing) {
            onDispose { }
        } else {
            samples = emptyList()
            val subscription = orientationProvider.observe(
                DeviceOrientationProvider.OrientationRequest(
                    latitudeDeg = siteLatitudeDeg,
                    longitudeDeg = siteLongitudeDeg,
                    altitudeMeters = siteAltitudeMeters,
                    epochMillis = System.currentTimeMillis(),
                ),
            ) { sample -> samples = (samples + sample).takeLast(SAMPLE_HISTORY) }
            onDispose { subscription.cancel() }
        }
    }
    val captureSample = samples.lastOrNull()

    // --- Typed input -----------------------------------------------------
    var editing by remember { mutableStateOf(false) }
    var buffer by remember { mutableStateOf("") }
    var typeError by remember { mutableStateOf<Int?>(null) }
    val focusRequester = remember { FocusRequester() }
    var fieldHadFocus by remember { mutableStateOf(false) }
    fun submitTyping() {
        when (val result = submitTopInput(buffer)) {
            TopSubmit.Cancel -> {
                editing = false
                typeError = null
            }
            is TopSubmit.Write -> {
                state.writeTop(result.valueDeg)
                editing = false
                typeError = null
            }
            is TopSubmit.Reject -> typeError = result.valueDeg
        }
    }
    fun startEditing() {
        buffer = state.topDeg.toString()
        typeError = null
        editing = true
    }
    /** A capture starting mid-edit preempts it with cancel semantics (§7.9). */
    fun preemptTyping() {
        if (editing) {
            editing = false
            typeError = null
        }
    }
    LaunchedEffect(editing) {
        if (editing) focusRequester.requestFocus()
    }

    // --- Camera permission ------------------------------------------------
    var cameraAskedBefore by remember { mutableStateOf(false) }
    fun permissionStep(): CameraPermissionStep = cameraPermissionStep(
        granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED,
        askedBefore = cameraAskedBefore,
        shouldShowRationale = (context as? Activity)?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(
                it, Manifest.permission.CAMERA,
            )
        } ?: false,
    )
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraAskedBefore = true
        captureMode = if (granted) {
            CaptureMode.VIEWFINDER
        } else if (permissionStep() == CameraPermissionStep.DENIED_PERMANENT) {
            CaptureMode.DENIED_FOREVER
        } else {
            CaptureMode.DENIED_ONCE
        }
    }
    fun startViewfinder() {
        preemptTyping()
        captureMode = when (permissionStep()) {
            CameraPermissionStep.GRANTED -> CaptureMode.VIEWFINDER
            CameraPermissionStep.EXPLAIN_FIRST -> CaptureMode.EXPLAIN
            CameraPermissionStep.DENIED_ASKABLE -> CaptureMode.DENIED_ONCE
            CameraPermissionStep.DENIED_PERMANENT -> CaptureMode.DENIED_FOREVER
        }
    }
    fun openAppSettings() {
        val details = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
        try {
            context.startActivity(details)
        } catch (e: Exception) {
            // The details screen is standard; a device without it
            // still has the general settings surface.
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                // No settings surface at all: the line above the
                // button already states the compass/typing fallback.
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Foreground-top altitude: steppers, typing and the
        // captures write the SAME single field (ui-design §7.9).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.composition_site_top_label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            if (editing) {
                OutlinedTextField(
                    value = buffer,
                    onValueChange = { raw ->
                        buffer = filterTopInput(raw)
                        typeError = null
                    },
                    modifier = Modifier
                        .width(84.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focus ->
                            if (focus.isFocused) {
                                fieldHadFocus = true
                            } else if (fieldHadFocus && editing) {
                                fieldHadFocus = false
                                submitTyping()
                            }
                        },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { submitTyping() },
                    ),
                )
                Text(
                    text = "°",
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                BearingStepButton(
                    label = "−",
                    description = stringResource(R.string.composition_step_down_top),
                ) { step -> state.writeTop(state.topDeg - step) }
                val valueDescription = stringResource(
                    R.string.composition_top_value_cd, state.topDeg,
                )
                Text(
                    text = "${state.topDeg}°",
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .width(60.dp)
                        .heightIn(min = 48.dp)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .semantics {
                            role = Role.Button
                            contentDescription = valueDescription
                            onClick(label = valueDescription) {
                                startEditing()
                                true
                            }
                        }
                        // The semantic onClick above serves assistive
                        // services only; the tap channel for fingers
                        // is this gesture detector — the same dual
                        // channel as BearingStepButton (0.17.1: the
                        // value was unreachable by touch and under
                        // the 48 dp floor without them).
                        .pointerInput(valueDescription) {
                            detectTapGestures(onTap = { startEditing() })
                        },
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
        }
        val rejected = typeError
        if (rejected != null) {
            Text(
                text = stringResource(
                    R.string.composition_top_range_error, rejected, state.topDeg,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (editing) {
            Text(
                text = stringResource(R.string.composition_top_edit_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // --- Capture area -----------------------------------------------
        when (captureMode) {
            CaptureMode.NONE -> {
                // The two entry buttons stack full-width, one per
                // row: both labels are long (each carries its aiming
                // instruction), and side by side in one Row the
                // second button was squeezed to a sliver whose text
                // was clipped away entirely (0.17.1, on-device
                // report; pinned by LandscapeCaptureLayoutSourceTest).
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (viewfinderPort != null) {
                        OutlinedButton(
                            onClick = { startViewfinder() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.composition_viewfinder_capture))
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            preemptTyping()
                            captureMode = CaptureMode.COMPASS
                        },
                        enabled = available,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
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
            }
            CaptureMode.EXPLAIN -> {
                Text(
                    text = stringResource(R.string.composition_camera_explain),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Stacked full-width like the entry row: the three
                // labels together (notably the English "Use the
                // compass capture instead") exceed one row's width
                // and would squeeze the same way (0.17.1 audit).
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.composition_camera_continue))
                    }
                    OutlinedButton(
                        onClick = { captureMode = CaptureMode.COMPASS },
                        enabled = available,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.composition_camera_use_compass))
                    }
                    TextButton(
                        onClick = { captureMode = CaptureMode.NONE },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.composition_capture_cancel))
                    }
                }
            }
            CaptureMode.VIEWFINDER -> {
                val port = viewfinderPort
                if (port == null) {
                    // Unreachable by construction (the entry button
                    // exists only when a port is wired); the compass
                    // channel stands in rather than dead air.
                    CompassCaptureBlock(
                        state = state,
                        sample = captureSample,
                        subjectAzimuthDeg = subjectAzimuthDeg,
                        onDone = { captureMode = CaptureMode.NONE },
                    )
                } else {
                    ViewfinderCaptureBlock(
                        port = port,
                        state = state,
                        samples = samples,
                        subjectAzimuthDeg = subjectAzimuthDeg,
                        redLight = redLight,
                        onFailure = { captureMode = CaptureMode.UNAVAILABLE },
                        onDone = { captureMode = CaptureMode.NONE },
                    )
                }
            }
            CaptureMode.COMPASS -> CompassCaptureBlock(
                state = state,
                sample = captureSample,
                subjectAzimuthDeg = subjectAzimuthDeg,
                onDone = { captureMode = CaptureMode.NONE },
            )
            CaptureMode.DENIED_ONCE -> {
                Text(
                    text = stringResource(R.string.composition_camera_denied),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.composition_camera_retry))
                    }
                    TextButton(
                        onClick = { captureMode = CaptureMode.NONE },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.composition_capture_cancel))
                    }
                }
                CompassCaptureBlock(
                    state = state,
                    sample = captureSample,
                    subjectAzimuthDeg = subjectAzimuthDeg,
                    onDone = { captureMode = CaptureMode.NONE },
                )
            }
            CaptureMode.DENIED_FOREVER -> {
                Text(
                    text = stringResource(R.string.composition_camera_denied_forever),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { openAppSettings() },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.composition_camera_open_settings))
                    }
                    TextButton(
                        onClick = { captureMode = CaptureMode.NONE },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.composition_capture_cancel))
                    }
                }
                CompassCaptureBlock(
                    state = state,
                    sample = captureSample,
                    subjectAzimuthDeg = subjectAzimuthDeg,
                    onDone = { captureMode = CaptureMode.NONE },
                )
            }
            CaptureMode.UNAVAILABLE -> {
                Text(
                    text = stringResource(R.string.composition_camera_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                )
                CompassCaptureBlock(
                    state = state,
                    sample = captureSample,
                    subjectAzimuthDeg = subjectAzimuthDeg,
                    onDone = { captureMode = CaptureMode.NONE },
                )
            }
        }
    }
}

/**
 * The compass (sensor) capture block (ui-design §7.9): the aiming
 * posture is stated on screen — along the phone's top edge, phone
 * held low — because the reading is the top edge's pitch and the
 * posture IS the semantics. The live line leads with the pitch and
 * shows the heading (and the subject's azimuth when supplied) as
 * reference only. Holding writes the pitch alone, rounded; the
 * clamp into the field's domain lives in
 * [LandscapeSiteState.writeTop]. Cancelling writes nothing.
 */
@Composable
private fun CompassCaptureBlock(
    state: LandscapeSiteState,
    sample: DeviceOrientationProvider.OrientationSample?,
    subjectAzimuthDeg: Double?,
    onDone: () -> Unit,
) {
    val heading = sample?.headingTrueDeg?.let { raw ->
        ((raw % 360.0) + 360.0) % 360.0
    }
    val pitch = sample?.pitchDeg
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.composition_compass_posture),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        if (sample?.accuracy == DeviceOrientationProvider.OrientationAccuracy.LOW ||
            sample?.accuracy == DeviceOrientationProvider.OrientationAccuracy.UNRELIABLE
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
                    onDone()
                },
                enabled = pitch != null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    if (pitch != null) {
                        stringResource(
                            R.string.composition_capture_lock_pitch,
                            pitch.roundToInt(),
                        )
                    } else {
                        stringResource(R.string.composition_capture_lock)
                    },
                )
            }
            TextButton(
                onClick = { onDone() },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.composition_capture_cancel))
            }
        }
    }
}

/**
 * The viewfinder capture block (ui-design §7.9, 0.17.0): the live
 * preview (the port's surface, decorative to screen readers) with
 * the aiming overlay — a reference line through the preview's
 * vertical centre and a thickened centre marker. The centre marker
 * is the aiming point: only the centre ray's elevation is
 * identically the camera-axis elevation γ, hold-free (the line's
 * ends sit at γ only when the phone is unrolled, so they are drawn
 * as a reference, never as the aiming feature). The readout under
 * the preview leads with γ; the hold writes the trailing-600 ms
 * mean of γ, rounded, and is disabled until a sample exists in the
 * window. Heading and subject azimuth are reference display only.
 */
@Composable
private fun ViewfinderCaptureBlock(
    port: CameraViewfinderPort,
    state: LandscapeSiteState,
    samples: List<DeviceOrientationProvider.OrientationSample>,
    subjectAzimuthDeg: Double?,
    redLight: Boolean,
    onFailure: (CameraViewfinderPort.Failure) -> Unit,
    onDone: () -> Unit,
) {
    val sample = samples.lastOrNull()
    val mean = meanCameraAxisDeg(samples)
    val heading = sample?.headingTrueDeg?.let { raw ->
        ((raw % 360.0) + 360.0) % 360.0
    }
    val axisNow = sample?.cameraAxisElevationDeg
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.composition_viewfinder_title),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.composition_viewfinder_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clearAndSetSemantics { },
        ) {
            port.ViewfinderView(
                redLight = redLight,
                onFailure = onFailure,
                modifier = Modifier.fillMaxSize(),
            )
            // The reference line through the vertical centre and
            // its thickened centre marker — the aiming point.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.Center)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                    ),
            )
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .height(10.dp)
                    .align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Text(
            text = if (subjectAzimuthDeg == null) {
                stringResource(
                    R.string.composition_capture_live_axis,
                    axisNow?.roundToInt() ?: 0,
                    heading?.roundToInt()?.rem(360) ?: 0,
                )
            } else {
                stringResource(
                    R.string.composition_capture_live_axis_subject,
                    axisNow?.roundToInt() ?: 0,
                    heading?.roundToInt()?.rem(360) ?: 0,
                    ((subjectAzimuthDeg.roundToInt() % 360) + 360) % 360,
                )
            },
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        if (sample?.accuracy == DeviceOrientationProvider.OrientationAccuracy.LOW ||
            sample?.accuracy == DeviceOrientationProvider.OrientationAccuracy.UNRELIABLE
        ) {
            Text(
                text = stringResource(R.string.finder_calibration_hint),
                style = MaterialTheme.typography.bodySmall,
                color = LocalStateColors.current.warn,
            )
        }
        Text(
            text = stringResource(R.string.composition_viewfinder_guide),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    mean?.let { state.writeTop(it.roundToInt()) }
                    onDone()
                },
                enabled = mean != null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    if (mean != null) {
                        stringResource(
                            R.string.composition_capture_lock_axis,
                            mean.roundToInt(),
                        )
                    } else {
                        stringResource(R.string.composition_capture_lock)
                    },
                )
            }
            TextButton(
                onClick = { onDone() },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.composition_capture_cancel))
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
    viewfinderPort: CameraViewfinderPort? = null,
    redLight: Boolean = false,
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
                viewfinderPort = viewfinderPort,
                redLight = redLight,
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
    viewfinderPort: CameraViewfinderPort? = null,
    redLight: Boolean = false,
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
        viewfinderPort = viewfinderPort,
        redLight = redLight,
    )
}
