package app.astroexposure.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.astroexposure.R
import app.astroexposure.domain.timeline.NightTimeline
import app.astroexposure.usecase.TonightOverviewUseCase
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max

/**
 * One tappable mark on the timeline (ui-design §7.4, 0.4.1 revision):
 * marks carry no text label — the tick (and, for the Sun and Moon, the
 * curve's horizon crossing) is the mark. Tapping opens a detail dialog
 * whose [title] and [lines] are resolved by the caller: Sun marks open
 * the Sun detail, Moon marks the Moon detail, twilight marks show the
 * moment and its solar altitude, and the galactic-centre mark shows
 * the culmination time and altitude.
 */
data class TimelineMark(
    val instant: Instant,
    val title: String,
    val lines: List<String>,
)

/**
 * The static four-layer timeline (ui-design §7.4): solar background
 * bands, Sun/Moon altitude curves sampled every 5 minutes, the
 * low-moonlight and galactic-centre window bands, and the nine key
 * moments as label-free ticks with transparent tap hot zones at least
 * 40dp wide.
 *
 * Degradation (ui-design §7.4): without an astronomical night the
 * background bands and the galactic-centre band are absent — the axis
 * degrades to the Sun/Moon curves plus the explicit no-night note.
 *
 * The altitude axis lower bound follows the sampled series
 * ([NightTimeline.axisMinAltitudeDeg]) so below-horizon curve sections
 * are drawn in full instead of being clipped at a fixed floor.
 *
 * Information is never carried by hue alone: the Sun curve is solid and
 * the Moon curve dashed in both themes; the low-moonlight band has a
 * dashed border and the galactic-centre band a dot-dash border
 * (ui-design board, red-light variant).
 *
 * Layout reserves the phase-2 layers (ui-design §7.5) as empty
 * structural slots below the chart — cursor track, bubble layer and
 * target-arc layer — so the interactive phase reuses this layout.
 */
@Composable
fun NightTimelineCard(
    timeline: TonightOverviewUseCase.Timeline,
    marks: List<TimelineMark>,
    moonIllumination: Double,
    lowMoonlightWindows: List<TonightOverviewUseCase.LowMoonlightWindow>,
    zone: ZoneId,
    redLight: Boolean,
    modifier: Modifier = Modifier,
) {
    var selectedMark by remember { mutableStateOf<TimelineMark?>(null) }
    val sunColor = if (redLight) Color(0xFFFF7A45) else Color(0xFFF2C66D)
    val moonColor = if (redLight) Color(0xFFFF9D8A) else Color(0xFF6EA8FE)
    val bandBase = if (redLight) Color(0xFFFF4A3D) else Color(0xFF6EA8FE)
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionTitle(stringResource(R.string.timeline_title))
            if (!timeline.hasAstronomicalNight) {
                Text(
                    text = stringResource(R.string.timeline_no_night),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(R.string.timeline_legend_sun),
                    style = MaterialTheme.typography.bodySmall,
                    color = sunColor,
                )
                Text(
                    text = stringResource(R.string.timeline_legend_moon),
                    style = MaterialTheme.typography.bodySmall,
                    color = moonColor,
                )
                Text(
                    text = stringResource(
                        R.string.timeline_moon_note,
                        Math.round(moonIllumination * 100),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val widthDp = maxWidth.value
                Column {
                    // Chart: bands + curves + window bands + marker ticks
                    // + transparent tap hot zones over the ticks.
                    Box(modifier = Modifier.fillMaxWidth().height(150.dp)) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val totalSec = (timeline.axisEnd.epochSecond - timeline.axisStart.epochSecond)
                                .toDouble().coerceAtLeast(1.0)
                            fun xOf(instant: Instant): Float =
                                ((instant.epochSecond - timeline.axisStart.epochSecond) / totalSec)
                                    .toFloat() * size.width
                            val maxAlt = max(
                                40.0,
                                timeline.samples.maxOfOrNull {
                                    max(it.sunAltitudeDeg, it.moonAltitudeDeg)
                                } ?: 40.0,
                            )
                            // Dynamic lower bound from the sampled series
                            // (ui-design §7.4): deepest sample minus a
                            // margin, clamped to [-90°, -5°], instead of
                            // the former fixed -20° that clipped the
                            // below-horizon Moon curve.
                            val minAlt = NightTimeline.axisMinAltitudeDeg(timeline.samples)
                            fun yOf(altDeg: Double): Float =
                                ((maxAlt - altDeg) / (maxAlt - minAlt)).toFloat() * size.height

                            // Layer 1: background bands from the solar
                            // samples — absent without an astronomical
                            // night (ui-design §7.4 degradation).
                            if (timeline.hasAstronomicalNight) {
                                for (segment in timeline.bandSegments) {
                                    val alpha = when (segment.band) {
                                        NightTimeline.SunBand.DAY -> 0.16f
                                        NightTimeline.SunBand.CIVIL_TWILIGHT -> 0.10f
                                        NightTimeline.SunBand.NAUTICAL_TWILIGHT -> 0.06f
                                        NightTimeline.SunBand.ASTRONOMICAL_TWILIGHT -> 0.03f
                                        NightTimeline.SunBand.ASTRONOMICAL_NIGHT -> 0.0f
                                    }
                                    if (alpha > 0f) {
                                        drawRect(
                                            color = bandBase.copy(alpha = alpha),
                                            topLeft = Offset(xOf(segment.start), 0f),
                                            size = Size(xOf(segment.end) - xOf(segment.start), size.height),
                                        )
                                    }
                                }
                            }
                            // Horizon line.
                            drawLine(
                                color = borderColor,
                                start = Offset(0f, yOf(0.0)),
                                end = Offset(size.width, yOf(0.0)),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                            )
                            // Marker ticks: the tick itself is the mark
                            // (no text labels since the 0.4.1 revision).
                            for (mark in marks) {
                                drawLine(
                                    color = borderColor.copy(alpha = 0.6f),
                                    start = Offset(xOf(mark.instant), 0f),
                                    end = Offset(xOf(mark.instant), size.height),
                                    strokeWidth = 1.dp.toPx(),
                                )
                            }
                            // Layer 2: altitude curves. Sun solid, Moon dashed.
                            fun curvePath(moon: Boolean): Path {
                                val path = Path()
                                timeline.samples.forEachIndexed { i, sample ->
                                    val x = xOf(sample.instant)
                                    val y = yOf(if (moon) sample.moonAltitudeDeg else sample.sunAltitudeDeg)
                                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                return path
                            }
                            drawPath(
                                path = curvePath(moon = false),
                                color = sunColor,
                                style = Stroke(width = 2.dp.toPx()),
                            )
                            drawPath(
                                path = curvePath(moon = true),
                                color = moonColor.copy(alpha = 0.55f),
                                style = Stroke(
                                    width = 1.5.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f)),
                                ),
                            )
                            // Moon above the horizon: same dashed style, full strength.
                            var run = Path()
                            var inRun = false
                            for (sample in timeline.samples) {
                                val x = xOf(sample.instant)
                                val y = yOf(sample.moonAltitudeDeg)
                                if (sample.moonAltitudeDeg > 0.0) {
                                    if (!inRun) {
                                        run = Path()
                                        run.moveTo(x, y)
                                        inRun = true
                                    } else {
                                        run.lineTo(x, y)
                                    }
                                } else if (inRun) {
                                    drawPath(
                                        path = run, color = moonColor,
                                        style = Stroke(
                                            width = 2.5.dp.toPx(),
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f)),
                                        ),
                                    )
                                    inRun = false
                                }
                            }
                            if (inRun) {
                                drawPath(
                                    path = run, color = moonColor,
                                    style = Stroke(
                                        width = 2.5.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f)),
                                    ),
                                )
                            }
                            // Layer 3: window bands at the foot of the chart.
                            val bandHeight = 11.dp.toPx()
                            val gcY = size.height - bandHeight * 2 - 4.dp.toPx()
                            val lowY = size.height - bandHeight
                            fun bandRect(interval: NightTimeline.Interval, top: Float, fill: Color, effect: PathEffect) {
                                val left = xOf(interval.start)
                                val right = xOf(interval.end)
                                drawRect(
                                    color = fill,
                                    topLeft = Offset(left, top),
                                    size = Size(right - left, bandHeight),
                                )
                                val outline = Path().apply {
                                    moveTo(left, top)
                                    lineTo(right, top)
                                    lineTo(right, top + bandHeight)
                                    lineTo(left, top + bandHeight)
                                    close()
                                }
                                drawPath(
                                    path = outline, color = fill.copy(alpha = 0.95f),
                                    style = Stroke(width = 1.2.dp.toPx(), pathEffect = effect),
                                )
                            }
                            if (timeline.hasAstronomicalNight) {
                                for (window in lowMoonlightWindows) {
                                    NightTimeline.clampToAxis(
                                        window.start, window.end, timeline.axisStart, timeline.axisEnd,
                                    )?.let {
                                        bandRect(
                                            it, lowY, moonColor,
                                            PathEffect.dashPathEffect(floatArrayOf(8f, 5f)),
                                        )
                                    }
                                }
                                // The galactic-centre band shares the
                                // no-astronomical-night degradation.
                                timeline.galacticCenterInterval?.let {
                                    bandRect(
                                        it, gcY, sunColor,
                                        PathEffect.dashPathEffect(floatArrayOf(2f, 4f, 9f, 4f)),
                                    )
                                }
                            }
                        }
                        // Tap hot zones over the ticks and curve crossings:
                        // transparent, 40dp wide and spanning the chart
                        // height, so a mark is tappable well beyond the
                        // 1dp tick line itself (ui-design §7.4). Where
                        // marks are closer than 40dp the zones overlap
                        // and the later mark wins the overlap.
                        for (mark in marks) {
                            val fraction = NightTimeline.fractionOf(
                                mark.instant, timeline.axisStart, timeline.axisEnd,
                            )
                            val leftDp = (fraction * widthDp - 20.0).coerceIn(0.0, max(0.0, widthDp - 40.0))
                            Box(
                                modifier = Modifier
                                    .offset(x = leftDp.dp)
                                    .size(width = 40.dp, height = 150.dp)
                                    .clickable { selectedMark = mark },
                            )
                        }
                    }
                    // Band legend row (text, so the bands do not rely on colour or line style alone).
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (timeline.hasAstronomicalNight) {
                            Text(
                                text = stringResource(R.string.timeline_gc_band),
                                style = MaterialTheme.typography.labelSmall,
                                color = sunColor,
                            )
                            Text(
                                text = stringResource(R.string.timeline_low_moon_band),
                                style = MaterialTheme.typography.labelSmall,
                                color = moonColor,
                            )
                        }
                    }
                    // Axis time labels: start, thirds, end.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val totalSec = timeline.axisEnd.epochSecond - timeline.axisStart.epochSecond
                        listOf(0.0, 1.0 / 3.0, 2.0 / 3.0, 1.0).forEach { fraction ->
                            val instant = timeline.axisStart.plusSeconds((totalSec * fraction).toLong())
                            Text(
                                text = formatTime(instant, zone),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // Reserved phase-2 layers (ui-design §7.5): cursor
                    // track, bubble layer and target-arc layer slots,
                    // empty in this phase.
                    Box(modifier = Modifier.fillMaxWidth().height(6.dp)) { /* cursor track slot */ }
                    Box(modifier = Modifier.fillMaxWidth().height(0.dp)) { /* bubble layer slot */ }
                    Box(modifier = Modifier.fillMaxWidth().height(0.dp)) { /* target-arc layer slot */ }
                }
            }
        }
    }

    selectedMark?.let { mark ->
        AlertDialog(
            onDismissRequest = { selectedMark = null },
            title = { Text(mark.title) },
            text = { Text(mark.lines.joinToString("\n")) },
            confirmButton = {
                TextButton(onClick = { selectedMark = null }) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
    }
}
