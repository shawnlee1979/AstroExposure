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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
 * One tappable mark on the timeline (ui-design §7.4): the tick is the
 * mark. Tapping opens a detail dialog whose [title] and [lines] are
 * resolved by the caller: Sun marks open the Sun detail, Moon marks
 * the Moon detail, twilight marks show the moment and its solar
 * altitude, and the galactic-centre mark shows the culmination time
 * and altitude. Since 0.8.8 the main marks (sunrise / sunset, the
 * astronomical night's start and end, the galactic-centre culmination)
 * also carry a [shortLabel] drawn under the tick; [labelRank] decides
 * which labels survive when neighbouring labels would overlap — the
 * night's start and end rank highest.
 */
data class TimelineMark(
    val instant: Instant,
    val title: String,
    val lines: List<String>,
    val shortLabel: String? = null,
    val labelRank: Int = 0,
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
 * Layered-panel row metrics (ui-design §7.4, 0.8.7; visual values
 * from the approved comparison board): the rows are inset by
 * [PLOT_INSET_DP] on both sides of the panel, and every row — bands,
 * marker row, lanes — shares the plot area between the insets, so a
 * single fraction-to-x mapping serves the whole panel.
 */
private val PLOT_INSET_DP = 8.dp
private val SUN_BAND_HEIGHT_DP = 16.dp
private val MOON_BAND_HEIGHT_DP = 12.dp
private val MARKER_ROW_HEIGHT_DP = 28.dp
private val WINDOW_BAND_HEIGHT_DP = 10.dp

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

/** One in-row text label competing for horizontal room (ui-design §7.4). */
private data class LabelCandidate(
    val text: String,
    /** Desired centre along the plot, in dp from the plot's left edge. */
    val centerDp: Double,
    val widthDp: Double,
    /** Higher rank wins when two labels would overlap. */
    val rank: Int,
)

/**
 * Priority placement shared by the sun band's short labels and the
 * marker row's mark labels (ui-design §7.4, 0.8.8): candidates are
 * considered highest rank first; each is centred on its anchor,
 * clamped into the plot, and kept only when its span (plus a small
 * gap) does not overlap an already-kept span — a colliding label is
 * omitted whole, never shrunk and never shifted into its neighbour.
 * Returns the kept labels paired with their clamped left offset, in
 * the original candidate order.
 */
private fun placeLabels(
    candidates: List<LabelCandidate>,
    plotWidthDp: Double,
): List<Pair<LabelCandidate, Double>> {
    if (plotWidthDp <= 0.0) return emptyList()
    val gapDp = 2.0
    val keptSpans = mutableListOf<Pair<Double, Double>>()
    val kept = mutableListOf<Pair<LabelCandidate, Double>>()
    for (candidate in candidates.sortedByDescending { it.rank }) {
        val left = (candidate.centerDp - candidate.widthDp / 2.0)
            .coerceIn(0.0, max(0.0, plotWidthDp - candidate.widthDp))
        val right = left + candidate.widthDp
        val collides = keptSpans.any { (keptLeft, keptRight) ->
            left < keptRight + gapDp && right > keptLeft - gapDp
        }
        if (!collides) {
            keptSpans += left to right
            kept += candidate to left
        }
    }
    return kept.sortedBy { it.second }
}

/**
 * The cursor instant for a horizontal position on the axis (shared by
 * tap and drag). Positions arrive in PLOT-AREA coordinates: the
 * layered panel (ui-design §7.4, 0.8.7) insets its rows by
 * [PLOT_INSET_DP] on both sides, so callers subtract the inset from
 * the pointer position and pass the plot width as [widthPx].
 */
private fun cursorInstantAt(
    xPx: Float,
    widthPx: Float,
    timeline: TonightOverviewUseCase.Timeline,
): Instant {
    val fraction = if (widthPx > 0f) (xPx / widthPx).coerceIn(0f, 1f) else 0f
    return TimelineCursor.clampToAxis(
        TimelineCursor.truncatedToMinute(
            NightTimeline.instantAtFraction(
                fraction.toDouble(), timeline.axisStart, timeline.axisEnd,
            ),
        ),
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
 * The layered timeline (ui-design §7.4, 0.8.7 layout): one inset
 * panel whose rows share a single time scale — the sun band with its
 * stage names in-band, the moon band with the Moon-up spans and the
 * moonrise label, the marker row with the nine key moments as
 * label-free ticks (transparent tap hot zones at least 40dp wide over
 * the band rows), the target lanes (ui-design §7.5), and the
 * low-moonlight and galactic-centre window bands as rows of their own.
 *
 * Degradation (ui-design §7.4): without an astronomical night the
 * low-moonlight and galactic-centre rows are absent and the explicit
 * no-night note shows above the panel; the sun band then simply has
 * no astronomical-night segment.
 *
 * Information is never carried by hue alone: the sun band's segments
 * carry their stage names in text; the low-moonlight band has a
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
    moonrise: Instant? = null,
    cursorInstant: Instant? = null,
    cursorActive: Boolean = false,
    bubbleTimeText: String? = null,
    bubbleMoonText: String? = null,
    bubbleSnapLabel: String? = null,
    arcs: List<TimelineArc> = emptyList(),
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
            // The night's Moon phase illumination (ui-design §7.4):
            // a fact of the moon band below, kept as text so it never
            // rides on colour alone.
            Text(
                text = stringResource(
                    R.string.timeline_moon_note,
                    percentRounded(moonIllumination),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val widthDp = maxWidth.value
                val plotWidthDp = max(0.0, widthDp - 2.0 * PLOT_INSET_DP.value.toDouble())
                Column {
                    // Layered panel (ui-design §7.4, 0.8.7): one inset
                    // panel whose rows share the plot area between the
                    // PLOT_INSET_DP insets — sun band, moon band,
                    // marker row, target lanes, then the low-moonlight
                    // and galactic-centre rows. The cursor gesture
                    // detectors live on the panel; the coexistence rule
                    // documented on the card applies unchanged (child
                    // clickables — the mark hot zones and the lanes —
                    // consume a tap first, so a tap that opens a
                    // detail never places the cursor).
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.background,
                                RoundedCornerShape(10.dp),
                            )
                            .semantics {
                                contentDescription = chartDescription
                            }
                            .pointerInput(timeline.axisStart, timeline.axisEnd) {
                                // Cursor tap (ui-design §7.5): pointer
                                // positions convert to plot-area
                                // coordinates before mapping to instants.
                                detectTapGestures(
                                    onTap = { offset ->
                                        onCursorTap(
                                            cursorInstantAt(
                                                offset.x - PLOT_INSET_DP.toPx(),
                                                (size.width - 2 * PLOT_INSET_DP.toPx()),
                                                timeline,
                                            ),
                                        )
                                    },
                                )
                            }
                            .pointerInput(timeline.axisStart, timeline.axisEnd) {
                                // Direction-aware drag (interaction fix):
                                // the first slop-crossing movement decides
                                // the axis. A vertically dominant drag is
                                // NOT consumed, so the page scroll keeps
                                // it; only horizontal drags move the
                                // cursor. (detectDragGestures consumed
                                // every axis and made the chart — the
                                // page's largest block — a scroll trap.)
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
                                                        change.position.x - PLOT_INSET_DP.toPx(),
                                                        (size.width - 2 * PLOT_INSET_DP.toPx()),
                                                        timeline,
                                                    ),
                                                )
                                            }
                                        } else if (horizontal == true && plainDragMovesCursor) {
                                            change.consume()
                                            onCursorDrag(
                                                cursorInstantAt(
                                                    change.position.x - PLOT_INSET_DP.toPx(),
                                                    (size.width - 2 * PLOT_INSET_DP.toPx()),
                                                    timeline,
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
                                            cursorInstantAt(
                                                x - PLOT_INSET_DP.toPx(),
                                                (size.width - 2 * PLOT_INSET_DP.toPx()),
                                                timeline,
                                            ),
                                        )
                                    },
                                )
                            },
                    ) {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = PLOT_INSET_DP,
                                vertical = 6.dp,
                            ),
                        ) {
                            // Sun band, moon band and marker row, with
                            // the marks' transparent tap hot zones
                            // overlaid on this section: 40dp wide and
                            // the section's full height, so a mark is
                            // tappable well beyond its tick line
                            // (ui-design §7.4). Where marks are closer
                            // than 40dp the zones overlap and the later
                            // mark wins the overlap.
                            Box {
                                Column {
                                    SunBandRow(
                                        timeline = timeline,
                                        redLight = redLight,
                                        plotWidthDp = plotWidthDp,
                                    )
                                    Spacer(modifier = Modifier.height(5.dp))
                                    MoonBandRow(
                                        timeline = timeline,
                                        moonrise = moonrise,
                                        zone = zone,
                                        moonColor = moonColor,
                                        plotWidthDp = plotWidthDp,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    MarkerRow(
                                        timeline = timeline,
                                        marks = marks,
                                        tickColor = borderColor,
                                        plotWidthDp = plotWidthDp,
                                    )
                                }
                                for (mark in marks) {
                                    val fraction = NightTimeline.fractionOf(
                                        mark.instant, timeline.axisStart, timeline.axisEnd,
                                    )
                                    val leftDp = (fraction * plotWidthDp - 20.0)
                                        .coerceIn(0.0, max(0.0, plotWidthDp - 40.0))
                                    Box(
                                        modifier = Modifier
                                            .offset(x = leftDp.dp)
                                            .width(40.dp)
                                            .fillMaxHeight()
                                            .clickable { selectedMark = mark }
                                            .semantics { contentDescription = mark.title },
                                    )
                                }
                            }
                            // Target-arc lanes (ui-design §7.5, the phase-1
                            // reserved slot; 0.5.1 revision): one TALL lane per
                            // displayed cursor target. The lane's bar is its
                            // ≥20° window segmented rising / culmination /
                            // setting: the culmination segment is gold-filled
                            // (in the red theme the same slot is the brightest
                            // red AND carries the culmination text, so the
                            // segment never reads by colour alone), rising is a
                            // mid-tone fill, setting a dimmer one. The target
                            // name sits inside the lane at its left-hand end;
                            // the culmination text sits inside the culmination
                            // segment when that segment is wide enough —
                            // otherwise it is omitted (never shrunk, never
                            // overlapped; the detail dialog always carries
                            // the same figures). Since 0.6.0 the recommended
                            // shooting window's overlap with the arc is framed
                            // with a thick bright border over the fills — a
                            // shape, not just a colour, so it survives the red
                            // theme. Tapping a lane opens its target's detail
                            // dialog.
                            if (cursorActive && arcs.isNotEmpty()) {
                                Column(
                                    modifier = Modifier.padding(top = 5.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    arcs.forEachIndexed { index, arc ->
                                        val baseColor = MaterialTheme.colorScheme.onSurfaceVariant
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
                                                fun xOf(instant: Instant): Float =
                                                    NightTimeline.fractionOf(
                                                        instant, timeline.axisStart, timeline.axisEnd,
                                                    ).toFloat() * size.width
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
                                                        sunColor.copy(alpha = 0.55f),
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
                                            // Name inside the lane at its
                                            // left-hand end (ui-design §7.5,
                                            // 0.8.8: the comparison board's C
                                            // drawing — a fixed reading start
                                            // for every lane, instead of the
                                            // name travelling with each
                                            // window's start), single line,
                                            // ellipsised at the lane edge.
                                            Text(
                                                text = arc.label,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style
                                                    .TextOverflow.Ellipsis,
                                                color = baseColor,
                                                modifier = Modifier
                                                    .offset(x = 6.dp, y = 5.dp)
                                                    .width(max(0.0, plotWidthDp - 10.0).dp),
                                            )
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
                                                    (spanEndFraction - spanStartFraction) * plotWidthDp
                                                val textWidthDp = estimateTextWidthDp(
                                                    culminationText, 10f,
                                                )
                                                if (spanWidthDp >= textWidthDp + 10.0) {
                                                    val textX = spanStartFraction * plotWidthDp +
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
                            // Window band rows (ui-design §7.4): the
                            // low-moonlight windows and the galactic-
                            // centre window as rows of their own, each
                            // named inside its first span (0.8.8). Both
                            // are absent without an astronomical night,
                            // and a row with nothing to show is not
                            // drawn at all.
                            if (timeline.hasAstronomicalNight &&
                                lowMoonlightWindows.isNotEmpty()
                            ) {
                                Spacer(modifier = Modifier.height(6.dp))
                                WindowBandRow(
                                    intervals = lowMoonlightWindows.mapNotNull { window ->
                                        NightTimeline.clampToAxis(
                                            window.start, window.end,
                                            timeline.axisStart, timeline.axisEnd,
                                        )
                                    },
                                    timeline = timeline,
                                    color = moonColor,
                                    effect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f)),
                                    label = stringResource(R.string.timeline_low_moon_band),
                                    plotWidthDp = plotWidthDp,
                                )
                            }
                            if (timeline.hasAstronomicalNight) {
                                timeline.galacticCenterInterval?.let { interval ->
                                    Spacer(modifier = Modifier.height(5.dp))
                                    WindowBandRow(
                                        intervals = listOf(interval),
                                        timeline = timeline,
                                        color = sunColor,
                                        effect = PathEffect.dashPathEffect(
                                            floatArrayOf(2f, 4f, 9f, 4f),
                                        ),
                                        label = stringResource(R.string.timeline_gc_visible),
                                        plotWidthDp = plotWidthDp,
                                    )
                                }
                            }
                        }
                        // Cursor (ui-design §7.5): the gold line runs
                        // the panel's full height with its knob at the
                        // top, drawn only while the cursor state is
                        // active.
                        if (cursorActive && cursorInstant != null) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val insetPx = PLOT_INSET_DP.toPx()
                                val cursorX = insetPx +
                                    NightTimeline.fractionOf(
                                        cursorInstant,
                                        timeline.axisStart,
                                        timeline.axisEnd,
                                    ).toFloat() * (size.width - 2 * insetPx)
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
                        // Cursor bubble (ui-design §7.5): since 0.8.7 a
                        // dark card surface with a gold border (the red
                        // theme's accent is its brightest red, so the
                        // border follows the theme) instead of the
                        // sun-phase fill; the three lines are unchanged
                        // and stay readable while dragging.
                        if (cursorActive && cursorInstant != null && bubbleTimeText != null) {
                            val fraction = NightTimeline.fractionOf(
                                cursorInstant, timeline.axisStart, timeline.axisEnd,
                            )
                            val bubbleWidth = 184.0
                            val bubbleLeft =
                                (PLOT_INSET_DP.value.toDouble() + fraction * plotWidthDp - bubbleWidth / 2)
                                    .coerceIn(0.0, max(0.0, widthDp - bubbleWidth))
                            Column(
                                modifier = Modifier
                                    .offset(x = bubbleLeft.dp, y = 14.dp)
                                    .width(bubbleWidth.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surface,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .border(1.dp, sunColor, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = bubbleTimeText,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (bubbleMoonText != null) {
                                    Text(
                                        text = bubbleMoonText,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                if (bubbleSnapLabel != null) {
                                    Text(
                                        text = bubbleSnapLabel,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                    // No legend row under the panel (0.8.8): each
                    // window band row names itself inside its span, and
                    // the sun / moon bands carry their own in-band
                    // labels, so a separate legend would only repeat
                    // them.
                    // Axis time labels: start, thirds, end.
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = PLOT_INSET_DP),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        listOf(0.0, 1.0 / 3.0, 2.0 / 3.0, 1.0).forEach { fraction ->
                            val instant = NightTimeline.instantAtFraction(
                                fraction, timeline.axisStart, timeline.axisEnd,
                            )
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
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = PLOT_INSET_DP)
                            .height(6.dp),
                    ) {
                        if (cursorActive && cursorInstant != null) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val cursorX = NightTimeline.fractionOf(
                                    cursorInstant, timeline.axisStart, timeline.axisEnd,
                                ).toFloat() * size.width
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
        DetailDialog(
            title = mark.title,
            lines = mark.lines,
            onDismiss = { selectedMark = null },
        )
    }
}

/**
 * Gradient colour of one solar band in the sun band strip (ui-design
 * §7.4, 0.8.8; values from the approved comparison board): daytime
 * amber through twilight blues to the astronomical night's deep blue;
 * the red theme maps the same luminance steps onto its red scale.
 */
private fun sunBandColor(band: NightTimeline.SunBand, redLight: Boolean): Color =
    if (redLight) {
        when (band) {
            NightTimeline.SunBand.DAY -> Color(0xFF8F2B21)
            NightTimeline.SunBand.CIVIL_TWILIGHT -> Color(0xFF701C13)
            NightTimeline.SunBand.NAUTICAL_TWILIGHT -> Color(0xFF57150E)
            NightTimeline.SunBand.ASTRONOMICAL_TWILIGHT -> Color(0xFF3F0F0A)
            NightTimeline.SunBand.ASTRONOMICAL_NIGHT -> Color(0xFF2B0806)
        }
    } else {
        when (band) {
            NightTimeline.SunBand.DAY -> Color(0xFF8A5A20)
            NightTimeline.SunBand.CIVIL_TWILIGHT -> Color(0xFF3D4E7D)
            NightTimeline.SunBand.NAUTICAL_TWILIGHT -> Color(0xFF2B3C68)
            NightTimeline.SunBand.ASTRONOMICAL_TWILIGHT -> Color(0xFF1C2A52)
            NightTimeline.SunBand.ASTRONOMICAL_NIGHT -> Color(0xFF101C42)
        }
    }

/**
 * The sun band row of the layered panel (ui-design §7.4, 0.8.8): one
 * strip filled with a multi-stop gradient whose stops sit at the band
 * segments' boundaries — daytime amber, twilight blues, the night's
 * deep blue, and back out at dawn (the comparison board's C drawing,
 * replacing the 0.8.7 single-hue alpha segments). Short labels ride
 * in the band: sunset and sunrise at their instants, dusk over the
 * evening twilight span, dawn over the morning one, and the night
 * label centred on the astronomical night. Placement follows the
 * shared priority rule ([placeLabels]): when two labels would
 * overlap, the lower rank is omitted — the night outranks dusk and
 * dawn, which outrank sunset and sunrise.
 */
@Composable
private fun SunBandRow(
    timeline: TonightOverviewUseCase.Timeline,
    redLight: Boolean,
    plotWidthDp: Double,
) {
    val labelColor = MaterialTheme.colorScheme.onBackground
    val segments = timeline.bandSegments
    fun fractionOf(instant: Instant): Double =
        NightTimeline.fractionOf(instant, timeline.axisStart, timeline.axisEnd)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SUN_BAND_HEIGHT_DP)
            .clip(RoundedCornerShape(4.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (segments.isEmpty()) {
                drawRect(
                    color = sunBandColor(NightTimeline.SunBand.ASTRONOMICAL_NIGHT, redLight),
                    size = size,
                )
            } else {
                val stops = buildList {
                    add(0f to sunBandColor(segments.first().band, redLight))
                    for (segment in segments) {
                        val mid = (
                            (fractionOf(segment.start) + fractionOf(segment.end)) / 2.0
                            ).toFloat()
                        add(mid to sunBandColor(segment.band, redLight))
                    }
                    add(1f to sunBandColor(segments.last().band, redLight))
                }
                drawRect(
                    brush = Brush.horizontalGradient(colorStops = stops.toTypedArray()),
                    size = size,
                )
            }
        }
        // Short labels (ui-design §7.4): anchors derive from the same
        // segment list the gradient uses, so a label never claims a
        // stage the strip does not show.
        val sunsetText = stringResource(R.string.timeline_sun_sunset)
        val sunriseText = stringResource(R.string.timeline_sun_sunrise)
        val duskText = stringResource(R.string.timeline_sun_dusk)
        val dawnText = stringResource(R.string.timeline_sun_dawn)
        val nightText = stringResource(R.string.timeline_sun_night)
        val candidates = buildList {
            fun addLabel(text: String, centerFraction: Double, rank: Int) {
                add(
                    LabelCandidate(
                        text = text,
                        centerDp = centerFraction * plotWidthDp,
                        widthDp = estimateTextWidthDp(text, 9f),
                        rank = rank,
                    ),
                )
            }
            val twilightBands = setOf(
                NightTimeline.SunBand.CIVIL_TWILIGHT,
                NightTimeline.SunBand.NAUTICAL_TWILIGHT,
                NightTimeline.SunBand.ASTRONOMICAL_TWILIGHT,
            )
            val nightSegments = segments.filter {
                it.band == NightTimeline.SunBand.ASTRONOMICAL_NIGHT
            }
            val twilightSegments = segments.filter { it.band in twilightBands }
            if (nightSegments.isNotEmpty()) {
                addLabel(
                    nightText,
                    (fractionOf(nightSegments.first().start) +
                        fractionOf(nightSegments.last().end)) / 2.0,
                    rank = 3,
                )
            }
            if (twilightSegments.isNotEmpty()) {
                val twilightStart = fractionOf(twilightSegments.first().start)
                val twilightEnd = fractionOf(twilightSegments.last().end)
                if (nightSegments.isNotEmpty()) {
                    val nightStart = fractionOf(nightSegments.first().start)
                    val nightEnd = fractionOf(nightSegments.last().end)
                    if (twilightStart < nightStart) {
                        addLabel(duskText, (twilightStart + nightStart) / 2.0, rank = 2)
                    }
                    if (nightEnd < twilightEnd) {
                        addLabel(dawnText, (nightEnd + twilightEnd) / 2.0, rank = 2)
                    }
                } else {
                    // No night segment: the twilight span is one dip —
                    // dusk takes its falling half, dawn its rising half.
                    val mid = (twilightStart + twilightEnd) / 2.0
                    addLabel(duskText, (twilightStart + mid) / 2.0, rank = 2)
                    addLabel(dawnText, (mid + twilightEnd) / 2.0, rank = 2)
                }
            }
            if (segments.size > 1) {
                if (segments.first().band == NightTimeline.SunBand.DAY) {
                    addLabel(sunsetText, fractionOf(segments[1].start), rank = 1)
                }
                if (segments.last().band == NightTimeline.SunBand.DAY) {
                    addLabel(sunriseText, fractionOf(segments.last().start), rank = 1)
                }
            }
        }
        for ((label, leftDp) in placeLabels(candidates, plotWidthDp)) {
            Text(
                text = label.text,
                fontSize = 9.sp,
                maxLines = 1,
                color = labelColor,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = leftDp.dp),
            )
        }
    }
}

/**
 * The moon band row (ui-design §7.4, 0.8.7): the Moon-up spans derived
 * from the samples in the domain layer, on a surface-variant track.
 * When the Moon rises inside the axis, the rise is labelled at the
 * span's start with the exact moment (passed in from the caller's
 * key-moment list, not the sample grid) whenever the label fits
 * before the plot's right edge. A moonrise outside the axis gets no
 * label at all (0.8.8): [NightTimeline.isWithinAxis] decides first —
 * the same intersect-then-present rule the window bands apply via
 * clampToAxis — instead of letting the fraction clamp pin an
 * afternoon moonrise to the plot's left edge.
 */
@Composable
private fun MoonBandRow(
    timeline: TonightOverviewUseCase.Timeline,
    moonrise: Instant?,
    zone: ZoneId,
    moonColor: Color,
    plotWidthDp: Double,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(MOON_BAND_HEIGHT_DP)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(4.dp),
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            fun xOf(instant: Instant): Float =
                NightTimeline.fractionOf(
                    instant, timeline.axisStart, timeline.axisEnd,
                ).toFloat() * size.width
            for (interval in timeline.moonAboveIntervals) {
                drawRect(
                    color = moonColor.copy(alpha = 0.25f),
                    topLeft = Offset(xOf(interval.start), 0f),
                    size = Size(xOf(interval.end) - xOf(interval.start), size.height),
                )
            }
        }
        if (moonrise != null &&
            NightTimeline.isWithinAxis(moonrise, timeline.axisStart, timeline.axisEnd)
        ) {
            val text = stringResource(
                R.string.timeline_moon_rise, formatTime(moonrise, zone),
            )
            val textWidthDp = estimateTextWidthDp(text, 9f)
            val textX = NightTimeline.fractionOf(
                moonrise, timeline.axisStart, timeline.axisEnd,
            ) * plotWidthDp + 4.0
            if (textX + textWidthDp <= plotWidthDp - 2.0) {
                Text(
                    text = text,
                    fontSize = 9.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = textX.dp),
                )
            }
        }
    }
}

/**
 * The marker row (ui-design §7.4, 0.8.8): the nine key moments as
 * ticks on their own row of the layered panel, the main marks
 * carrying a short text label under the tick (short name plus the
 * moment for the Sun and night marks; the galactic-centre mark is
 * name-only) — the comparison board's C drawing. Labels are centred
 * on their tick and placed by the shared priority rule
 * ([placeLabels]): a label that would overlap a higher-ranked
 * neighbour is omitted, never shrunk or shifted into it. The tap hot
 * zones live with the panel (see NightTimelineCard), spanning the
 * band rows, and are unaffected by the labels.
 */
@Composable
private fun MarkerRow(
    timeline: TonightOverviewUseCase.Timeline,
    marks: List<TimelineMark>,
    tickColor: Color,
    plotWidthDp: Double,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(MARKER_ROW_HEIGHT_DP),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            fun xOf(instant: Instant): Float =
                NightTimeline.fractionOf(
                    instant, timeline.axisStart, timeline.axisEnd,
                ).toFloat() * size.width
            for (mark in marks) {
                drawLine(
                    color = tickColor.copy(alpha = 0.6f),
                    start = Offset(xOf(mark.instant), 2.dp.toPx()),
                    end = Offset(xOf(mark.instant), 10.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
        val candidates = marks.mapNotNull { mark ->
            mark.shortLabel?.let { label ->
                LabelCandidate(
                    text = label,
                    centerDp = NightTimeline.fractionOf(
                        mark.instant, timeline.axisStart, timeline.axisEnd,
                    ) * plotWidthDp,
                    widthDp = estimateTextWidthDp(label, 9f),
                    rank = mark.labelRank,
                )
            }
        }
        for ((label, leftDp) in placeLabels(candidates, plotWidthDp)) {
            Text(
                text = label.text,
                fontSize = 9.sp,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.offset(x = leftDp.dp, y = 13.dp),
            )
        }
    }
}

/**
 * One window band row of the layered panel (ui-design §7.4): the
 * low-moonlight windows (dashed border) and the galactic-centre
 * window (dot-dash border) share this drawing — a filled span with a
 * patterned outline on a surface-variant track, so the two bands read
 * by line style, not hue alone, in both themes. Since 0.8.8 the row
 * also names itself: [label] sits inside the first window span at its
 * start, in the row's own colour, so the row reads without the old
 * legend row under the panel; a label wider than its span is omitted
 * rather than spilling over the track.
 */
@Composable
private fun WindowBandRow(
    intervals: List<NightTimeline.Interval>,
    timeline: TonightOverviewUseCase.Timeline,
    color: Color,
    effect: PathEffect,
    label: String,
    plotWidthDp: Double,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(WINDOW_BAND_HEIGHT_DP)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(3.dp),
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            fun xOf(instant: Instant): Float =
                NightTimeline.fractionOf(
                    instant, timeline.axisStart, timeline.axisEnd,
                ).toFloat() * size.width
            for (interval in intervals) {
                val left = xOf(interval.start)
                val right = xOf(interval.end)
                drawRect(
                    color = color.copy(alpha = 0.25f),
                    topLeft = Offset(left, 0f),
                    size = Size(right - left, size.height),
                )
                val outline = Path().apply {
                    moveTo(left, 0f)
                    lineTo(right, 0f)
                    lineTo(right, size.height)
                    lineTo(left, size.height)
                    close()
                }
                drawPath(
                    path = outline,
                    color = color.copy(alpha = 0.95f),
                    style = Stroke(width = 1.2.dp.toPx(), pathEffect = effect),
                )
            }
        }
        val first = intervals.firstOrNull()
        if (first != null) {
            val startFraction = NightTimeline.fractionOf(
                first.start, timeline.axisStart, timeline.axisEnd,
            )
            val endFraction = NightTimeline.fractionOf(
                first.end, timeline.axisStart, timeline.axisEnd,
            )
            val spanWidthDp = (endFraction - startFraction) * plotWidthDp
            val labelWidthDp = estimateTextWidthDp(label, 8f)
            if (labelWidthDp + 8.0 <= spanWidthDp) {
                Text(
                    text = label,
                    fontSize = 8.sp,
                    maxLines = 1,
                    color = color,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = (startFraction * plotWidthDp + 4.0).dp),
                )
            }
        }
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
