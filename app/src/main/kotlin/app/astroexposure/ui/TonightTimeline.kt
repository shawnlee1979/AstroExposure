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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import app.astroexposure.R
import app.astroexposure.domain.timeline.NightTimeline
import app.astroexposure.domain.timeline.TimelineCursor
import app.astroexposure.ui.theme.LocalStateColors
import app.astroexposure.usecase.TonightOverviewUseCase
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One tappable mark on the timeline (ui-design §7.4): the mark's
 * position on the marker row is the mark — since 0.8.9 the row draws
 * no ticks, only the main marks' text labels. Tapping (through the
 * transparent hot zones over the band rows) opens a detail dialog
 * whose [title] and [lines] are resolved by the caller: Sun marks
 * open the Sun detail, Moon marks the Moon detail, twilight marks
 * show the moment and its solar altitude, and the galactic-centre
 * mark shows the culmination time and altitude. The main marks
 * (sunrise / sunset, the astronomical night's start and end, the
 * galactic-centre culmination) carry a [shortLabel]; [labelRank]
 * decides which labels survive when neighbouring labels would
 * overlap — the night's start and end rank highest.
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
 * where the target is at or above 20° and its display name. Since
 * 0.8.9 the lane draws the target's REAL altitude curve from
 * [altitudeSamples] — the use case's 5-minute samples across the
 * whole axis, the same series [intervals] and [phases] derive from —
 * filled under the curve inside the ≥20° window, with the
 * culmination span's fill in the accent. (Before 0.8.9 the lane was
 * three flat segments and no curve was ever drawn; the comparison
 * board's arcs were design sketches.) The lane carries NO
 * culmination text: culmination figures live in the cursor bubble
 * and the detail dialog. When [altitudeSamples] is empty the lane
 * degrades to its name plus the recommended frame — never back to
 * the retired flat segments.
 */
data class TimelineArc(
    val intervals: List<NightTimeline.Interval>,
    val label: String,
    val altitudeSamples: List<TimelineCursor.AltitudeSample> = emptyList(),
    val phases: TimelineCursor.ArcPhases? = null,
    /**
     * The recommended shooting window cut to this lane's arc — drawn
     * as a bright frame over the curve fills (ui-design §7.5, 0.6.0).
     * The frame is a border SHAPE over the fills, so it stays legible
     * in the red theme where hues collapse into one family.
     */
    val recommendedFrame: NightTimeline.Interval? = null,
)

/** Arc lane visual height (ui-design §7.5): the comparison board's compact 26dp lane. */
private val ARC_LANE_HEIGHT_DP = 26.dp

/**
 * Layered-panel row metrics (ui-design §7.4, 0.8.7; layout from the
 * approved comparison board): the rows are inset by [PLOT_INSET_DP]
 * on both sides of the panel, and every row — bands, marker row,
 * lanes, hour axis — shares the plot area between the insets, so a
 * single fraction-to-x mapping serves the whole panel. The board's
 * proportions are the layout reference, but since 0.8.10 the
 * physical row heights and in-row font sizes follow real-device
 * readability instead: the board-scale rows (16/12/14/10/12dp with
 * 8.5–9sp text) were illegible on a real phone, so the band rows are
 * enlarged (24/18/22/16/18dp, 10–11sp text). The lanes keep the
 * board's compact 26dp (see [ARC_LANE_HEIGHT_DP]).
 */
private val PLOT_INSET_DP = 8.dp
private val SUN_BAND_HEIGHT_DP = 24.dp
private val MOON_BAND_HEIGHT_DP = 18.dp
private val MARKER_ROW_HEIGHT_DP = 22.dp
private val WINDOW_BAND_HEIGHT_DP = 16.dp
private val HOUR_AXIS_ROW_HEIGHT_DP = 18.dp

/**
 * Height of the Sun/Moon track band (ui-design §7.4, 0.8.11): the
 * cursor state's top row, tall enough to read the two altitude
 * curves against the horizon and to serve as the cursor's drag
 * surface. Present only while the cursor state is active.
 */
private val TRACK_BAND_HEIGHT_DP = 64.dp

/** Panel inner padding and the band-section row gaps (ui-design §7.4). */
private val PANEL_TOP_PADDING_DP = 6.dp
private val PANEL_BOTTOM_PADDING_DP = 4.dp
private val TRACK_SUN_GAP_DP = 5.dp
private val SUN_MOON_GAP_DP = 5.dp
private val MOON_MARKER_GAP_DP = 2.dp

/**
 * Vertical offset of the cursor bubble's top edge inside the panel
 * (ui-design §7.5): since 0.8.11 the bubble hangs over the track
 * band, so its top edge is the panel's top padding — the track
 * band's own top edge — derived from the panel metrics instead of
 * freezing a number. (Before 0.8.11 it hung at the top of the lane
 * area and covered the lanes' left ends.)
 */
private val BUBBLE_TOP_DP = PANEL_TOP_PADDING_DP

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
 * moonrise label, the marker row with the main marks' text labels
 * (no ticks since 0.8.9; transparent tap hot zones at least 40dp
 * wide over the band rows), the target lanes (ui-design §7.5), the
 * low-moonlight and galactic-centre window bands as rows of their
 * own, and the whole-hour axis closing the panel (0.8.9).
 *
 * Degradation (ui-design §7.4): without an astronomical night the
 * low-moonlight and galactic-centre rows are absent and the explicit
 * no-night note shows above the panel; the sun band then simply has
 * no astronomical-night segment.
 *
 * Information is never carried by hue alone: the sun band's segments
 * carry their stage names in text, and each window band names itself
 * inside its first span (0.8.8), so the two window bands read by
 * their labels as well as their fills in both themes.
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
 * cancels any hot-zone press in progress. The lane tap overlays are
 * the opposite arrangement (0.8.11): they only OBSERVE a press and
 * never consume it, so panel drags and the long-press grab work
 * starting on a lane; the overlay fires its tap only when the press
 * ends as a short, un-consumed, in-slop tap.
 *
 * Anti-mistouch (ui-design §7.5, 0.5.2): the plain tap and drag paths
 * above place the cursor only while none exists — the drag detector
 * snapshots that at gesture start, so the gesture that first places
 * the cursor keeps following the finger, while any later plain drag
 * is inert. Once the cursor is placed, repositioning goes through the
 * long-press drag detector (hold ~300ms to grab, then drag) — or,
 * since 0.8.11, through a direct drag on the Sun/Moon track band at
 * the panel's top, the one deliberate scrub surface (its own callback,
 * [onTrackDrag], permissioned as TRACK_DRAG in the state machine).
 * While [cursorLocked] is set, no axis gesture moves the cursor at
 * all and only the time bar's step buttons (deliberate actions) still
 * apply. The pure state machine behind this lives in
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
    /** The cursor's solar-segment name for the bubble's first line (ui-design §7.5, 0.8.9). */
    bubbleSegmentText: String? = null,
    /** The bubble's third line: moonlight-interference tier and Bortle grade (ui-design §7.5, 0.8.9). */
    bubbleTierText: String? = null,
    arcs: List<TimelineArc> = emptyList(),
    onCursorTap: (Instant) -> Unit = {},
    onCursorDrag: (Instant) -> Unit = {},
    /** Direct tap/drag on the Sun/Moon track band (ui-design §7.5, 0.8.11). */
    onTrackDrag: (Instant) -> Unit = {},
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
    // Dim text of the marker row and the hour axis (design token tx3
    // #5D6C8C; the red theme's muted red equivalent).
    val axisTextColor = if (redLight) Color(0xFFC05348) else Color(0xFF5D6C8C)
    val okColor = LocalStateColors.current.ok
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
                                start = PLOT_INSET_DP,
                                end = PLOT_INSET_DP,
                                top = PANEL_TOP_PADDING_DP,
                                bottom = PANEL_BOTTOM_PADDING_DP,
                            ),
                        ) {
                            // Sun/Moon track band (ui-design §7.4,
                            // 0.8.11): the cursor state's top row —
                            // the sampled Sun and Moon altitude curves
                            // over the shared time scale, and the
                            // cursor's direct drag surface (see
                            // TrackBandRow). Absent without the cursor
                            // state, so the default panel is unchanged.
                            if (cursorActive) {
                                TrackBandRow(
                                    timeline = timeline,
                                    sunColor = sunColor,
                                    moonColor = moonColor,
                                    horizonColor = borderColor,
                                    enabled = { !cursorLockedState.value },
                                    onDragTo = { xPx, widthPx ->
                                        onTrackDrag(
                                            cursorInstantAt(xPx, widthPx, timeline),
                                        )
                                    },
                                )
                                Spacer(modifier = Modifier.height(TRACK_SUN_GAP_DP))
                            }
                            // Sun band, moon band and marker row, with
                            // the marks' transparent tap hot zones
                            // overlaid on this section: 40dp wide and
                            // the section's full height, so a mark is
                            // tappable well beyond its label
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
                                    Spacer(modifier = Modifier.height(SUN_MOON_GAP_DP))
                                    MoonBandRow(
                                        timeline = timeline,
                                        moonrise = moonrise,
                                        zone = zone,
                                        moonColor = moonColor,
                                        plotWidthDp = plotWidthDp,
                                    )
                                    Spacer(modifier = Modifier.height(MOON_MARKER_GAP_DP))
                                    MarkerRow(
                                        timeline = timeline,
                                        marks = marks,
                                        textColor = axisTextColor,
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
                            // Target-arc lanes (ui-design §7.5): one
                            // compact 26dp lane per displayed cursor
                            // target, 5dp apart. Each lane draws the
                            // target's REAL altitude curve from its
                            // 5-minute samples (0.8.9 — the first true
                            // curve lanes; the pre-0.8.9 flat rising /
                            // culmination / setting rectangles are
                            // retired): inside the ≥20° window the area
                            // under the curve is filled, the culmination
                            // span's under-curve area takes the accent
                            // fill, the curve itself is stroked, and the
                            // recommended window is framed over the
                            // fills. The lane carries the target name at
                            // its left end and NO culmination text —
                            // culmination figures live in the bubble and
                            // the detail dialog. Tap targets are
                            // transparent overlays over the visual lanes,
                            // 31dp tall (visual lane plus its share of
                            // the row gap), so the visual rhythm stays
                            // compact while taps still land. Since
                            // 0.8.11 the overlays only OBSERVE the press
                            // (ArcTapOverlay): a consuming clickable here
                            // ate the panel's drag and long-press
                            // gestures whole — in the cursor state the
                            // lanes cover most of the panel, so the
                            // cursor could no longer be dragged at all.
                            // Tapping a lane opens its target's detail
                            // dialog.
                            if (cursorActive && arcs.isNotEmpty()) {
                                Box(modifier = Modifier.padding(top = 5.dp)) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        arcs.forEach { arc ->
                                            ArcLane(
                                                arc = arc,
                                                timeline = timeline,
                                                accent = sunColor,
                                            )
                                        }
                                    }
                                    Column {
                                        arcs.forEachIndexed { index, arc ->
                                            ArcTapOverlay(
                                                label = arc.label,
                                                height = if (index < arcs.lastIndex) {
                                                    31.dp
                                                } else {
                                                    ARC_LANE_HEIGHT_DP
                                                },
                                                onTap = { onArcTap(index) },
                                            )
                                        }
                                    }
                                }
                            }
                            // Window band rows (ui-design §7.4): the
                            // low-moonlight windows and the galactic-
                            // centre window as rows of their own, each
                            // named inside its first span (0.8.8). Since
                            // 0.8.9 both are plain soft fills (ok-green /
                            // gold) with no patterned frame — the row
                            // name, not a line style, tells them apart.
                            // Both are absent without an astronomical
                            // night, and a row with nothing to show is
                            // not drawn at all.
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
                                    fillColor = okColor.copy(alpha = 0.22f),
                                    label = stringResource(R.string.timeline_low_moon_band),
                                    labelColor = okColor,
                                    plotWidthDp = plotWidthDp,
                                )
                            }
                            if (timeline.hasAstronomicalNight) {
                                timeline.galacticCenterInterval?.let { interval ->
                                    Spacer(modifier = Modifier.height(5.dp))
                                    WindowBandRow(
                                        intervals = listOf(interval),
                                        timeline = timeline,
                                        fillColor = sunColor.copy(alpha = 0.19f),
                                        label = stringResource(R.string.timeline_gc_visible),
                                        labelColor = sunColor,
                                        plotWidthDp = plotWidthDp,
                                    )
                                }
                            }
                            // Whole-hour axis (ui-design §7.4, 0.8.9;
                            // sparse stepping since 0.8.10): the
                            // panel's closing row — whole hours at the
                            // picked step, each at its true position on
                            // the shared time scale (not an equal
                            // division of the axis). This is the
                            // panel's only axis labelling; the former
                            // four-point row under the panel is gone.
                            Spacer(modifier = Modifier.height(6.dp))
                            HourAxisRow(
                                timeline = timeline,
                                zone = zone,
                                textColor = axisTextColor,
                                plotWidthDp = plotWidthDp,
                            )
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
                        // Cursor bubble (ui-design §7.5): a dark card
                        // surface with a gold border (the red theme's
                        // accent is its brightest red, so the border
                        // follows the theme). Three lines since 0.8.9,
                        // aligned with the comparison board: the time
                        // with its solar segment (a snapped moment's
                        // name takes the time's place, its own time
                        // being the cursor's), the Moon altitude with
                        // the phase illumination, and the moonlight
                        // tier with the site's Bortle grade. The width
                        // hugs the widest line (capped at the panel
                        // width minus 16dp), and the bubble hangs over
                        // the track band (BUBBLE_TOP_DP, 0.8.11)
                        // instead of covering the lanes and their
                        // target names.
                        if (cursorActive && cursorInstant != null && bubbleTimeText != null) {
                            val fraction = NightTimeline.fractionOf(
                                cursorInstant, timeline.axisStart, timeline.axisEnd,
                            )
                            val firstLine = buildString {
                                append(bubbleSnapLabel ?: bubbleTimeText)
                                if (bubbleSegmentText != null) {
                                    append(" · ")
                                    append(bubbleSegmentText)
                                }
                            }
                            val bubbleLines = listOfNotNull(
                                firstLine, bubbleMoonText, bubbleTierText,
                            )
                            val bubbleWidth = minOf(
                                bubbleLines.maxOfOrNull { line ->
                                    estimateTextWidthDp(line, 13f)
                                } ?: 0.0,
                                max(0.0, widthDp - 16.0),
                            ) + 16.0
                            val bubbleLeft =
                                (PLOT_INSET_DP.value.toDouble() + fraction * plotWidthDp - bubbleWidth / 2)
                                    .coerceIn(0.0, max(0.0, widthDp - bubbleWidth))
                            Column(
                                modifier = Modifier
                                    .offset(x = bubbleLeft.dp, y = BUBBLE_TOP_DP)
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
                                    text = firstLine,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                )
                                if (bubbleMoonText != null) {
                                    Text(
                                        text = bubbleMoonText,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                    )
                                }
                                if (bubbleTierText != null) {
                                    Text(
                                        text = bubbleTierText,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                    // No legend row under the panel (0.8.8) and no
                    // axis row either (0.8.9): each window band names
                    // itself inside its span, and the whole-hour axis
                    // closes the panel itself.
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
 * The Sun/Moon track band (ui-design §7.4, 0.8.11): the cursor
 * state's top row. Both bodies' real altitude curves ride on the
 * panel's shared time scale — the Sun as a solid line in the accent
 * colour, the Moon as a dashed line in the Moon colour — over the
 * vertical domain of [NightTimeline.altitudeFraction] (−36°…+90°),
 * with a thin horizon line at 0°. Identity is carried by colour AND
 * line style: in the red theme both hues collapse into the red
 * scale and the solid/dashed distinction is what remains. The band
 * carries no text labels.
 *
 * The band is also the cursor's direct drag surface: a tap
 * positions the cursor and a horizontal drag scrubs it — no hold
 * needed. The detector mirrors the panel drag's direction rule:
 * nothing is consumed or reported until the first slop-crossing
 * move proves horizontal (a vertically dominant press is left to
 * the page scroll); horizontal moves are then consumed and
 * reported; a press released undecided counts as a tap on its down
 * position. A press that another handler consumed mid-flight is
 * abandoned. While [enabled] is false at press time (cursor
 * locked) the press is ignored entirely and nothing is consumed.
 * Positions are the band's own coordinates — the band spans
 * exactly the plot width between the panel insets.
 */
@Composable
private fun TrackBandRow(
    timeline: TonightOverviewUseCase.Timeline,
    sunColor: Color,
    moonColor: Color,
    horizonColor: Color,
    enabled: () -> Boolean,
    onDragTo: (xPx: Float, widthPx: Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TRACK_BAND_HEIGHT_DP)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(4.dp),
            )
            .pointerInput(timeline.axisStart, timeline.axisEnd) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!enabled()) return@awaitEachGesture
                    val pointerId: PointerId = down.id
                    var totalDx = 0f
                    var totalDy = 0f
                    var horizontal: Boolean? = null
                    var spoiled = false
                    var change = currentEvent.changes
                        .firstOrNull { it.id == pointerId }
                    while (change != null && !change.changedToUp()) {
                        val event = awaitPointerEvent()
                        change = event.changes
                            .firstOrNull { it.id == pointerId } ?: break
                        if (change.changedToUp()) break
                        if (change.isConsumed) {
                            spoiled = true
                            break
                        }
                        val delta = change.positionChange()
                        totalDx += delta.x
                        totalDy += delta.y
                        if (horizontal == null &&
                            (kotlin.math.abs(totalDx) > viewConfiguration.touchSlop ||
                                kotlin.math.abs(totalDy) > viewConfiguration.touchSlop)
                        ) {
                            horizontal =
                                kotlin.math.abs(totalDx) >= kotlin.math.abs(totalDy)
                            if (horizontal == true) {
                                change.consume()
                                onDragTo(change.position.x, size.width.toFloat())
                            }
                        } else if (horizontal == true) {
                            change.consume()
                            onDragTo(change.position.x, size.width.toFloat())
                        }
                    }
                    // Released undecided and never taken over by
                    // another handler: a tap — it positions the
                    // cursor at the down position.
                    if (change != null && change.changedToUp() &&
                        horizontal == null && !spoiled
                    ) {
                        onDragTo(down.position.x, size.width.toFloat())
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            fun xOf(instant: Instant): Float =
                NightTimeline.fractionOf(
                    instant, timeline.axisStart, timeline.axisEnd,
                ).toFloat() * size.width
            fun yOf(altitudeDeg: Double): Float =
                (1.0 - NightTimeline.altitudeFraction(altitudeDeg)).toFloat() *
                    size.height
            val horizonY = yOf(0.0)
            drawLine(
                color = horizonColor.copy(alpha = 0.6f),
                start = Offset(0f, horizonY),
                end = Offset(size.width, horizonY),
                strokeWidth = 1.dp.toPx(),
            )
            fun curve(samples: List<Offset>, color: Color, dashed: Boolean) {
                if (samples.size < 2) return
                val path = Path().apply {
                    moveTo(samples.first().x, samples.first().y)
                    samples.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = if (dashed) {
                            PathEffect.dashPathEffect(
                                floatArrayOf(4.dp.toPx(), 3.dp.toPx()),
                            )
                        } else {
                            null
                        },
                    ),
                )
            }
            val samples = timeline.samples
            curve(
                samples.map { Offset(xOf(it.instant), yOf(it.sunAltitudeDeg)) },
                sunColor,
                dashed = false,
            )
            curve(
                samples.map { Offset(xOf(it.instant), yOf(it.moonAltitudeDeg)) },
                moonColor,
                dashed = true,
            )
        }
    }
}

/**
 * One lane's transparent tap overlay (ui-design §7.5): covers the
 * visual lane (plus its share of the row gap) and turns a tap into
 * [onTap] — WITHOUT consuming anything. A consuming clickable in
 * this spot (the pre-0.8.11 arrangement) swallowed the panel's drag
 * and long-press gestures whole: in the cursor state the lanes
 * cover most of the panel, so the cursor could no longer be dragged
 * anywhere. This overlay only observes: the tap fires when the
 * press ends as a genuine tap — released within
 * [TimelineCursor.LONG_PRESS_REPOSITION_MS] (a longer hold belongs
 * to the long-press grab), never moved beyond the touch slop, and
 * no change consumed by another handler (a panel drag that takes
 * over consumes its moves). Accessibility keeps the old clickable's
 * contract through semantics: the target name as the description,
 * the button role and an explicit click action.
 */
@Composable
private fun ArcTapOverlay(
    label: String,
    height: Dp,
    onTap: () -> Unit,
) {
    val latestOnTap = rememberUpdatedState(onTap)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick {
                    latestOnTap.value()
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var spoiled = down.isConsumed
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes
                            .firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) spoiled = true
                        if ((change.position - down.position)
                                .getDistance() > viewConfiguration.touchSlop
                        ) {
                            spoiled = true
                        }
                        if (change.changedToUp()) {
                            if (!spoiled &&
                                change.uptimeMillis - down.uptimeMillis <
                                TimelineCursor.LONG_PRESS_REPOSITION_MS
                            ) {
                                latestOnTap.value()
                            }
                            break
                        }
                        if (!change.pressed) break
                    }
                }
            },
    )
}

/**
 * One target lane of the layered panel (ui-design §7.5, 0.8.9): a
 * compact 26dp container (card2 surface, 1dp border, 6dp corners)
 * holding the target's real altitude curve. Altitude maps linearly
 * onto the lane's vertical: the target's culmination altitude is the
 * full scale, the curve's peak leaves a 2dp headroom and altitude 0
 * sits on the lane's bottom edge. Only the ≥20° window is drawn —
 * the curve enters and leaves at the 20° crossings interpolated
 * between samples — with the area under the curve filled, the
 * culmination span's under-curve area in the accent, and the curve
 * stroked over both. The recommended frame (1.5dp, 5dp corners,
 * inset 1.5dp from the lane's top and bottom) sits over the fills.
 * The name rides at the lane's left end over the fill, with a soft
 * dark shadow for legibility. Without samples the lane degrades to
 * the name plus the frame — the retired flat segments never return.
 */
@Composable
private fun ArcLane(
    arc: TimelineArc,
    timeline: TonightOverviewUseCase.Timeline,
    accent: Color,
) {
    val laneShape = RoundedCornerShape(6.dp)
    val containerColor = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val windowFill = MaterialTheme.colorScheme.secondaryContainer
    val culminationFill = accent.copy(alpha = 0.33f)
    val curveColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ARC_LANE_HEIGHT_DP)
            .background(containerColor, laneShape)
            .border(1.dp, borderColor, laneShape)
            .clip(laneShape),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            fun xOf(instant: Instant): Float =
                NightTimeline.fractionOf(
                    instant, timeline.axisStart, timeline.axisEnd,
                ).toFloat() * size.width
            val samples = arc.altitudeSamples
            val peak = arc.phases?.culmination?.altitudeDeg
                ?: samples.maxOfOrNull { it.altitudeDeg } ?: 0.0
            if (samples.size >= 2 && peak > 0.0) {
                val topPad = 2.dp.toPx()
                val bottom = size.height
                fun yOf(altitudeDeg: Double): Float =
                    bottom - (altitudeDeg / peak).coerceIn(0.0, 1.0).toFloat() *
                        (bottom - topPad)
                // The window curve: sample points inside [window],
                // extended to the exact 20° crossings on both sides,
                // so the curve starts and ends at the threshold height
                // instead of at the first / last sample above it.
                fun windowCurvePoints(
                    window: NightTimeline.Interval,
                ): List<Offset> {
                    val threshold = TimelineCursor.MIN_ALTITUDE_DEG
                    val inside = samples.filter { sample ->
                        !sample.instant.isBefore(window.start) &&
                            (sample.instant.isBefore(window.end) ||
                                (sample.instant == window.end &&
                                    sample.altitudeDeg >= threshold))
                    }
                    if (inside.isEmpty()) return emptyList()
                    val points = mutableListOf<Offset>()
                    val first = inside.first()
                    val firstIndex = samples.indexOfFirst {
                        it.instant == first.instant
                    }
                    val before = samples.getOrNull(firstIndex - 1)
                    if (before != null && before.altitudeDeg < threshold &&
                        first.altitudeDeg >= threshold
                    ) {
                        val span = (first.altitudeDeg - before.altitudeDeg)
                        val f = if (span > 0.0) {
                            (threshold - before.altitudeDeg) / span
                        } else {
                            0.0
                        }
                        val crossing = before.instant.plus(
                            Duration.ofMillis(
                                (Duration.between(before.instant, first.instant)
                                    .toMillis() * f).toLong(),
                            ),
                        )
                        points += Offset(xOf(crossing), yOf(threshold))
                    }
                    inside.forEach { sample ->
                        points += Offset(
                            xOf(sample.instant), yOf(sample.altitudeDeg),
                        )
                    }
                    val last = inside.last()
                    val lastIndex = samples.indexOfFirst {
                        it.instant == last.instant
                    }
                    val after = samples.getOrNull(lastIndex + 1)
                    if (after != null && last.altitudeDeg >= threshold &&
                        after.altitudeDeg < threshold
                    ) {
                        val span = (last.altitudeDeg - after.altitudeDeg)
                        val f = if (span > 0.0) {
                            (last.altitudeDeg - threshold) / span
                        } else {
                            0.0
                        }
                        val crossing = last.instant.plus(
                            Duration.ofMillis(
                                (Duration.between(last.instant, after.instant)
                                    .toMillis() * f).toLong(),
                            ),
                        )
                        points += Offset(xOf(crossing), yOf(threshold))
                    }
                    return points
                }
                // The sub-polyline of [points] between two x positions,
                // with the y at both cut positions interpolated from
                // the neighbouring vertices (the culmination fill
                // follows the curve, cut to its span).
                fun subCurvePoints(
                    points: List<Offset>,
                    x0: Float,
                    x1: Float,
                ): List<Offset> {
                    if (points.size < 2) return emptyList()
                    fun yAt(x: Float): Float {
                        if (x <= points.first().x) return points.first().y
                        if (x >= points.last().x) return points.last().y
                        val upper = points.indexOfFirst { it.x >= x }
                        val a = points[upper - 1]
                        val b = points[upper]
                        if (b.x <= a.x) return b.y
                        return a.y + (b.y - a.y) * (x - a.x) / (b.x - a.x)
                    }
                    val out = mutableListOf(Offset(x0, yAt(x0)))
                    points.filter { it.x > x0 && it.x < x1 }
                        .forEach { out += it }
                    out += Offset(x1, yAt(x1))
                    return out
                }
                fun fillUnder(points: List<Offset>, color: Color) {
                    if (points.size < 2) return
                    val path = Path().apply {
                        moveTo(points.first().x, bottom)
                        points.forEach { lineTo(it.x, it.y) }
                        lineTo(points.last().x, bottom)
                        close()
                    }
                    drawPath(path = path, color = color)
                }
                fun strokeOver(points: List<Offset>) {
                    if (points.size < 2) return
                    val path = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        points.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(
                        path = path,
                        color = curveColor,
                        style = Stroke(width = 1.2.dp.toPx()),
                    )
                }
                val windows = arc.phases?.window?.let { listOf(it) }
                    ?: arc.intervals
                for (window in windows) {
                    val points = windowCurvePoints(window)
                    fillUnder(points, windowFill)
                    val span = arc.phases?.culminationSpan
                    if (span != null && arc.phases?.window == window) {
                        fillUnder(
                            subCurvePoints(
                                points, xOf(span.start), xOf(span.end),
                            ),
                            culminationFill,
                        )
                    }
                    strokeOver(points)
                }
            }
            // Recommended-window frame (ui-design §7.5): over the
            // curve fills, in the comparison board's geometry — it
            // draws whether or not samples exist.
            arc.recommendedFrame?.let { frame ->
                val left = xOf(frame.start)
                val right = xOf(frame.end)
                if (right - left >= 2f) {
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(left, 1.5.dp.toPx()),
                        size = Size(right - left, 23.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry
                            .CornerRadius(5.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }
        }
        Text(
            text = arc.label,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            style = TextStyle(
                fontSize = 11.sp,
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.55f),
                    offset = Offset(0f, 1f),
                    blurRadius = 3f,
                ),
            ),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .padding(start = 8.dp, end = 4.dp),
        )
    }
}

/**
 * The whole-hour axis closing the layered panel (ui-design §7.4,
 * 0.8.9; sparse stepping since 0.8.10): whole hours at a step picked
 * by [NightTimeline.hourAxisStepHours] — the smallest of 1/2/3/6h
 * whose labels clear each other AND keep the board's sparse density
 * — labelled at their true positions on the panel's shared time
 * scale, deliberately NOT an equal division of the axis: a
 * 17:35–08:47 axis takes the 3h step and labels 18:00, 21:00, 00:00,
 * 03:00 and 06:00 where those instants actually fall. (The 0.8.9
 * version enumerated every hour and the labels ran into each other
 * on real phones.) All labels share one "HH:mm" shape, so a single
 * width estimate serves both the step choice and placement;
 * [placeLabels] remains as the overlap backstop.
 */
@Composable
private fun HourAxisRow(
    timeline: TonightOverviewUseCase.Timeline,
    zone: ZoneId,
    textColor: Color,
    plotWidthDp: Double,
) {
    val labelWidthDp = estimateTextWidthDp("00:00", 10.5f)
    val stepHours = NightTimeline.hourAxisStepHours(
        timeline.axisStart, timeline.axisEnd, plotWidthDp, labelWidthDp,
    )
    val hours = remember(timeline.axisStart, timeline.axisEnd, zone, stepHours) {
        NightTimeline.hourAxisLabels(
            timeline.axisStart, timeline.axisEnd, zone, stepHours,
        )
    }
    val candidates = hours.map { instant ->
        LabelCandidate(
            text = formatTime(instant, zone),
            centerDp = NightTimeline.fractionOf(
                instant, timeline.axisStart, timeline.axisEnd,
            ) * plotWidthDp,
            widthDp = labelWidthDp,
            rank = 0,
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HOUR_AXIS_ROW_HEIGHT_DP),
    ) {
        for ((label, leftDp) in placeLabels(candidates, plotWidthDp)) {
            Text(
                text = label.text,
                fontSize = 10.5.sp,
                maxLines = 1,
                color = textColor,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = leftDp.dp),
            )
        }
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
            NightTimeline.SunBand.DAY -> Color(0xFF7A261C)
            NightTimeline.SunBand.CIVIL_TWILIGHT -> Color(0xFF701C13)
            NightTimeline.SunBand.NAUTICAL_TWILIGHT -> Color(0xFF57150E)
            NightTimeline.SunBand.ASTRONOMICAL_TWILIGHT -> Color(0xFF3F0F0A)
            NightTimeline.SunBand.ASTRONOMICAL_NIGHT -> Color(0xFF2B0806)
        }
    } else {
        when (band) {
            // Desaturated deep amber since 0.8.9: the board's daytime
            // ends glow faintly instead of reading as solid orange
            // blocks at both ends of the strip.
            NightTimeline.SunBand.DAY -> Color(0xFF6E4B22)
            NightTimeline.SunBand.CIVIL_TWILIGHT -> Color(0xFF3D4E7D)
            NightTimeline.SunBand.NAUTICAL_TWILIGHT -> Color(0xFF2B3C68)
            NightTimeline.SunBand.ASTRONOMICAL_TWILIGHT -> Color(0xFF1C2A52)
            NightTimeline.SunBand.ASTRONOMICAL_NIGHT -> Color(0xFF101C42)
        }
    }

/**
 * The sun band row of the layered panel (ui-design §7.4, 0.8.8): one
 * strip filled with a multi-stop gradient driven by the band
 * segments — daytime amber, twilight blues, the night's deep blue,
 * and back out at dawn. Since 0.8.9 the stops are smoothed: each
 * segment boundary carries a transition zone of about ±2.5% of the
 * axis width (the previous band's colour holds until the zone, the
 * next band's colour takes over after it) instead of a stop pinned
 * at each segment's midpoint, and the daytime colour is a
 * desaturated deep amber so the strip's ends no longer read as solid
 * orange. Short labels ride in the band: sunset and sunrise at their
 * instants, dusk over the evening twilight span, dawn over the
 * morning one, and the night label centred on the astronomical
 * night. Placement follows the shared priority rule ([placeLabels]):
 * when two labels would overlap, the lower rank is omitted — the
 * night outranks dusk and dawn, which outrank sunset and sunrise.
 */
@Composable
private fun SunBandRow(
    timeline: TonightOverviewUseCase.Timeline,
    redLight: Boolean,
    plotWidthDp: Double,
) {
    // The board's pale blue-grey label colour (#C9D4EE); the red
    // theme takes its pale red equivalent.
    val labelColor = if (redLight) Color(0xFFFFB4A3) else Color(0xFFC9D4EE)
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
                // Smoothed stops: fractions are kept non-decreasing;
                // where two transition zones would overlap (a very
                // short segment), the clamp collapses the zone to a
                // hard stop at the shared fraction.
                val transitionHalf = 0.025f
                val stops = buildList {
                    var lastFraction = 0f
                    var lastColor: Color? = null
                    fun addStop(fraction: Float, color: Color) {
                        val f = max(fraction.coerceIn(0f, 1f), lastFraction)
                        if (f == lastFraction && color == lastColor) return
                        add(f to color)
                        lastFraction = f
                        lastColor = color
                    }
                    addStop(0f, sunBandColor(segments.first().band, redLight))
                    for (i in 0 until segments.size - 1) {
                        val boundary = fractionOf(segments[i + 1].start).toFloat()
                        addStop(
                            boundary - transitionHalf,
                            sunBandColor(segments[i].band, redLight),
                        )
                        addStop(
                            boundary + transitionHalf,
                            sunBandColor(segments[i + 1].band, redLight),
                        )
                    }
                    addStop(1f, sunBandColor(segments.last().band, redLight))
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
                        widthDp = estimateTextWidthDp(text, 11f),
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
                fontSize = 11.sp,
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
 * from the samples in the domain layer, on the card2 track (the
 * surfaceVariant token — the same track as the window bands).
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
            val textWidthDp = estimateTextWidthDp(text, 10.5f)
            val textX = NightTimeline.fractionOf(
                moonrise, timeline.axisStart, timeline.axisEnd,
            ) * plotWidthDp + 4.0
            if (textX + textWidthDp <= plotWidthDp - 2.0) {
                Text(
                    text = text,
                    fontSize = 10.5.sp,
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
 * The marker row (ui-design §7.4): the main marks' text labels on
 * their own row of the layered panel (short name plus the moment for
 * the Sun and night marks; the galactic-centre mark is name-only) —
 * the comparison board's C drawing. Since 0.8.9 the row draws NO
 * ticks: the labels alone carry the marks, in the dim tx3 colour.
 * Labels are centred on their moment and placed by the shared
 * priority rule ([placeLabels]): a label that would overlap a
 * higher-ranked neighbour is omitted, never shrunk or shifted into
 * it. The tap hot zones live with the panel (see NightTimelineCard),
 * spanning the band rows, and are unaffected by the labels.
 */
@Composable
private fun MarkerRow(
    timeline: TonightOverviewUseCase.Timeline,
    marks: List<TimelineMark>,
    textColor: Color,
    plotWidthDp: Double,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(MARKER_ROW_HEIGHT_DP),
    ) {
        val candidates = marks.mapNotNull { mark ->
            mark.shortLabel?.let { label ->
                LabelCandidate(
                    text = label,
                    centerDp = NightTimeline.fractionOf(
                        mark.instant, timeline.axisStart, timeline.axisEnd,
                    ) * plotWidthDp,
                    widthDp = estimateTextWidthDp(label, 10.5f),
                    rank = mark.labelRank,
                )
            }
        }
        for ((label, leftDp) in placeLabels(candidates, plotWidthDp)) {
            Text(
                text = label.text,
                fontSize = 10.5.sp,
                maxLines = 1,
                color = textColor,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = leftDp.dp),
            )
        }
    }
}

/**
 * One window band row of the layered panel (ui-design §7.4): the
 * low-moonlight windows and the galactic-centre window share this
 * drawing — a soft filled span on the card2 track, with no outline
 * frame since 0.8.9 (the pre-0.8.9 dashed / dot-dash frames are
 * retired; the two bands are told apart by their in-row names and
 * fills, in the red theme by name and brightness). The row names
 * itself: [label] starts at the first window span's start, in
 * [labelColor], so the row reads without a legend. Since 0.8.10 the
 * name may run past its (possibly narrow) window onto the row's own
 * track — a one-hour low-moonlight window still names its row; the
 * name is omitted only when it would leave the plot's right edge or
 * run into a second window span.
 */
@Composable
private fun WindowBandRow(
    intervals: List<NightTimeline.Interval>,
    timeline: TonightOverviewUseCase.Timeline,
    fillColor: Color,
    label: String,
    labelColor: Color,
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
                    color = fillColor,
                    topLeft = Offset(left, 0f),
                    size = Size(right - left, size.height),
                )
            }
        }
        val first = intervals.firstOrNull()
        if (first != null) {
            val startFraction = NightTimeline.fractionOf(
                first.start, timeline.axisStart, timeline.axisEnd,
            )
            val labelLeftDp = startFraction * plotWidthDp + 4.0
            val labelWidthDp = estimateTextWidthDp(label, 10f)
            val labelRightDp = labelLeftDp + labelWidthDp
            // The name may overflow its window onto the track
            // (0.8.10); it is omitted only when it would leave the
            // plot or run into the NEXT window span.
            val secondStartDp = intervals.getOrNull(1)?.let { second ->
                NightTimeline.fractionOf(
                    second.start, timeline.axisStart, timeline.axisEnd,
                ) * plotWidthDp
            }
            val fits = labelRightDp <= plotWidthDp &&
                (secondStartDp == null || labelRightDp <= secondStartDp)
            if (fits) {
                Text(
                    text = label,
                    fontSize = 10.sp,
                    maxLines = 1,
                    color = labelColor,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = labelLeftDp.dp),
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
