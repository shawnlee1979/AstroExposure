package app.astroexposure.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.astroexposure.R
import app.astroexposure.domain.orientation.AlignmentState
import app.astroexposure.domain.orientation.AltitudeCue
import app.astroexposure.domain.orientation.AzimuthCue
import app.astroexposure.domain.orientation.DeviceOrientationProvider
import app.astroexposure.domain.orientation.FinderAlignment
import app.astroexposure.domain.orientation.FinderThresholds
import app.astroexposure.domain.orientation.HoldingPosture
import app.astroexposure.domain.orientation.advanceAlignment
import app.astroexposure.domain.orientation.finderReadout
import app.astroexposure.domain.orientation.holdingPosture
import app.astroexposure.domain.orientation.pitchDeltaDeg
import app.astroexposure.domain.orientation.signedAzimuthDeltaDeg
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The target detail dialog's pointing section (ui-design §7.5,
 * 0.8.0): a compass dial plus a vertical altitude gauge, driven by
 * the [DeviceOrientationProvider] port. The section owns the sensor
 * subscription — it subscribes when it enters the composition (the
 * dialog is open and the detail is on screen) and unsubscribes when
 * it leaves, so sampling never outlives the dialog.
 *
 * All judgement lives in the domain (delta functions, the alignment
 * hysteresis, posture, readout assembly); this file only renders
 * what the readout says. The graphics are never the only channel:
 * the two delta lines under the dial carry the same information as
 * text, and the card's accessibility summary speaks them.
 */
@Composable
fun FinderSection(
    provider: DeviceOrientationProvider,
    siteLatitudeDeg: Double,
    siteLongitudeDeg: Double,
    siteAltitudeMeters: Double,
    targetAltDeg: Double,
    targetAzDeg: Double,
    horizonRise: Instant?,
    horizonSet: Instant?,
    cursor: Instant,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val available = remember(provider) { provider.isAvailable() }
    if (!available) {
        // No orientation hardware: the section degrades to the two
        // static target values and says why there is no live compass
        // — no blank card, no pretend dial (design: honest states).
        Card(modifier = modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionTitle(stringResource(R.string.finder_section_title))
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    BigStat(
                        label = stringResource(R.string.detail_grid_azimuth),
                        value = "${targetAzDeg.roundToInt()}°",
                    )
                    BigStat(
                        label = stringResource(R.string.detail_grid_altitude),
                        value = "${targetAltDeg.roundToInt()}°",
                    )
                }
                Text(
                    text = stringResource(R.string.finder_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    var sample by remember { mutableStateOf<DeviceOrientationProvider.OrientationSample?>(null) }
    var alignmentState by remember { mutableStateOf(AlignmentState()) }
    var calibrationDismissedAt by remember {
        mutableStateOf<DeviceOrientationProvider.OrientationAccuracy?>(null)
    }
    // One-second ticker: drives the no-reading judgement when the
    // sample stream stalls mid-dialog (frozen samples must not pose
    // as live ones).
    var tickSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            tickSeconds++
        }
    }
    val noReading = run {
        val s = sample
        if (s == null) {
            tickSeconds >= NO_READING_AFTER_SECONDS
        } else {
            SystemClock.elapsedRealtime() - s.elapsedMillis > NO_READING_AFTER_SECONDS * 1_000
        }
    }

    // The subscription is bound to this section's lifetime: opening
    // the dialog composes it, closing disposes it and unsubscribes.
    DisposableEffect(provider, siteLatitudeDeg, siteLongitudeDeg) {
        val subscription = provider.observe(
            DeviceOrientationProvider.OrientationRequest(
                latitudeDeg = siteLatitudeDeg,
                longitudeDeg = siteLongitudeDeg,
                altitudeMeters = siteAltitudeMeters,
                epochMillis = System.currentTimeMillis(),
            ),
        ) { newSample -> sample = newSample }
        onDispose { subscription.cancel() }
    }

    // Advance the alignment hysteresis on every fresh sample. The
    // altitude axis is not evaluated while the target is below the
    // horizon (nothing to align to yet) or the phone is flat (the
    // pitch reading is meaningless in that posture).
    val haptic = LocalHapticFeedback.current
    var lastAlignedHapticMillis by remember { mutableStateOf(0L) }
    LaunchedEffect(sample) {
        val s = sample ?: return@LaunchedEffect
        val posture = holdingPosture(s.screenNormalTiltDeg)
        val azimuthDelta = signedAzimuthDeltaDeg(s.headingTrueDeg, targetAzDeg)
        val altitudeDelta = if (targetAltDeg < 0.0 || posture == HoldingPosture.FLAT) {
            null
        } else {
            pitchDeltaDeg(s.pitchDeg, targetAltDeg)
        }
        val previous = alignmentState
        val next = advanceAlignment(previous, azimuthDelta, altitudeDelta)
        alignmentState = next
        if (next.combined == FinderAlignment.ALIGNED &&
            previous.combined != FinderAlignment.ALIGNED
        ) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAlignedHapticMillis >= ALIGNED_HAPTIC_THROTTLE_MS) {
                lastAlignedHapticMillis = now
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    val liveSample = if (noReading) null else sample
    val readout = finderReadout(targetAltDeg, targetAzDeg, liveSample, alignmentState)
    // While the stream is interrupted, the dial and the gauge freeze
    // on the last received sample (ui-design §7.5 boundary table):
    // [sample] is never cleared once set, so it IS that last frame.
    // The readout above is still assembled from no sample in that
    // state, so every text line reports the interruption instead of
    // the frozen graphics posing as a live reading.
    val frameSample = sample
    val posture = frameSample?.let { holdingPosture(it.screenNormalTiltDeg) }
        ?: HoldingPosture.AIMING

    // --- Readout line texts (the textual channel; §7.5: the dial is
    // never the only source of this information) ---
    val azimuthText = when {
        noReading -> stringResource(R.string.finder_no_reading)
        readout.azimuthCue == AzimuthCue.ALIGNED ->
            stringResource(R.string.finder_in_front)
        readout.azimuthCue == AzimuthCue.TURN_RIGHT ->
            stringResource(R.string.finder_turn_right, abs(readout.azimuthDeltaDeg ?: 0.0).roundToInt())
        readout.azimuthCue == AzimuthCue.TURN_LEFT ->
            stringResource(R.string.finder_turn_left, abs(readout.azimuthDeltaDeg ?: 0.0).roundToInt())
        else -> stringResource(R.string.finder_no_reading)
    }
    val belowHorizonText = if (targetAltDeg < 0.0) {
        when {
            horizonSet != null && !cursor.isBefore(horizonSet) ->
                stringResource(R.string.detail_conclusion_set, formatTime(horizonSet, zone))
            horizonRise != null ->
                stringResource(
                    R.string.finder_below_horizon,
                    abs(targetAltDeg).roundToInt(), formatTime(horizonRise, zone),
                )
            else ->
                stringResource(R.string.finder_below_horizon_no_time, abs(targetAltDeg).roundToInt())
        }
    } else {
        null
    }
    val altitudeText = when {
        belowHorizonText != null -> belowHorizonText
        noReading -> stringResource(R.string.finder_no_reading)
        readout.altitudeCue == AltitudeCue.FLAT_POSTURE ->
            stringResource(R.string.finder_flat_hint)
        readout.altitudeCue == AltitudeCue.ALIGNED ->
            stringResource(R.string.finder_altitude_aligned)
        readout.altitudeCue == AltitudeCue.RAISE ->
            stringResource(R.string.finder_raise, abs(readout.altitudeDeltaDeg ?: 0.0).roundToInt())
        readout.altitudeCue == AltitudeCue.LOWER ->
            stringResource(R.string.finder_lower, abs(readout.altitudeDeltaDeg ?: 0.0).roundToInt())
        else -> stringResource(R.string.finder_no_reading)
    }
    val combinedText: Pair<String, Boolean> = when {
        // An interrupted stream never presents the gold conclusion,
        // even if the state machine was aligned when it stalled.
        noReading -> stringResource(R.string.finder_no_reading) to false
        readout.alignment == FinderAlignment.ALIGNED ->
            stringResource(R.string.finder_aligned_centre) to true
        targetAltDeg < 0.0 && alignmentState.azimuthAligned ->
            stringResource(R.string.finder_aligned_wait_rise) to false
        targetAltDeg < 0.0 ->
            stringResource(R.string.finder_hint_azimuth_below_horizon) to false
        readout.alignment == FinderAlignment.AZIMUTH_ONLY ->
            stringResource(R.string.finder_hint_finish_altitude) to false
        readout.alignment == FinderAlignment.ALTITUDE_ONLY ->
            stringResource(R.string.finder_hint_finish_azimuth) to false
        else -> stringResource(R.string.finder_hint_azimuth_first) to false
    }
    // The one gold condition for the whole card (conclusion line and
    // the 2dp border alike): dual-aligned on a live reading.
    val dualAligned = !noReading && readout.alignment == FinderAlignment.ALIGNED
    val a11ySummary = stringResource(R.string.finder_a11y_summary, azimuthText, altitudeText)

    // The pointing speaks for the cursor instant like everything in
    // this dialog; a cursor far from now earns one warning line, the
    // target is never silently switched to the current sky.
    // The staleness line compares on raw seconds: truncating the
    // difference to whole minutes first would silently move the
    // 5-minute line out to a full 6 minutes.
    val staleSeconds = abs(Duration.between(cursor, Instant.now()).seconds)
    val accuracy = sample?.accuracy
    val showCalibration = when (accuracy) {
        DeviceOrientationProvider.OrientationAccuracy.LOW ->
            calibrationDismissedAt == null
        DeviceOrientationProvider.OrientationAccuracy.UNRELIABLE ->
            calibrationDismissedAt != DeviceOrientationProvider.OrientationAccuracy.UNRELIABLE
        else -> false
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        border = if (dualAligned) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .semantics { contentDescription = a11ySummary },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle(stringResource(R.string.finder_section_title))
            if (staleSeconds > FinderThresholds.STALE_CURSOR_MINUTES * 60) {
                Text(
                    text = stringResource(
                        R.string.finder_stale_cursor,
                        formatTime(cursor, zone), staleSeconds / 60,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = app.astroexposure.ui.theme.LocalStateColors.current.warn,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                CompassDial(
                    headingDeg = frameSample?.headingTrueDeg ?: 0.0,
                    targetAzDeg = targetAzDeg,
                    azimuthAligned = alignmentState.azimuthAligned,
                )
                AltitudeGauge(
                    currentPitchDeg = frameSample?.pitchDeg,
                    targetAltDeg = targetAltDeg,
                    flat = posture == HoldingPosture.FLAT,
                )
            }
            Text(
                text = azimuthText,
                fontSize = 17.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (readout.azimuthCue == AzimuthCue.ALIGNED) {
                    FontWeight.SemiBold
                } else {
                    FontWeight.Normal
                },
                color = if (readout.azimuthCue == AzimuthCue.ALIGNED) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = altitudeText,
                fontSize = 17.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (readout.altitudeCue == AltitudeCue.ALIGNED) {
                    FontWeight.SemiBold
                } else {
                    FontWeight.Normal
                },
                color = if (readout.altitudeCue == AltitudeCue.ALIGNED) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = combinedText.first,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (combinedText.second) FontWeight.SemiBold else FontWeight.Normal,
                color = if (combinedText.second) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (showCalibration) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.finder_calibration_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { calibrationDismissedAt = accuracy },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Text(
                            text = "✕",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The compass dial: the face counter-rotates with the phone (the
 * fixed ▼ at the top reads the current true heading), ticks every
 * 15°, cardinal letters, quadrant numbers, and the gold diamond for
 * the target azimuth. When the azimuth axis is aligned, the ▼ turns
 * gold and a gold sector links it to the diamond — shape and text
 * carry the state alongside the colour.
 */
@Composable
private fun CompassDial(
    headingDeg: Double,
    targetAzDeg: Double,
    azimuthAligned: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant
    val rim = MaterialTheme.colorScheme.outlineVariant
    val labelRadiusDp = 74.dp
    val numberRadiusDp = 52.dp

    Box(modifier = modifier.size(DIAL_SIZE)) {
        Canvas(modifier = Modifier.size(DIAL_SIZE)) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f
            drawCircle(color = rim, radius = radius - 1.dp.toPx(), center = centre, style = Stroke(width = 1.dp.toPx()))
            // Ticks: drawn at their bearing relative to the heading,
            // so the face appears to counter-rotate under the fixed
            // top marker.
            for (tick in 0 until 24) {
                val bearing = tick * 15.0
                val rel = Math.toRadians(bearing - headingDeg)
                val long = tick % 3 == 0
                val outer = radius - 4.dp.toPx()
                val inner = outer - (if (long) 12.dp else 7.dp).toPx()
                drawLine(
                    color = textSecondary,
                    start = Offset(
                        centre.x + (inner * sin(rel)).toFloat(),
                        centre.y - (inner * cos(rel)).toFloat(),
                    ),
                    end = Offset(
                        centre.x + (outer * sin(rel)).toFloat(),
                        centre.y - (outer * cos(rel)).toFloat(),
                    ),
                    strokeWidth = (if (long) 2.dp else 1.dp).toPx(),
                )
            }
            // Target diamond on the rim, with its ring.
            val targetRel = Math.toRadians(targetAzDeg - headingDeg)
            val diamondCentre = Offset(
                centre.x + ((radius - 10.dp.toPx()) * sin(targetRel)).toFloat(),
                centre.y - ((radius - 10.dp.toPx()) * cos(targetRel)).toFloat(),
            )
            drawCircle(
                color = accent,
                radius = 9.dp.toPx(),
                center = diamondCentre,
                style = Stroke(width = if (azimuthAligned) 2.5.dp.toPx() else 1.5.dp.toPx()),
            )
            val diamond = Path().apply {
                val r = 6.5.dp.toPx()
                moveTo(diamondCentre.x, diamondCentre.y - r)
                lineTo(diamondCentre.x + r, diamondCentre.y)
                lineTo(diamondCentre.x, diamondCentre.y + r)
                lineTo(diamondCentre.x - r, diamondCentre.y)
                close()
            }
            drawPath(diamond, color = accent)
            if (azimuthAligned) {
                // The aligned sector: a short gold arc at the top of
                // the rim, between the fixed marker and the diamond
                // (which sits at the top when aligned).
                drawArc(
                    color = accent,
                    startAngle = -90f - 9f,
                    sweepAngle = 18f,
                    useCenter = false,
                    topLeft = Offset(centre.x - radius + 3.dp.toPx(), centre.y - radius + 3.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(
                        (radius - 3.dp.toPx()) * 2, (radius - 3.dp.toPx()) * 2,
                    ),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
            // Fixed heading marker at the top.
            val marker = Path().apply {
                val w = 7.dp.toPx()
                val top = centre.y - radius - 1.dp.toPx()
                moveTo(centre.x - w, top)
                lineTo(centre.x + w, top)
                lineTo(centre.x, top + 10.dp.toPx())
                close()
            }
            drawPath(marker, color = if (azimuthAligned) accent else textSecondary)
        }
        // Cardinal letters and quadrant numbers ride the same
        // relative bearing; as composables they stay upright.
        val cardinals = listOf(
            "N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0,
        )
        for ((letter, bearing) in cardinals) {
            val rel = Math.toRadians(bearing - headingDeg)
            val dx = labelRadiusDp * sin(rel).toFloat()
            val dy = labelRadiusDp * cos(rel).toFloat()
            Text(
                text = letter,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (letter == "N") textPrimary else textSecondary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = dx, y = -dy),
            )
        }
        for ((label, bearing) in listOf("90" to 90.0, "180" to 180.0, "270" to 270.0)) {
            val rel = Math.toRadians(bearing - headingDeg)
            val dx = numberRadiusDp * sin(rel).toFloat()
            val dy = numberRadiusDp * cos(rel).toFloat()
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = textSecondary.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = dx, y = -dy),
            )
        }
    }
}

/**
 * The vertical altitude gauge beside the dial: 0° (with its horizon
 * line) to 90°, the target as a gold line (which sinks into the
 * hatched below-horizon strip for a target that has not risen), and
 * the current pitch as a marker on the left edge with the track
 * below it filled. A flat phone hollows the marker and drops the
 * fill — no fake altitude.
 */
@Composable
private fun AltitudeGauge(
    currentPitchDeg: Double?,
    targetAltDeg: Double,
    flat: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.outline
    val fill = MaterialTheme.colorScheme.surfaceVariant
    val negZone = 12.dp

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.width(GAUGE_WIDTH).height(DIAL_SIZE)) {
            Canvas(modifier = Modifier.width(GAUGE_WIDTH).height(DIAL_SIZE)) {
                val negPx = negZone.toPx()
                val y0 = size.height - negPx // 0° line
                val plotHeight = y0 // 90° at the very top
                fun yFor(altDeg: Double): Float =
                    (y0 - (altDeg.coerceIn(0.0, 90.0) / 90.0) * plotHeight).toFloat()
                val centreX = size.width / 2f
                // Track.
                drawLine(
                    color = track,
                    start = Offset(centreX, 0f),
                    end = Offset(centreX, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
                // Ticks every 15°, numbers at 30° / 60° drawn as
                // composables below; ticks here.
                for (tickAlt in 0..90 step 15) {
                    val y = yFor(tickAlt.toDouble())
                    drawLine(
                        color = textSecondary,
                        start = Offset(centreX - 5.dp.toPx(), y),
                        end = Offset(centreX + 5.dp.toPx(), y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                // Horizon line, wider than the ticks.
                drawLine(
                    color = textSecondary,
                    start = Offset(4.dp.toPx(), y0),
                    end = Offset(size.width - 4.dp.toPx(), y0),
                    strokeWidth = 1.5.dp.toPx(),
                )
                // Below-horizon strip: diagonal hatching.
                var hx = -negPx
                while (hx < size.width) {
                    drawLine(
                        color = track,
                        start = Offset(hx, size.height),
                        end = Offset(hx + negPx, y0),
                        strokeWidth = 1.dp.toPx(),
                    )
                    hx += 5.dp.toPx()
                }
                // Filled portion up to the current pitch (aiming
                // posture with a live sample only).
                if (currentPitchDeg != null && !flat) {
                    val yCurrent = yFor(currentPitchDeg)
                    drawRect(
                        color = fill,
                        topLeft = Offset(centreX - 3.dp.toPx(), yCurrent),
                        size = androidx.compose.ui.geometry.Size(6.dp.toPx(), y0 - yCurrent),
                    )
                }
                // Target gold line. Below the horizon it sits inside
                // the hatched strip (about −15° fills the strip).
                val yTarget = if (targetAltDeg >= 0.0) {
                    yFor(targetAltDeg)
                } else {
                    (y0 + (minOf(abs(targetAltDeg), 15.0) / 15.0) * negPx).toFloat()
                }
                drawLine(
                    color = accent,
                    start = Offset(2.dp.toPx(), yTarget),
                    end = Offset(size.width - 2.dp.toPx(), yTarget),
                    strokeWidth = 2.5.dp.toPx(),
                )
                // Current-pitch marker on the left edge.
                if (currentPitchDeg != null) {
                    val yCurrent = yFor(currentPitchDeg)
                    val marker = Path().apply {
                        moveTo(0f, yCurrent)
                        lineTo(9.dp.toPx(), yCurrent - 5.dp.toPx())
                        lineTo(9.dp.toPx(), yCurrent + 5.dp.toPx())
                        close()
                    }
                    if (flat) {
                        drawPath(marker, color = textSecondary, style = Stroke(width = 1.5.dp.toPx()))
                    } else {
                        drawPath(marker, color = textSecondary)
                    }
                }
            }
            // Scale numbers and the target value as text overlays.
            // The plot runs from the top (90°) to the 0° line at
            // DIAL_SIZE - negZone; below it the hatched strip maps
            // about −15° onto negZone, mirroring the Canvas geometry.
            val plotDp = DIAL_SIZE - negZone
            for ((label, altDeg) in listOf("30" to 30.0, "60" to 60.0)) {
                val yDp = plotDp * (1f - (altDeg / 90.0).toFloat())
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = textSecondary.copy(alpha = 0.7f),
                    modifier = Modifier.offset(x = 30.dp, y = yDp - 8.dp),
                )
            }
            val targetYDp = if (targetAltDeg >= 0.0) {
                plotDp * (1f - (targetAltDeg.coerceIn(0.0, 90.0) / 90.0).toFloat())
            } else {
                plotDp + negZone * (minOf(abs(targetAltDeg), 15.0) / 15.0).toFloat()
            }
            Text(
                text = "${targetAltDeg.roundToInt()}°",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = accent,
                modifier = Modifier.offset(x = 26.dp, y = targetYDp - 10.dp),
            )
        }
        Text(
            text = stringResource(R.string.finder_horizon_label),
            style = MaterialTheme.typography.labelSmall,
            color = textSecondary,
        )
    }
}

private val DIAL_SIZE = 200.dp
private val GAUGE_WIDTH = 54.dp

/** Seconds without any (or a fresh) sample before the reading is declared stale. */
private const val NO_READING_AFTER_SECONDS = 3L

/** Minimum gap between two "aligned" haptics inside one dialog session. */
private const val ALIGNED_HAPTIC_THROTTLE_MS = 10_000L
