package app.astroexposure.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown

import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.astroexposure.R
import app.astroexposure.domain.timeline.NightTimeline
import app.astroexposure.domain.timeline.TimelineCursor
import app.astroexposure.usecase.TonightOverviewUseCase
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

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
 * One cursor target's arc on the timeline (ui-design §7.5): the spans
 * where the target is at or above 20°, its culmination instant (drawn
 * as a solid dot) and its display name. Since the 0.5.1 revision the
 * arc is a tall information lane: [phases] segments it into rising /
 * culmination / setting parts, and [culminationText] (e.g. "中天
 * 23:24 · 74°") is drawn inside the culmination segment whenever that
 * segment is wide enough to hold it.
 */
data class TimelineArc(
    val intervals: List<NightTimeline.Interval>,
    val culmination: Instant,
    val label: String,
    val phases: TimelineCursor.ArcPhases? = null,
    val culminationText: String? = null,
    /**
     * The recommended shooting window cut to this lane's arc — drawn
     * as a bright frame over the segmented bar (ui-design §7.5, 0.6.0).
     * The frame is a border SHAPE over the fills, so it stays legible
     * in the red theme where hues collapse into one family.
     */
    val recommendedFrame: NightTimeline.Interval? = null,
)

/** Arc lane height (ui-design §7.5, 0.5.1): tall enough for readable in-lane text. */
private val ARC_LANE_HEIGHT_DP = 26.dp

/**
 * Rough text width estimate in dp for the in-lane labels: CJK glyphs
 * count one em, digits and punctuation about half an em. Used ONLY to
 * decide whether a label fits its segment (the omission rule of
 * ui-design §7.5) — a label that does not fit is omitted, never
 * shrunk or overlapped.
 */
private fun estimateTextWidthDp(text: String, fontSizeSp: Float): Double =
    text.sumOf { ch ->
        when {
            ch >= '⺀' -> fontSizeSp * 1.0
            ch.isDigit() || ch == ':' || ch == '·' || ch == '.' || ch == '°' || ch == ' ' ->
                fontSizeSp * 0.55
            else -> fontSizeSp * 0.62
        }
    }

/** The cursor instant for a horizontal pixel position on the axis (shared by tap and drag). */
private fun cursorInstantAt(
    xPx: Float,
    widthPx: Int,
    timeline: TonightOverviewUseCase.Timeline,
): Instant {
    val totalSec = (timeline.axisEnd.epochSecond - timeline.axisStart.epochSecond)
        .toDouble().coerceAtLeast(1.0)
    val fraction = if (widthPx > 0) (xPx / widthPx).coerceIn(0f, 1f) else 0f
    val epoch = timeline.axisStart.epochSecond + (fraction * totalSec).toLong()
    return TimelineCursor.clampToAxis(
        TimelineCursor.truncatedToMinute(Instant.ofEpochSecond(epoch)),
        timeline.axisStart, timeline.axisEnd,
    )
}

/**
 * Long-press drag detector for cursor repositioning (ui-design §7.5,
 * 0.5.2 anti-mistouch rule): a press that stays within the touch slop
 * for [timeoutMs] "grabs" the cursor — [onGrab] fires once (the caller
 * gives the haptic tick) — and from then on the pointer's horizontal
 * position is reported to [onDragTo] until it lifts. A press released
 * early, or one that moves beyond the slop before the timeout, is not
 * a grab and is left entirely to the plain tap/drag detectors. The
 * detector observes events without consuming them. [enabled] is
 * consulted at press time; when it is false the gesture is ignored.
 */
private suspend fun PointerInputScope.detectCursorLongPressDrag(
    timeoutMs: Long,
    enabled: () -> Boolean,
    onGrab: () -> Unit,
    onDragTo: (Float) -> Unit,
) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent().changes.firstOrNull { it.changedToDown() }
                ?: continue
            if (!enabled()) continue
            val pointerId: PointerId = down.id
            val start = down.position
            var lastX = start.x
            // Wait out the hold: released or moved early means no grab.
            val grabbed = withTimeoutOrNull(timeoutMs) {
                while (true) {
                    val change = awaitPointerEvent().changes
                        .firstOrNull { it.id == pointerId } ?: continue
                    if (!change.pressed) {
                        return@withTimeoutOrNull false
                    }
                    lastX = change.position.x
                    if ((change.position - start).getDistance() > viewConfiguration.touchSlop) {
                        return@withTimeoutOrNull false
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } == null
            if (!grabbed) continue
            onGrab()
            onDragTo(lastX)
            // Follow the grabbed pointer until it lifts.
            while (true) {
                val change = awaitPointerEvent().changes
                    .firstOrNull { it.id == pointerId } ?: continue
                if (!change.pressed) break
                onDragTo(change.position.x)
            }
        }
    }
}

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
 * Phase 2 (ui-design §7.5) fills the reserved layers: the cursor
 * track, bubble and target arcs, plus the time bar under the axis.
 *
 * Gesture coexistence with the §7.4 mark hot zones (minimal arrangement,
 * the mark dialogs themselves are unchanged): the hot zones are child
 * clickables whose tap handling consumes a tap before the chart's own
 * tap detector sees it, so a tap that opens a mark detail does NOT
 * place the cursor; a tap anywhere else places it. A drag anywhere
 * takes over the cursor — the drag consumes its moves, which also
 * cancels any hot-zone press in progress.
 *
 * Anti-mistouch (ui-design §7.5, 0.5.2): the plain tap and drag paths
 * above place the cursor only while none exists — the drag detector
 * snapshots that at gesture start, so the gesture that first places
 * the cursor keeps following the finger, while any later plain drag
 * is inert. Once the cursor is placed, repositioning goes through the
 * long-press drag detector (hold ~300ms to grab, then drag); while
 * [cursorLocked] is set, no axis gesture moves the cursor at all and
 * only the time bar's step buttons (deliberate actions) still apply.
 * The pure state machine behind this lives in
 * [TimelineCursor.mayMoveCursor] and is unit-tested there.
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
    cursorInstant: Instant? = null,
    cursorActive: Boolean = false,
    bubbleTimeText: String? = null,
    bubbleMoonText: String? = null,
    bubbleSnapLabel: String? = null,
    arcs: List<TimelineArc> = emptyList(),
    highlightedArc: Int? = null,
    onCursorTap: (Instant) -> Unit = {},
    onCursorDrag: (Instant) -> Unit = {},
    onCursorStep: (Long) -> Unit = {},
    onExitCursor: () -> Unit = {},
    onArcTap: (Int) -> Unit = {},
    cursorLocked: Boolean = false,
    onCursorLongPressDrag: (Instant) -> Unit = {},
    onCursorGrab: () -> Unit = {},
    onToggleCursorLock: () -> Unit = {},
) {
    var selectedMark by remember { mutableStateOf<TimelineMark?>(null) }
    val chartDescription = stringResource(R.string.timeline_chart_description)
    // Anti-mistouch gesture state (ui-design §7.5): snapshots read at
    // gesture start — deliberately NOT pointerInput keys, since a key
    // change would restart a detector mid-gesture. The plain drag owns
    // the cursor only when its gesture began with no cursor placed.
    val cursorPlacedState = rememberUpdatedState(cursorInstant != null)
    val cursorLockedState = rememberUpdatedState(cursorLocked)
    var plainDragMovesCursor by remember { mutableStateOf(false) }
    val sunColor = if (redLight) Color(0xFFFF7A45) else Color(0xFFF2C66D)
    val moonColor = if (redLight) Color(0xFFFF9D8A) else Color(0xFF6EA8FE)
    val bandBase = if (redLight) Color(0xFFFF4A3D) else Color(0xFF6EA8FE)
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    // Text/marks drawn on accent fills take the theme onPrimary token
    // in both themes instead of a hard-coded near-black.
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary

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
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .semantics {
                                contentDescription = chartDescription
                            }
                            .pointerInput(timeline.axisStart, timeline.axisEnd) {
                                // Cursor tap (ui-design §7.5) with the
                                // coexistence rule documented on the
                                // card: the mark hot zones are children
                                // whose click handling consumes a tap
                                // first, so a tap that opens a mark
                                // detail never reaches this detector and
                                // never places the cursor.
                                detectTapGestures(
                                    onTap = { offset ->
                                        onCursorTap(cursorInstantAt(offset.x, size.width, timeline))
                                    },
                                )
                            }
                            .pointerInput(timeline.axisStart, timeline.axisEnd) {
                                // Direction-aware drag (interaction fix):
                                // the first slop-crossing movement decides
                                // the axis. A vertically dominant drag is
                                // NOT consumed, so the page scroll keeps it;
                                // only horizontal drags move the cursor.
                                // (detectDragGestures consumed every axis
                                // and made the chart - the page's largest
                                // block - a scroll trap.)
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    plainDragMovesCursor = !cursorPlacedState.value
                                    var totalDx = 0f
                                    var totalDy = 0f
                                    var horizontal: Boolean? = null
                                    var change = currentEvent.changes.firstOrNull { it.id == down.id }
                                    while (change != null && !change.changedToUp()) {
                                        val event = awaitPointerEvent()
                                        change = event.changes.firstOrNull { it.id == down.id }
                                            ?: break
                                        if (change.changedToUp()) break
                                        val delta = change.positionChange()
                                        totalDx += delta.x
                                        totalDy += delta.y
                                        if (horizontal == null &&
                                            (kotlin.math.abs(totalDx) > viewConfiguration.touchSlop ||
                                                kotlin.math.abs(totalDy) > viewConfiguration.touchSlop)
                                        ) {
                                            horizontal =
                                                kotlin.math.abs(totalDx) >= kotlin.math.abs(totalDy)
                                            if (horizontal == true && plainDragMovesCursor) {
                                                change.consume()
                                                onCursorDrag(
                                                    cursorInstantAt(
                                                        change.position.x, size.width, timeline,
                                                    ),
                                                )
                                            }
                                        } else if (horizontal == true && plainDragMovesCursor) {
                                            change.consume()
                                            onCursorDrag(
                                                cursorInstantAt(
                                                    change.position.x, size.width, timeline,
                                                ),
                                            )
                                        }
                                    }
                                    plainDragMovesCursor = false
                                }
                            }
                            .pointerInput(timeline.axisStart, timeline.axisEnd) {
                                // Repositioning a placed cursor
                                // (ui-design §7.5): hold ~300ms to grab
                                // it, then drag. Refused while locked.
                                detectCursorLongPressDrag(
                                    timeoutMs = TimelineCursor.LONG_PRESS_REPOSITION_MS,
                                    enabled = {
                                        cursorPlacedState.value && !cursorLockedState.value
                                    },
                                    onGrab = onCursorGrab,
                                    onDragTo = { x ->
                                        onCursorLongPressDrag(
                                            cursorInstantAt(x, size.width, timeline),
                                        )
                                    },
                                )
                            },
                    ) {
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
                            // Cursor (ui-design §7.5): gold line across
                            // the chart with a knob at the top, drawn
                            // only while the cursor state is active.
                            if (cursorActive && cursorInstant != null) {
                                val cursorX = xOf(cursorInstant)
                                drawLine(
                                    color = sunColor,
                                    start = Offset(cursorX, 0f),
                                    end = Offset(cursorX, size.height),
                                    strokeWidth = 2.dp.toPx(),
                                )
                                drawCircle(
                                    color = sunColor,
                                    radius = 5.dp.toPx(),
                                    center = Offset(cursorX, 5.dp.toPx()),
                                )
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
                                    .clickable { selectedMark = mark }
                                    .semantics { contentDescription = mark.title },
                            )
                        }
                        // Cursor bubble (ui-design §7.5): cursor time
                        // plus the Moon's altitude and illumination at
                        // that moment, readable while dragging. When
                        // the cursor is snapped to a key moment the
                        // moment's name is appended.
                        if (cursorActive && cursorInstant != null && bubbleTimeText != null) {
                            val fraction = NightTimeline.fractionOf(
                                cursorInstant, timeline.axisStart, timeline.axisEnd,
                            )
                            val bubbleWidth = 128.0
                            val bubbleLeft = (fraction * widthDp - bubbleWidth / 2)
                                .coerceIn(0.0, max(0.0, widthDp - bubbleWidth))
                            Column(
                                modifier = Modifier
                                    .offset(x = bubbleLeft.dp, y = 14.dp)
                                    .width(bubbleWidth.dp)
                                    .background(sunColor, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = bubbleTimeText,
                                    color = onPrimaryColor,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (bubbleMoonText != null) {
                                    Text(
                                        text = bubbleMoonText,
                                        color = onPrimaryColor,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                if (bubbleSnapLabel != null) {
                                    Text(
                                        text = bubbleSnapLabel,
                                        color = onPrimaryColor,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
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
                    // Cursor track (ui-design §7.5, the phase-1 reserved
                    // slot): a thin rail under the axis with the cursor
                    // knob, visible while the cursor state is active.
                    Box(modifier = Modifier.fillMaxWidth().height(6.dp)) {
                        if (cursorActive && cursorInstant != null) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val totalSec = (timeline.axisEnd.epochSecond -
                                    timeline.axisStart.epochSecond).toDouble().coerceAtLeast(1.0)
                                val cursorX = ((cursorInstant.epochSecond -
                                    timeline.axisStart.epochSecond) / totalSec).toFloat() * size.width
                                drawLine(
                                    color = borderColor,
                                    start = Offset(0f, size.height / 2),
                                    end = Offset(size.width, size.height / 2),
                                    strokeWidth = 1.dp.toPx(),
                                )
                                drawCircle(
                                    color = sunColor,
                                    radius = 3.dp.toPx(),
                                    center = Offset(cursorX, size.height / 2),
                                )
                            }
                        }
                    }
                    // Target-arc layer (ui-design §7.5, the phase-1
                    // reserved slot; 0.5.1 revision): one TALL lane per
                    // displayed cursor target. The lane's bar is its
                    // ≥20° window segmented rising / culmination /
                    // setting: the culmination segment is gold-filled
                    // (in the red theme the same slot is the brightest
                    // red AND carries the culmination text, so the
                    // segment never reads by colour alone), rising is a
                    // mid-tone fill, setting a dimmer one. The target
                    // name sits inside the lane at the window start;
                    // the culmination text sits inside the culmination
                    // segment when that segment is wide enough —
                    // otherwise it is omitted (never shrunk, never
                    // overlapped; the detail card below always carries
                    // the same figures). Since 0.6.0 the recommended
                    // shooting window's overlap with the arc is framed
                    // with a thick bright border over the fills — a
                    // shape, not just a colour, so it survives the red
                    // theme. Tapping a lane activates its target for
                    // the detail card.
                    if (cursorActive && arcs.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            arcs.forEachIndexed { index, arc ->
                                val highlighted = highlightedArc == index
                                val baseColor = if (highlighted) {
                                    sunColor
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .clickable { onArcTap(index) }
                                        .semantics { contentDescription = arc.label },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(ARC_LANE_HEIGHT_DP),
                                    ) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val totalSec = (timeline.axisEnd.epochSecond -
                                            timeline.axisStart.epochSecond).toDouble()
                                            .coerceAtLeast(1.0)
                                        fun xOf(instant: Instant): Float =
                                            ((instant.epochSecond - timeline.axisStart.epochSecond) /
                                                totalSec).toFloat() * size.width
                                        val barTop = 2.dp.toPx()
                                        val barHeight = size.height - 4.dp.toPx()
                                        fun segment(
                                            interval: NightTimeline.Interval?,
                                            fill: Color,
                                            outline: Color,
                                        ) {
                                            if (interval == null) return
                                            val left = xOf(interval.start)
                                            val right = xOf(interval.end)
                                            if (right - left < 1f) return
                                            drawRoundRect(
                                                color = fill,
                                                topLeft = Offset(left, barTop),
                                                size = Size(right - left, barHeight),
                                                cornerRadius = androidx.compose.ui.geometry
                                                    .CornerRadius(6.dp.toPx()),
                                            )
                                            drawRoundRect(
                                                color = outline,
                                                topLeft = Offset(left, barTop),
                                                size = Size(right - left, barHeight),
                                                cornerRadius = androidx.compose.ui.geometry
                                                    .CornerRadius(6.dp.toPx()),
                                                style = Stroke(width = 1.dp.toPx()),
                                            )
                                        }
                                        val phases = arc.phases
                                        if (phases != null) {
                                            segment(
                                                phases.rising,
                                                baseColor.copy(alpha = 0.22f), baseColor,
                                            )
                                            segment(
                                                phases.culminationSpan,
                                                sunColor.copy(
                                                    alpha = if (highlighted) 0.85f else 0.55f,
                                                ),
                                                sunColor,
                                            )
                                            segment(
                                                phases.setting,
                                                baseColor.copy(alpha = 0.10f), baseColor,
                                            )
                                        } else {
                                            for (interval in arc.intervals) {
                                                segment(
                                                    interval,
                                                    baseColor.copy(alpha = 0.16f), baseColor,
                                                )
                                            }
                                        }
                                        // Recommended-window frame
                                        // (ui-design §7.5, 0.6.0): a thick
                                        // bright border around the framed
                                        // span, drawn over the phase fills.
                                        arc.recommendedFrame?.let { frame ->
                                            val left = xOf(frame.start)
                                            val right = xOf(frame.end)
                                            if (right - left >= 2f) {
                                                drawRoundRect(
                                                    color = sunColor,
                                                    topLeft = Offset(
                                                        left, barTop - 1.dp.toPx(),
                                                    ),
                                                    size = Size(
                                                        right - left,
                                                        barHeight + 2.dp.toPx(),
                                                    ),
                                                    cornerRadius = androidx.compose.ui
                                                        .geometry.CornerRadius(7.dp.toPx()),
                                                    style = Stroke(width = 2.5.dp.toPx()),
                                                )
                                            }
                                        }
                                        drawCircle(
                                            color = if (phases != null) {
                                                onPrimaryColor
                                            } else {
                                                baseColor
                                            },
                                            radius = 2.5.dp.toPx(),
                                            center = Offset(
                                                xOf(arc.culmination), size.height / 2,
                                            ),
                                        )
                                    }
                                    // Name inside the lane at the window
                                    // start, single line, ellipsised at
                                    // the lane edge (documented omission
                                    // rule, ui-design §7.5).
                                    val windowStart = arc.phases?.window?.start
                                        ?: arc.intervals.firstOrNull()?.start
                                    if (windowStart != null) {
                                        val fraction = NightTimeline.fractionOf(
                                            windowStart, timeline.axisStart, timeline.axisEnd,
                                        )
                                        val textX = fraction * widthDp + 6.0
                                        Text(
                                            text = arc.label,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style
                                                .TextOverflow.Ellipsis,
                                            color = baseColor,
                                            modifier = Modifier
                                                .offset(x = textX.dp, y = 5.dp)
                                                .width(max(0.0, widthDp - textX - 4.0).dp),
                                        )
                                    }
                                    // Culmination text inside the gold
                                    // segment, only when the segment is
                                    // wide enough for the estimate plus
                                    // padding (omission rule, §7.5).
                                    val span = arc.phases?.culminationSpan
                                    val culminationText = arc.culminationText
                                    if (span != null && culminationText != null) {
                                        val spanStartFraction = NightTimeline.fractionOf(
                                            span.start, timeline.axisStart, timeline.axisEnd,
                                        )
                                        val spanEndFraction = NightTimeline.fractionOf(
                                            span.end, timeline.axisStart, timeline.axisEnd,
                                        )
                                        val spanWidthDp =
                                            (spanEndFraction - spanStartFraction) * widthDp
                                        val textWidthDp = estimateTextWidthDp(
                                            culminationText, 10f,
                                        )
                                        if (spanWidthDp >= textWidthDp + 10.0) {
                                            val textX = spanStartFraction * widthDp +
                                                (spanWidthDp - textWidthDp) / 2.0
                                            Text(
                                                text = culminationText,
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                fontWeight = FontWeight.SemiBold,
                                                color = onPrimaryColor,
                                                modifier = Modifier.offset(
                                                    x = textX.dp, y = 6.dp,
                                                ),
                                            )
                                        }
                                    }
                                    }
                                }
                            }
                        }
                    }
                    // Time bar (ui-design §7.5): −1′ / +1′ minute steps
                    // (long press repeats in 10-minute steps), the
                    // cursor time, the lock toggle and the whole-night
                    // exit.
                    if (cursorActive && bubbleTimeText != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CursorStepButton(
                                label = stringResource(R.string.cursor_step_back),
                                borderColor = borderColor,
                                onStep = { minutes -> onCursorStep(-minutes) },
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .border(1.dp, sunColor, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = bubbleTimeText,
                                    color = sunColor,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            CursorStepButton(
                                label = stringResource(R.string.cursor_step_forward),
                                borderColor = borderColor,
                                onStep = { minutes -> onCursorStep(minutes) },
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            // Lock toggle (ui-design §7.5, 0.5.2): while
                            // locked no axis gesture moves the cursor;
                            // the step buttons stay live. The locked
                            // state reads by fill AND label, not colour
                            // alone (in the red theme the fill uses
                            // the same accent family).
                            Box(
                                modifier = Modifier
                                    .height(48.dp)
                                    .then(
                                        if (cursorLocked) {
                                            Modifier.background(
                                                sunColor, RoundedCornerShape(12.dp),
                                            )
                                        } else {
                                            Modifier.border(
                                                1.dp, borderColor, RoundedCornerShape(12.dp),
                                            )
                                        },
                                    )
                                    .clickable { onToggleCursorLock() }
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(
                                        if (cursorLocked) {
                                            R.string.cursor_locked
                                        } else {
                                            R.string.cursor_lock
                                        },
                                    ),
                                    color = if (cursorLocked) {
                                        onPrimaryColor
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    fontWeight = if (cursorLocked) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(onClick = onExitCursor) {
                                Text(stringResource(R.string.cursor_show_whole_night))
                            }
                        }
                    }
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

/**
 * One step button of the cursor time bar (ui-design §7.5): a tap steps
 * one minute; a long press switches to repeated 10-minute steps until
 * release. [onStep] receives the unsigned step size in minutes; the
 * caller applies the direction.
 */
@Composable
private fun CursorStepButton(
    label: String,
    borderColor: Color,
    onStep: (Long) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            // The press/hold gesture below is pointer-level, so expose
            // the step as a real accessibility action: a screen reader
            // can invoke the one-minute step and hears the button's name.
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick(label = label) {
                    onStep(1L)
                    true
                }
            }
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        onStep(1L)
                        try {
                            withTimeout(450L) { tryAwaitRelease() }
                        } catch (e: PointerEventTimeoutCancellationException) {
                            var released = false
                            while (!released) {
                                onStep(10L)
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
