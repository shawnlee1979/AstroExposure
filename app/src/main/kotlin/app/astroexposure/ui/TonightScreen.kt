package app.astroexposure.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.astroexposure.R
import app.astroexposure.domain.catalog.DeepSkyObject
import app.astroexposure.domain.catalog.DeepSkyObjectType
import app.astroexposure.domain.geo.Coordinates
import app.astroexposure.domain.sky.BaselineSource
import app.astroexposure.domain.sky.MoonPhaseName
import app.astroexposure.domain.timeline.NightTimeline
import app.astroexposure.domain.timeline.TimelineCursor
import app.astroexposure.domain.timeline.TonightDateWindow
import app.astroexposure.support.CrashTrap
import app.astroexposure.support.SkyContext
import app.astroexposure.support.TimeHealthCheck
import app.astroexposure.usecase.TonightOverviewUseCase
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Cadence of the cursor's clock-follow tick (ui-design §7.5, 0.8.12): at most 30 seconds. */
private const val FOLLOW_TICK_MS = 20_000L

/**
 * Tonight overview (ui-design §4.1 and §7): location card (positioning,
 * light pollution and the dusk environment in one card) → date switch →
 * tonight-at-a-glance block → the static four-layer timeline as the
 * page backbone → static bright-object shortlist → ±3-day events; the
 * page ends with the shortlist and events — calculator categories are
 * chosen on the Calculate tab. Conclusion first; degradations stated
 * plainly in their §7.4 positions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TonightScreen(
    overview: TonightOverviewUseCase.Result,
    date: LocalDate,
    anchorDate: LocalDate,
    zone: ZoneId,
    deviceZone: ZoneId,
    latitudeDeg: Double,
    longitudeDeg: Double,
    locationLabel: String?,
    locationSourceText: String,
    timeHealth: TimeHealthCheck.Result,
    redLight: Boolean,
    onUseGps: () -> Unit,
    /** True while the current site's coordinates came from a GPS fix: the card marks them with a sync dot (ui-design §7.2). */
    showGpsSyncDot: Boolean,
    manualBortle: Int?,
    onManualBortleChange: (Int?) -> Unit,
    onManualLocation: (Double, Double) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    /** Whether the altitude line shows in the location card (GPS and manual sites, ui-design §7.2). */
    showAltitudeLine: Boolean,
    /** The GPS fix's altitude in metres; null when the fix carried none. */
    gpsAltitudeMeters: Double?,
    /**
     * True for a site that is not yet a favourite — an unsaved GPS
     * fix or a manual-coordinate entry: the card offers "save as a
     * favourite place".
     */
    canSaveFavourite: Boolean,
    onSaveFavourite: (String) -> Unit,
    onEvaluateCursor: (Instant) -> TonightOverviewUseCase.CursorEvaluation,
    onEvaluateTarget: (Instant, String) -> TonightOverviewUseCase.TargetDetail?,
    /** The lens library (built-in + custom), for the detail dialog's framing chart (§7.10). */
    lenses: List<app.astroexposure.domain.lens.Lens>,
    onEvaluateComposition: (String, Double, Double) ->
        TonightOverviewUseCase.CompositionEvaluation?,
    onOpenTargetCalculator: (CalcCategory, CalcPrefill) -> Unit,
    orientationProvider: app.astroexposure.domain.orientation.DeviceOrientationProvider,
    siteLatitudeDeg: Double,
    siteLongitudeDeg: Double,
    siteAltitudeMeters: Double,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val haptic = LocalHapticFeedback.current

    // Cursor state (ui-design §7.5). Everything resets when the selected
    // date changes (the cursor belongs to one night); the position is
    // retained while the cursor state is exited via "whole night", and
    // the next plain tap on the axis restores it (see onCursorTap).
    var cursorActive by remember(date) { mutableStateOf(false) }
    var retainedCursor by remember(date) { mutableStateOf<Instant?>(null) }
    var cursorEval by remember(date) { mutableStateOf<TonightOverviewUseCase.CursorEvaluation?>(null) }
    // The detail dialog's target (ui-design §7.5): set by tapping an
    // arc lane; its detail is evaluated on demand at the cursor
    // instant. There is no activation — entering the cursor state
    // selects nothing, and the cursor evaluates no single target.
    var detailObjectId by remember(date) { mutableStateOf<String?>(null) }
    var targetDetail by remember(date) {
        mutableStateOf<TonightOverviewUseCase.TargetDetail?>(null)
    }
    // True once the on-demand evaluation for [detailObjectId] has
    // finished and produced nothing: the use case reports a missing or
    // failed catalogue and an unknown id alike as null, and the dialog
    // must show that as a failure, not as an endless "loading" state.
    var detailLoadFailed by remember(date) { mutableStateOf(false) }
    var lastSnappedMoment by remember(date) { mutableStateOf<Int?>(null) }
    // Cursor lock (ui-design §7.5, 0.5.2 anti-mistouch): bound to the
    // selected night like every other cursor state — switching the
    // date resets it together with the cursor itself.
    var cursorLocked by remember(date) { mutableStateOf(false) }
    val cursorPlacement = when {
        retainedCursor == null -> TimelineCursor.CursorPlacement.NO_CURSOR
        cursorLocked -> TimelineCursor.CursorPlacement.PLACED_LOCKED
        else -> TimelineCursor.CursorPlacement.PLACED_UNLOCKED
    }
    // Clock-follow state (ui-design §7.5, 0.8.12): ONE instance per
    // Tonight-page visit — deliberately not keyed by date, so leaving
    // the tab (which disposes this screen) re-initialises follow to
    // on, while a "whole night" exit disables it for the rest of the
    // visit, across date switches too. Every transition goes through
    // the domain state machine (TimelineCursor.CursorFollowState).
    var followState by remember { mutableStateOf(TimelineCursor.CursorFollowState()) }
    // The wall-clock minute the follow UI reasons about: refreshed by
    // the tick and on resume, so the time bar's "Now" offer tracks
    // the clock even while follow is off.
    var nowMinute by remember {
        mutableStateOf(TimelineCursor.truncatedToMinute(Instant.now()))
    }

    // Placement shared by tap and drag: minute precision, axis clamp,
    // snapping to a key moment within the §7.5 window with one haptic
    // tick when a new moment is snapped to. Key moments carry
    // sub-minute precision from the ephemeris port while the cursor's
    // unit is one minute (§7.5), so snapping lands on the moment
    // TRUNCATED to the minute — the placed cursor then compares equal
    // to the evaluation instant, which is truncated the same way.
    fun placeCursor(raw: Instant) {
        val clamped = TimelineCursor.clampToAxis(
            TimelineCursor.truncatedToMinute(raw),
            overview.timeline.axisStart, overview.timeline.axisEnd,
        )
        val momentMinutes = overview.moments.map {
            TimelineCursor.truncatedToMinute(it.instant)
        }
        val snapped = TimelineCursor.snappedMomentIndex(clamped, momentMinutes)
        if (snapped != null && snapped != lastSnappedMoment) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        lastSnappedMoment = snapped
        retainedCursor = snapped?.let { momentMinutes[it] } ?: clamped
        cursorActive = true
    }

    // A manual placement (ui-design §7.5, 0.8.12): the user takes the
    // cursor over and follow stops — every gesture callback routes
    // through here instead of calling placeCursor directly.
    fun placeCursorManually(raw: Instant) {
        followState = followState.afterManualMove()
        placeCursor(raw)
    }

    // Latest-value handles for the follow machinery: the tick loop
    // and the resume observer outlive any single composition, so they
    // must read the CURRENT state, never a captured first value.
    val followStateNow by rememberUpdatedState(followState)
    val cursorLockedNow by rememberUpdatedState(cursorLocked)
    val retainedCursorNow by rememberUpdatedState(retainedCursor)
    val overviewNow by rememberUpdatedState(overview)

    // One clock-follow alignment (ui-design §7.5, 0.8.12): the pure
    // decision lives in the domain (TimelineCursor.followTickAction);
    // this only applies it. A follow placement is exact — no snapping
    // to key moments, no haptic — so the cursor's minute always
    // equals the wall-clock minute.
    fun alignCursorToNow() {
        val now = TimelineCursor.truncatedToMinute(Instant.now())
        nowMinute = now
        val timeline = overviewNow.timeline
        if (
            TimelineCursor.followTickAction(
                followStateNow, cursorLockedNow, retainedCursorNow, now,
                timeline.axisStart, timeline.axisEnd,
            ) == TimelineCursor.FollowTickAction.PLACE_AT_NOW
        ) {
            retainedCursor = now
            cursorActive = true
            lastSnappedMoment = null
        }
    }

    // The follow tick (ui-design §7.5, 0.8.12): aligns on entry,
    // then at the FOLLOW_TICK_MS cadence while this page is composed
    // (leaving the tab disposes the screen and stops the loop).
    LaunchedEffect(Unit) {
        while (true) {
            alignCursorToNow()
            delay(FOLLOW_TICK_MS)
        }
    }
    // Returning to the foreground aligns at once instead of waiting
    // out the tick.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) alignCursorToNow()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // A data recompute (GPS refresh, site or date change) can move
    // the axis: re-align at once so a following cursor lands on the
    // current minute of the NEW axis. A manually held cursor is never
    // touched by this — the decision is the domain's.
    LaunchedEffect(overview.timeline.axisStart, overview.timeline.axisEnd) {
        alignCursorToNow()
    }

    // The evaluation follows the settled cursor position (short
    // debounce so a drag does not evaluate every intermediate minute).
    LaunchedEffect(cursorActive, retainedCursor, overview) {
        cursorEval = if (cursorActive && retainedCursor != null) {
            delay(120)
            withContext(Dispatchers.Default) {
                onEvaluateCursor(retainedCursor!!)
            }
        } else {
            null
        }
    }

    val evalMatchesCursor = cursorEval != null && cursorEval!!.instant == retainedCursor

    // Locate highlight (ui-design §7.8): the lane a locate landed on
    // carries a gold border for ~1.2 s, then the state clears itself.
    var highlightedSubjectId by remember(date) { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightedSubjectId) {
        if (highlightedSubjectId != null) {
            delay(1200)
            highlightedSubjectId = null
        }
    }

    // The lanes the timeline draws (ui-design §7.8): in the cursor
    // state the ranked shootable set, in the whole-night state the
    // board lanes — with tonight's radiant lanes appended in both.
    // In the cursor state the merge is by subject id: the cursor
    // evaluation's universe already contains tonight's qualifying
    // radiants, so appending the whole-night radiant lanes
    // unconditionally drew every radiant twice.
    val displayArcs = mutableListOf<TimelineArc>()
    if (cursorActive) {
        if (evalMatchesCursor) {
            displayArcs += mergeCursorStateArcs(
                cursorEval!!.targets.map { it.toTimelineArc(highlightedSubjectId) },
                overview.radiantLanes.map { it.toTimelineArc(highlightedSubjectId) },
            )
        }
    } else {
        for (lane in overview.lanes) {
            displayArcs += lane.toTimelineArc(highlightedSubjectId)
        }
        for (lane in overview.radiantLanes) {
            displayArcs += lane.toTimelineArc(highlightedSubjectId)
        }
    }

    // Tonight's own lanes (board + radiants), independent of the
    // display state: the category filter chips count these and the
    // hidden-category fact lines name these (ui-design §7.8) — the
    // counts are a property of the night, identical in both states,
    // never of the cursor state's ranked display set above.
    val tonightArcs = (overview.lanes + overview.radiantLanes)
        .map { it.toTimelineArc(null) }

    // The targets sheet's rows (ui-design §7.8, 0.10.0): the use
    // case's qualified set rendered — the presentation layer counts
    // and groups these, it never re-derives membership.
    val targets = overview.qualified.map { entry -> entry.toTonightTarget() }

    // The detail dialog's evaluation: runs when a lane tap names a
    // target, against the cursor instant at that moment (the dialog
    // is modal, so the cursor cannot move while it is open).
    LaunchedEffect(detailObjectId, overview) {
        val id = detailObjectId
        detailLoadFailed = false
        targetDetail = if (id != null && retainedCursor != null) {
            val evaluated = withContext(Dispatchers.Default) {
                onEvaluateTarget(retainedCursor!!, id)
            }
            detailLoadFailed = evaluated == null
            evaluated
        } else {
            null
        }
    }

    // While the detail dialog is open, its target values are
    // re-evaluated every TARGET_REFRESH_SECONDS through the same
    // on-demand path at the same cursor instant (ui-design §7.5,
    // 0.8.0): the finder aims at a fixed cursor instant, so the
    // refresh only ever corrects the values toward that instant's
    // evaluation, and a transient failure keeps the values already
    // on screen rather than blanking the dialog.
    LaunchedEffect(detailObjectId) {
        val id = detailObjectId ?: return@LaunchedEffect
        val cursor = retainedCursor ?: return@LaunchedEffect
        while (true) {
            delay(
                app.astroexposure.domain.orientation.FinderThresholds
                    .TARGET_REFRESH_SECONDS * 1_000,
            )
            if (detailObjectId != id) break
            val fresh = withContext(Dispatchers.Default) {
                onEvaluateTarget(cursor, id)
            }
            if (fresh != null && detailObjectId == id) {
                targetDetail = fresh
            }
        }
    }

    // The snap name is shown only when the cursor truly sits ON a key
    // moment (exact minute equality after truncation) — proximity
    // alone does not earn the name, so stepping near a moment without
    // snapping never labels the bubble with a different time.
    val snappedLabel = if (cursorActive && retainedCursor != null) {
        overview.moments.firstOrNull {
            TimelineCursor.truncatedToMinute(it.instant) == retainedCursor
        }?.let { moment ->
            "${momentKindName(moment.kind)} " +
                formatTime(TimelineCursor.truncatedToMinute(moment.instant), zone)
        }
    } else {
        null
    }
    // The bubble's three lines (ui-design §7.5, 0.8.9 composition):
    // time · solar segment / Moon altitude · phase illumination /
    // moonlight-interference tier · Bortle grade. All figures describe
    // the cursor instant: while the evaluation is in flight the Moon
    // figures and the segment are interpolated / classified from the
    // timeline's own samples at the cursor, not taken from dusk; the
    // interference tier exists only in the evaluation, so the third
    // line carries the Bortle grade alone until the evaluation lands.
    val bubbleSegmentText = if (cursorActive && retainedCursor != null) {
        val band = if (evalMatchesCursor) {
            cursorEval!!.segment
        } else {
            TimelineCursor.sunAltitudeAt(overview.timeline.samples, retainedCursor!!)
                ?.let { NightTimeline.bandFor(it) }
        }
        band?.let {
            stringResource(
                when (it) {
                    NightTimeline.SunBand.DAY -> R.string.cursor_segment_day
                    NightTimeline.SunBand.CIVIL_TWILIGHT -> R.string.cursor_segment_civil
                    NightTimeline.SunBand.NAUTICAL_TWILIGHT -> R.string.cursor_segment_nautical
                    NightTimeline.SunBand.ASTRONOMICAL_TWILIGHT ->
                        R.string.cursor_segment_astronomical
                    NightTimeline.SunBand.ASTRONOMICAL_NIGHT -> R.string.cursor_segment_night
                },
            )
        }
    } else {
        null
    }
    val bubbleMoonText = if (cursorActive && retainedCursor != null) {
        val moonAlt = if (evalMatchesCursor) {
            cursorEval!!.moonAltitudeDeg
        } else {
            TimelineCursor.moonAltitudeAt(overview.timeline.samples, retainedCursor!!)
        }
        val illumination = if (evalMatchesCursor) {
            cursorEval!!.moonIllumination
        } else {
            TimelineCursor.moonIlluminationAt(overview.timeline.samples, retainedCursor!!)
                ?: overview.moonIllumination
        }
        moonAlt?.let {
            stringResource(
                R.string.cursor_bubble_moon, it, percentRounded(illumination),
            )
        }
    } else {
        null
    }
    val bubbleTierText = if (cursorActive && retainedCursor != null) {
        val tierPart = if (evalMatchesCursor) {
            stringResource(R.string.detail_grid_moon_interference) + " " +
                stringResource(
                    when (cursorEval!!.moonInterference) {
                        TimelineCursor.MoonInterference.NONE ->
                            R.string.cursor_interference_none
                        TimelineCursor.MoonInterference.WEAK ->
                            R.string.cursor_interference_weak
                        TimelineCursor.MoonInterference.STRONG ->
                            R.string.cursor_interference_strong
                    },
                )
        } else {
            null
        }
        // The site's Bortle grade with the environment section's
        // derivation (§7.2): the grid estimate where the grid has
        // data, else the manual grade, else no grade at all.
        val bortle = if (overview.lightPollutionInfo?.artificialMcdPerM2 != null) {
            overview.lightPollutionInfo?.bortleEstimate
        } else {
            manualBortle
        }
        val bortlePart = bortle?.let {
            stringResource(R.string.cursor_bubble_bortle, it)
        }
        listOfNotNull(tierPart, bortlePart).joinToString(" · ").ifEmpty { null }
    } else {
        null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // A crash record left by the previous run (0.11.1): pinned
        // to the very top until the user copies or dismisses it;
        // renders nothing when no record exists.
        CrashRecordCard()

        // Time-health warnings, stated plainly (design §2.2). There
        // is no standing health marker on the page (ui-design §7.2,
        // 0.8.7): healthy shows nothing at all, and an unhealthy state
        // is carried by the warning lines below — the first of them
        // bears the ▲ glyph in the bad state colour, so the state
        // still reads by colour AND glyph. (The page-top status row
        // that used to hold the marker was deleted: its lone ● was
        // indistinguishable from the GPS sync dot on the location
        // card.)
        TimeHealthWarningText(timeHealth)

        LocationCard(
            overview = overview,
            zone = zone,
            latitudeDeg = latitudeDeg,
            longitudeDeg = longitudeDeg,
            locationLabel = locationLabel,
            locationSourceText = locationSourceText,
            manualBortle = manualBortle,
            onManualBortleChange = onManualBortleChange,
            onManualLocation = onManualLocation,
            onUseGps = onUseGps,
            showAltitudeLine = showAltitudeLine,
            gpsAltitudeMeters = gpsAltitudeMeters,
            canSaveFavourite = canSaveFavourite,
            onSaveFavourite = onSaveFavourite,
            showGpsSyncDot = showGpsSyncDot,
            // The environment section follows a settled cursor
            // (ui-design §7.2): while the cursor evaluation matches
            // the placed cursor, the card reads the cursor's own
            // galactic-centre sky sample; anything else (no cursor,
            // evaluation in flight) keeps the dusk snapshot.
            cursorSky = if (evalMatchesCursor) cursorEval?.skyTowardGalacticCenter else null,
            cursorSkyInstant = if (evalMatchesCursor) retainedCursor else null,
        )

        // §7.6: when the displayed zone is not the device zone, name it.
        if (zone.id != deviceZone.id) {
            Text(
                text = stringResource(R.string.tonight_zone_note, zone.id),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DateSwitchRow(date = date, anchorDate = anchorDate, onDateChange = onDateChange)

        // Tonight at a glance (ui-design §7.2): the night's four
        // headline facts above the timeline — astronomical night span,
        // the Moon altitude at the cursor, the whole-night moonlight
        // tier and the low-moonlight windows, all from data the
        // overview already computed.
        TonightOverviewBlock(
            overview = overview,
            cursorEvaluation = if (evalMatchesCursor) cursorEval else null,
            cursorInstant = if (cursorActive) retainedCursor else null,
            zone = zone,
        )

        NightTimelineCard(
            timeline = overview.timeline,
            marks = overview.moments.map { moment ->
                timelineMarkFor(moment, overview, zone)
            },
            moonrise = overview.moments.firstOrNull { moment ->
                moment.kind == TonightOverviewUseCase.MomentKind.MOONRISE
            }?.instant,
            moonIllumination = overview.moonIllumination,
            lowMoonlightWindows = overview.lowMoonlightWindows,
            zone = zone,
            redLight = redLight,
            cursorInstant = retainedCursor,
            cursorActive = cursorActive,
            bubbleTimeText = retainedCursor?.let { formatTime(it, zone) },
            bubbleMoonText = bubbleMoonText,
            bubbleSnapLabel = snappedLabel,
            bubbleSegmentText = bubbleSegmentText,
            bubbleTierText = bubbleTierText,
            arcs = displayArcs,
            tonightArcs = tonightArcs,
            followLive = cursorActive && followState.driving,
            jumpToNowVisible = cursorActive && TimelineCursor.jumpToNowOffered(
                followState, retainedCursor, nowMinute,
                overview.timeline.axisStart, overview.timeline.axisEnd,
            ),
            onJumpToNow = {
                // The time bar's "Now" button (ui-design §7.5,
                // 0.8.12): a deliberate jump to the current minute
                // that re-opens follow — one of only two ways back
                // (the other is re-entering the page). Exact minute,
                // no snapping, like every follow placement.
                followState = followState.afterJumpToNow()
                val now = TimelineCursor.truncatedToMinute(Instant.now())
                nowMinute = now
                retainedCursor = TimelineCursor.clampToAxis(
                    now, overview.timeline.axisStart, overview.timeline.axisEnd,
                )
                lastSnappedMoment = null
                cursorActive = true
            },
            onCursorTap = { tapped ->
                if (!cursorActive && retainedCursor != null) {
                    // After "whole night", the next tap restores the
                    // retained cursor position (ui-design §7.5) — a
                    // restore, not a new placement, so the lock does
                    // not block it. The restore is still the user's
                    // own placement: follow stays off (0.8.12).
                    followState = followState.afterManualMove()
                    cursorActive = true
                } else if (
                    TimelineCursor.mayMoveCursor(
                        cursorPlacement, TimelineCursor.CursorGesture.TAP,
                    )
                ) {
                    placeCursorManually(tapped)
                }
                // A placed cursor is never moved by a plain tap
                // (anti-mistouch, ui-design §7.5): repositioning takes
                // the long-press drag below.
            },
            onCursorDrag = { dragged -> placeCursorManually(dragged) },
            onTrackDrag = { dragged ->
                // The track band's direct drag (ui-design §7.5,
                // 0.8.11): the one surface where a placed, unlocked
                // cursor follows the finger without a hold; the lock
                // still refuses it (see the gesture state machine).
                if (
                    TimelineCursor.mayMoveCursor(
                        cursorPlacement, TimelineCursor.CursorGesture.TRACK_DRAG,
                    )
                ) {
                    placeCursorManually(dragged)
                }
            },
            cursorLocked = cursorLocked,
            onCursorLongPressDrag = { dragged ->
                if (
                    TimelineCursor.mayMoveCursor(
                        cursorPlacement, TimelineCursor.CursorGesture.LONG_PRESS_DRAG,
                    )
                ) {
                    placeCursorManually(dragged)
                }
            },
            onCursorGrab = {
                // One haptic tick when the hold grabs the cursor
                // (ui-design §7.5, 0.5.2).
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onToggleCursorLock = { cursorLocked = !cursorLocked },
            onCursorStep = { deltaMinutes ->
                retainedCursor?.let { current ->
                    // A step is a manual move like any other
                    // (ui-design §7.5, 0.8.12): follow stops.
                    followState = followState.afterManualMove()
                    retainedCursor = TimelineCursor.clampToAxis(
                        TimelineCursor.truncatedToMinute(
                            current.plus(Duration.ofMinutes(deltaMinutes)),
                        ),
                        overview.timeline.axisStart, overview.timeline.axisEnd,
                    )
                    lastSnappedMoment = null
                    cursorActive = true
                }
            },
            onExitCursor = {
                cursorActive = false
                // "Whole night" also disables follow for the rest of
                // this visit (ui-design §7.5, 0.8.12): the tick must
                // not resurrect the cursor the user just dismissed.
                followState = followState.afterWholeNightExit()
            },
            onArcTap = { subjectId ->
                // Tapping a lane opens its entry's detail dialog
                // (ui-design §7.5): the detail is evaluated on demand
                // for the tapped subject at the cursor instant. In
                // the whole-night state there is no cursor yet, so
                // the tap first locates — the cursor lands on the
                // entry's recommended start through the manual
                // takeover path — and the dialog evaluates there.
                if (cursorActive) {
                    if (evalMatchesCursor) {
                        detailObjectId = subjectId
                    }
                } else {
                    val lane = (overview.lanes + overview.radiantLanes)
                        .firstOrNull { it.subject.id == subjectId }
                    if (lane != null) {
                        placeCursorManually(
                            lane.recommendedFrame?.start ?: lane.culmination,
                        )
                        highlightedSubjectId = subjectId
                        detailObjectId = subjectId
                    }
                }
            },
            targets = targets,
            targetsAvailable = overview.shortlistAvailable,
            onTargetOpen = { target ->
                // A targets row opens the subject's detail
                // (ui-design §7.8) — at the placed cursor in the
                // cursor state; in the whole-night state the tap
                // locates first, like a lane tap, so the dialog has
                // a meaningful instant to evaluate at. Only a row
                // that owns a lane lights one up (0.10.0): board
                // rows and radiants highlight; a non-board row just
                // places the cursor — no lane is drawn for it.
                if (!cursorActive) {
                    placeCursorManually(target.recommendedStart ?: target.culmination)
                    if (target.hasLane) highlightedSubjectId = target.subjectId
                }
                detailObjectId = target.subjectId
            },
            onTargetLocate = { target ->
                // The locate chip (ui-design §7.8): the cursor goes
                // to the row's recommended start (its culmination
                // when it has no recommended window) through the
                // existing manual-takeover semantics (follow off,
                // cursor pinned); the lane highlight follows the
                // same lane-ownership rule as the row tap.
                placeCursorManually(target.recommendedStart ?: target.culmination)
                if (target.hasLane) highlightedSubjectId = target.subjectId
            },
        )

        // Target detail dialog (ui-design §7.5, 0.8.0 layout): the
        // layered dialog — fixed conclusion head and warning bar,
        // the finder, the key-data grid and the folded whole-night
        // section — in the shared DetailDialog shell. Loading and
        // failure keep the plain shell with their distinct wordings
        // (0.7.1): a failure is never dressed as loading. The
        // calculator action appears only when the target's category
        // maps to a calculator (targetCalcCategory).
        detailObjectId?.let { id ->
            val detail = targetDetail?.takeIf { it.subject.id == id }
            val tappedName = displayArcs.firstOrNull { it.subjectId == id }?.subjectName
                ?: targets.firstOrNull { it.subjectId == id }?.name
            val category = detail?.let { subjectCalcCategory(it.subject) }
            // The handover prefill is composed here, in composition
            // (its name is UI-language work), and merely captured by
            // the action lambda below.
            val prefill = detail?.let { CalcPrefill.fromSubject(it.subject) }
            if (detail != null && retainedCursor != null) {
                TargetDetailDialog(
                    detail = detail,
                    cursor = retainedCursor!!,
                    zone = zone,
                    moonDegraded = overview.moonDegraded,
                    moonIllumination = overview.moonIllumination,
                    orientationProvider = orientationProvider,
                    siteLatitudeDeg = siteLatitudeDeg,
                    siteLongitudeDeg = siteLongitudeDeg,
                    siteAltitudeMeters = siteAltitudeMeters,
                    onDismiss = { detailObjectId = null },
                    actionLabel = if (category != null) {
                        stringResource(R.string.cursor_detail_goto_calculator)
                    } else {
                        null
                    },
                    onAction = if (category != null && prefill != null) {
                        {
                            onOpenTargetCalculator(category, prefill)
                            detailObjectId = null
                        }
                    } else {
                        null
                    },
                    onEvaluateComposition = { bearingDeg, toleranceDeg ->
                        onEvaluateComposition(id, bearingDeg, toleranceDeg)
                    },
                    lenses = lenses,
                    redLight = redLight,
                )
            } else {
                DetailDialog(
                    title = tappedName ?: "…",
                    lines = if (detailLoadFailed) {
                        listOf(stringResource(R.string.cursor_detail_unavailable))
                    } else {
                        listOf("…")
                    },
                    onDismiss = { detailObjectId = null },
                )
            }
        }

        if (cursorActive && retainedCursor != null) {
            // Cursor state (ui-design §7.5): below the axis only the
            // loading / unavailable strip remains (the status row was
            // deleted in 0.8.4) — target details live in the dialog.
            // The whole-night state shows nothing here since 0.9.0:
            // the board moved into the timeline card's targets sheet
            // (ui-design §7.8), so the page order is location card,
            // overview block, timeline card, events card.
            CursorStatusCard(
                evaluation = if (evalMatchesCursor) cursorEval else null,
            )
        }

        // Events within ±3 days of the selected date.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SectionTitle(stringResource(R.string.tonight_events_title))
                when (overview.eventsDisplay) {
                    TonightOverviewUseCase.EventsDisplay.UNAVAILABLE ->
                        Text(text = stringResource(R.string.tonight_events_unavailable))
                    TonightOverviewUseCase.EventsDisplay.NONE ->
                        Text(text = stringResource(R.string.tonight_events_none))
                    TonightOverviewUseCase.EventsDisplay.LIST -> overview.events.forEach { event ->
                        when (event) {
                            is TonightOverviewUseCase.Event.Eclipse -> Text(
                                text = stringResource(
                                    R.string.tonight_event_eclipse,
                                    event.record.date.toString(),
                                    event.record.kind.name,
                                    event.record.type.name,
                                ),
                            )
                            is TonightOverviewUseCase.Event.Shower -> Text(
                                text = stringResource(
                                    R.string.tonight_event_shower,
                                    event.approxPeakDate.toString(),
                                    event.shower.nameZh,
                                    event.approxPeakDate.toString(),
                                    event.shower.peakZhr,
                                ),
                            )
                        }
                    }
                }
            }
        }

    }
}

/**
 * Build the timeline mark for one key moment (ui-design §7.4): Sun
 * and Moon moments open the shared Sun / Moon detail (rise
 * and set times with azimuths sampled at those instants; the Moon
 * detail adds the phase name and illumination), twilight moments show
 * the moment with the solar altitude that defines it, and the
 * galactic-centre mark shows culmination time and altitude.
 */
@Composable
private fun twilightTimelineMark(
    moment: TonightOverviewUseCase.Moment,
    titleRes: Int,
    solarAltitudeDeg: Double,
    zone: ZoneId,
): TimelineMark = TimelineMark(
    instant = moment.instant,
    title = stringResource(titleRes),
    // The two astronomical-night boundary marks carry the marker
    // row's top-ranked short labels (ui-design §7.4, 0.8.8); the civil
    // and nautical marks stay tick-only.
    shortLabel = when (moment.kind) {
        TonightOverviewUseCase.MomentKind.ASTRO_DUSK ->
            stringResource(R.string.timeline_mark_night_start, formatTime(moment.instant, zone))
        TonightOverviewUseCase.MomentKind.ASTRO_DAWN ->
            stringResource(R.string.timeline_mark_night_end, formatTime(moment.instant, zone))
        else -> null
    },
    labelRank = if (
        moment.kind == TonightOverviewUseCase.MomentKind.ASTRO_DUSK ||
        moment.kind == TonightOverviewUseCase.MomentKind.ASTRO_DAWN
    ) {
        3
    } else {
        0
    },
    lines = listOf(
        stringResource(
            R.string.detail_moment_altitude_line,
            formatTime(moment.instant, zone),
            solarAltitudeDeg,
        ),
    ),
)

@Composable
private fun timelineMarkFor(
    moment: TonightOverviewUseCase.Moment,
    overview: TonightOverviewUseCase.Result,
    zone: ZoneId,
): TimelineMark {
    return when (moment.kind) {
        TonightOverviewUseCase.MomentKind.SUNSET,
        TonightOverviewUseCase.MomentKind.SUNRISE -> {
            val detail = overview.sunDetail
            TimelineMark(
                instant = moment.instant,
                title = stringResource(R.string.detail_sun_title),
                // Marker-row short label (ui-design §7.4, 0.8.8): the
                // Sun marks name themselves with their moment.
                shortLabel = stringResource(
                    if (moment.kind == TonightOverviewUseCase.MomentKind.SUNSET) {
                        R.string.timeline_mark_sunset
                    } else {
                        R.string.timeline_mark_sunrise
                    },
                    formatTime(moment.instant, zone),
                ),
                labelRank = 1,
                lines = buildList {
                    if (detail.sunset != null) {
                        add(
                            stringResource(
                                R.string.detail_sunset_line,
                                formatTime(detail.sunset, zone),
                                detail.sunsetAzimuthDeg ?: 0.0,
                            ),
                        )
                    }
                    if (detail.sunrise != null) {
                        add(
                            stringResource(
                                R.string.detail_sunrise_line,
                                formatTime(detail.sunrise, zone),
                                detail.sunriseAzimuthDeg ?: 0.0,
                            ),
                        )
                    }
                },
            )
        }
        TonightOverviewUseCase.MomentKind.MOONRISE,
        TonightOverviewUseCase.MomentKind.MOONSET -> {
            val detail = overview.moonDetail
            TimelineMark(
                instant = moment.instant,
                title = stringResource(R.string.detail_moon_title),
                lines = buildList {
                    if (detail.moonrise != null) {
                        add(
                            stringResource(
                                R.string.detail_moonrise_line,
                                formatTime(detail.moonrise, zone),
                                detail.moonriseAzimuthDeg ?: 0.0,
                            ),
                        )
                    }
                    if (detail.moonset != null) {
                        add(
                            stringResource(
                                R.string.detail_moonset_line,
                                formatTime(detail.moonset, zone),
                                detail.moonsetAzimuthDeg ?: 0.0,
                            ),
                        )
                    }
                    add(
                        stringResource(
                            R.string.detail_moon_phase_line,
                            stringResource(
                                when (detail.phaseName) {
                                    MoonPhaseName.NEW_MOON -> R.string.phase_new_moon
                                    MoonPhaseName.WAXING_CRESCENT -> R.string.phase_waxing_crescent
                                    MoonPhaseName.FIRST_QUARTER -> R.string.phase_first_quarter
                                    MoonPhaseName.WAXING_GIBBOUS -> R.string.phase_waxing_gibbous
                                    MoonPhaseName.FULL_MOON -> R.string.phase_full_moon
                                    MoonPhaseName.WANING_GIBBOUS -> R.string.phase_waning_gibbous
                                    MoonPhaseName.LAST_QUARTER -> R.string.phase_last_quarter
                                    MoonPhaseName.WANING_CRESCENT -> R.string.phase_waning_crescent
                                },
                            ),
                            percentRounded(detail.illumination),
                        ),
                    )
                },
            )
        }
        TonightOverviewUseCase.MomentKind.CIVIL_DUSK_END ->
            twilightTimelineMark(moment, R.string.moment_civil_dusk, NightTimeline.CIVIL_LIMIT_DEG, zone)
        TonightOverviewUseCase.MomentKind.NAUTICAL_DUSK_END ->
            twilightTimelineMark(moment, R.string.moment_nautical_dusk, NightTimeline.NAUTICAL_LIMIT_DEG, zone)
        TonightOverviewUseCase.MomentKind.ASTRO_DUSK ->
            twilightTimelineMark(moment, R.string.moment_astro_dusk, NightTimeline.ASTRONOMICAL_LIMIT_DEG, zone)
        TonightOverviewUseCase.MomentKind.ASTRO_DAWN ->
            twilightTimelineMark(moment, R.string.moment_astro_dawn, NightTimeline.ASTRONOMICAL_LIMIT_DEG, zone)
        TonightOverviewUseCase.MomentKind.GC_CULMINATION -> TimelineMark(
            instant = moment.instant,
            title = stringResource(R.string.moment_gc_culmination),
            // Name-only short label (ui-design §7.4, 0.8.8): the
            // culmination moment itself lives in the detail dialog.
            shortLabel = stringResource(R.string.timeline_mark_gc),
            labelRank = 2,
            lines = listOf(
                overview.galacticCenter?.let { gc ->
                    stringResource(
                        R.string.detail_gc_line,
                        formatTime(gc.culmination, zone),
                        gc.culminationAltitude.value,
                    )
                } ?: stringResource(R.string.detail_time_line, formatTime(moment.instant, zone)),
            ),
        )
        TonightOverviewUseCase.MomentKind.RADIANT_CULMINATION -> {
            // A qualifying radiant's culmination (ui-design §7.8):
            // the mark names the shower whose lane culminates here.
            val lane = overview.radiantLanes.firstOrNull {
                it.culmination == moment.instant
            }
            TimelineMark(
                instant = moment.instant,
                title = stringResource(
                    R.string.detail_radiant_moment_title,
                    lane?.subject?.displayName()
                        ?: stringResource(R.string.category_meteor),
                ),
                shortLabel = stringResource(R.string.timeline_mark_radiant),
                labelRank = 2,
                lines = listOf(
                    if (lane != null) {
                        stringResource(
                            R.string.detail_radiant_moment_line,
                            formatTime(moment.instant, zone),
                            Math.round(lane.culminationAltitudeDeg),
                        )
                    } else {
                        stringResource(
                            R.string.detail_time_line,
                            formatTime(moment.instant, zone),
                        )
                    },
                ),
            )
        }
    }
}

/**
 * The tonight-at-a-glance block (ui-design §7.2): four headline facts
 * above the timeline, every one read from data the overview or the
 * cursor evaluation already computed — astronomical night span, Moon
 * altitude at the cursor (labelled with the cursor time; the same
 * value the timeline bubble's second line shows), the moonlight tier
 * (the cursor's own tier while a cursor is placed, the whole-night
 * dusk tier without one) and the low-moonlight windows. Without a
 * cursor the Moon-altitude cell honestly shows "—".
 */
@Composable
private fun TonightOverviewBlock(
    overview: TonightOverviewUseCase.Result,
    cursorEvaluation: TonightOverviewUseCase.CursorEvaluation?,
    cursorInstant: Instant?,
    zone: ZoneId,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle(stringResource(R.string.tonight_overview_title))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    OverviewCellLabel(stringResource(R.string.tonight_overview_night))
                    Text(
                        text = overview.night?.let { night ->
                            "${formatTime(night.dusk, zone)} – ${formatTime(night.dawn, zone)}"
                        } ?: stringResource(R.string.tonight_overview_night_none),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    OverviewCellLabel(
                        if (cursorInstant != null) {
                            stringResource(
                                R.string.tonight_overview_moon_alt_cursor,
                                formatTime(cursorInstant, zone),
                            )
                        } else {
                            stringResource(R.string.tonight_overview_moon_alt)
                        },
                    )
                    Text(
                        text = cursorEvaluation?.let {
                            stringResource(R.string.tonight_overview_moon_alt_value, it.moonAltitudeDeg)
                        } ?: "—",
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    OverviewCellLabel(
                        if (cursorEvaluation != null && cursorInstant != null) {
                            stringResource(
                                R.string.tonight_overview_moon_cursor,
                                formatTime(cursorInstant, zone),
                            )
                        } else {
                            stringResource(R.string.tonight_overview_moon)
                        },
                    )
                    Text(
                        text = (cursorEvaluation?.moonInterference
                            ?: overview.moonInterferenceAtDusk)?.let {
                            stringResource(moonInterferenceNameRes(it))
                        } ?: "—",
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    OverviewCellLabel(stringResource(R.string.tonight_overview_low_moon))
                    // One or two windows share a single line; three or
                    // more list one per line so the cell stays scannable
                    // instead of running one long joined string
                    // (ui-design §7.2).
                    val windows = overview.lowMoonlightWindows
                    if (windows.isEmpty()) {
                        Text(text = stringResource(R.string.tonight_overview_low_moon_none))
                    } else if (windows.size <= 2) {
                        Text(
                            text = windows.joinToString(
                                separator = stringResource(R.string.tonight_overview_window_separator),
                            ) { window ->
                                "${formatTime(window.start, zone)} – ${formatTime(window.end, zone)}"
                            },
                        )
                    } else {
                        Column {
                            windows.forEach { window ->
                                Text(
                                    text = "${formatTime(window.start, zone)} – " +
                                        formatTime(window.end, zone),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewCellLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Location card (ui-design §7.2): positioning, light pollution and the
 * dusk environment in one card. The source line is the location
 * affordance — tapping it re-fixes or switches to GPS; coordinates
 * are shown as numbers — tapping them is the edit entry — and are
 * edited in place. The light-pollution row shows only the Bortle
 * estimate, or — where the grid has no data — the manual Bortle
 * selector, the only one in the app. The environment rows below it
 * (no heading since 0.8.12) carry the moonlight contribution and the
 * total sky (the page's only copy of those figures), one merged
 * footnote directly under the total (sample instant, galactic-centre
 * direction, baseline source) and the full-moon degradation line.
 * While a cursor is placed, the moonlight and total-sky figures
 * follow it ([cursorSky], the cursor evaluation's own
 * galactic-centre sample at [cursorSkyInstant]); the
 * light-pollution row does not follow — it is a property of the
 * site. A GPS or manual site shows the altitude line (a fix
 * altitude, or "—" when there is none), and so does a selected
 * favourite whose record carries a GPS altitude source; a GPS or
 * manual site that is not yet a favourite also gets the button that
 * saves it as one, riding in the coordinate row (0.8.12).
 */
@Composable
private fun LocationCard(
    overview: TonightOverviewUseCase.Result,
    zone: ZoneId,
    latitudeDeg: Double,
    longitudeDeg: Double,
    locationLabel: String?,
    locationSourceText: String,
    manualBortle: Int?,
    onManualBortleChange: (Int?) -> Unit,
    onManualLocation: (Double, Double) -> Unit,
    onUseGps: () -> Unit,
    showAltitudeLine: Boolean,
    gpsAltitudeMeters: Double?,
    canSaveFavourite: Boolean,
    onSaveFavourite: (String) -> Unit,
    showGpsSyncDot: Boolean,
    cursorSky: SkyContext?,
    cursorSkyInstant: Instant?,
) {
    var editing by remember { mutableStateOf(false) }
    var latText by remember { mutableStateOf("") }
    var lonText by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf(false) }
    var savingFavourite by remember { mutableStateOf(false) }
    var favouriteName by remember { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (locationLabel != null) {
                Text(text = locationLabel, style = MaterialTheme.typography.titleSmall)
            }
            // The source line itself is the GPS affordance
            // (ui-design §7.2): a full-width, 48dp-tall touch target
            // that re-fixes or switches the site to GPS — the whole
            // row answers the tap, not just the text's own width.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { onUseGps() },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = locationSourceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!editing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // GPS sync dot (ui-design §7.2): ahead of the
                        // coordinate figures while the site's
                        // coordinates came from a GPS fix — the state
                        // "ok" colour, which the red-light theme maps
                        // into its red scale like every state colour.
                        // The row merges its semantics so a screen
                        // reader hears the coordinates together with
                        // the dot's "GPS synced" description.
                        if (showGpsSyncDot) {
                            val gpsSyncedDescription =
                                stringResource(R.string.location_gps_synced)
                            Text(
                                text = "●",
                                color = app.astroexposure.ui.theme.LocalStateColors.current.ok,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .semantics {
                                        contentDescription = gpsSyncedDescription
                                    },
                            )
                        }
                        // The edit entry is a full 48dp-tall touch target
                        // (ui-design §2), not just the text line's height.
                        // The figures themselves are the entry — no
                        // pencil glyph (removed in 0.8.12); the hint
                        // lives in the semantics instead, so a screen
                        // reader still hears that the row edits the
                        // coordinates (the row merges descendants).
                        val editCoordinatesHint =
                            stringResource(R.string.location_edit_hint)
                        Box(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .heightIn(min = 48.dp)
                                .clickable {
                                    latText = formatCoordinateValue(latitudeDeg)
                                    lonText = formatCoordinateValue(longitudeDeg)
                                    editError = false
                                    editing = true
                                }
                                .semantics {
                                    contentDescription = editCoordinatesHint
                                },
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = "${formatCoordinateValue(latitudeDeg)}°  ${formatCoordinateValue(longitudeDeg)}°",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.titleMedium,
                                // The row shares its width with the
                                // save-as-favourite button (0.8.12): on
                                // a narrow screen the coordinates give
                                // way first, never the button's label.
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // Saving the current site as a favourite (ui-design
                    // §7.2): offered while the site is an unsaved GPS fix
                    // or a manual-coordinate entry; once saved, the
                    // favourite match takes over the name line and this
                    // button disappears. Since 0.8.12 the button rides
                    // in this coordinate row, right after the figures,
                    // instead of taking a row of its own under the card.
                    if (canSaveFavourite) {
                        OutlinedButton(
                            onClick = {
                                favouriteName = ""
                                savingFavourite = true
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.location_save_favourite),
                                maxLines = 1,
                            )
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latText,
                        onValueChange = { latText = it },
                        label = { Text(stringResource(R.string.location_latitude)) },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = lonText,
                        onValueChange = { lonText = it },
                        label = { Text(stringResource(R.string.location_longitude)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (editError) {
                    Text(
                        text = stringResource(R.string.location_edit_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val lat = latText.toDoubleOrNull()
                            val lon = lonText.toDoubleOrNull()
                            if (lat != null && lon != null && Coordinates.isValid(lat, lon)) {
                                onManualLocation(lat, lon)
                                editing = false
                            } else {
                                editError = true
                            }
                        },
                    ) { Text(stringResource(R.string.location_edit_confirm)) }
                    OutlinedButton(onClick = { editing = false }) {
                        Text(stringResource(R.string.location_edit_cancel))
                    }
                }
            }

            // The altitude line (ui-design §7.2): shown for GPS and
            // manual sites and for a selected favourite whose record
            // carries a GPS altitude source; a fix without altitude —
            // and a manual site, which has no altitude source — shows
            // "—" rather than an invented 0 m.
            if (showAltitudeLine) {
                Text(
                    text = gpsAltitudeMeters?.let {
                        stringResource(R.string.location_altitude, it.roundToInt())
                    } ?: stringResource(R.string.location_altitude_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (savingFavourite) {
                AlertDialog(
                    onDismissRequest = { savingFavourite = false },
                    title = { Text(stringResource(R.string.location_save_favourite)) },
                    text = {
                        OutlinedTextField(
                            value = favouriteName,
                            onValueChange = { favouriteName = it },
                            label = { Text(stringResource(R.string.settings_field_name)) },
                            singleLine = true,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                onSaveFavourite(favouriteName.trim())
                                savingFavourite = false
                            },
                            enabled = favouriteName.isNotBlank(),
                        ) { Text(stringResource(R.string.location_edit_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { savingFavourite = false }) {
                            Text(stringResource(R.string.location_edit_cancel))
                        }
                    },
                )
            }

            // Environment rows (ui-design §7.2): light pollution,
            // moonlight contribution and total sky — the page's only
            // copy of the sky figures. No heading row since 0.8.12:
            // the rows speak for themselves and the card stays short.
            val info = overview.lightPollutionInfo
            if (info?.artificialMcdPerM2 != null) {
                // Only the Bortle estimate (ui-design §7.2): the
                // brightness figure and the data version stay with the
                // dataset; the merged footnote under the total still
                // names where the baseline comes from.
                Text(
                    text = stringResource(
                        R.string.location_light_row,
                        info.bortleEstimate ?: 0,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(text = stringResource(R.string.location_light_no_data))
                BortleSelector(manualBortle, onManualBortleChange)
                if (manualBortle != null) {
                    Text(
                        text = stringResource(R.string.location_light_manual, manualBortle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Moonlight contribution and total sky, moved here from
            // the former standalone sky card (ui-design §7.2): one
            // sample toward the galactic centre — the cursor's own
            // sample while a cursor is placed, the dusk snapshot
            // otherwise; the figures are whichever sample is on
            // display, never a blend. The moonrise note belongs to
            // the dusk display only: it explains a dusk zero the
            // cursor display never shows (there the contribution is
            // the cursor instant's real figure). The total is the
            // section's conclusion value and carries the accent
            // colour; the merged footnote sits directly under it and
            // names the displayed sample's instant, and the full-moon
            // degradation line follows — judged, like the figures,
            // from the displayed sample.
            val sky = cursorSky ?: overview.skyAtDusk
            if (sky == null) {
                Text(text = stringResource(R.string.tonight_sky_source_none))
            } else {
                Text(
                    text = stringResource(R.string.location_env_moonlight, sky.moonlightMcdPerM2) +
                        (if (cursorSky == null) {
                            overview.moonriseWithinNight?.let { rise ->
                                " " + stringResource(
                                    R.string.location_env_rise_note,
                                    formatTime(rise, zone),
                                )
                            }
                        } else {
                            null
                        } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.location_env_total, sky.totalSky.totalMpsas),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                // One merged footnote (ui-design §7.2, 0.8.12): the
                // displayed sample's instant, the galactic-centre
                // direction and the baseline source in a single short
                // line — it was a scope sentence plus a separate
                // source line before. The full-moon degradation line
                // after it stays its own warning, unmerged.
                val baselineText = stringResource(
                    when (sky.totalSky.baselineSource) {
                        BaselineSource.ATLAS -> R.string.tonight_sky_baseline_atlas
                        BaselineSource.MANUAL_BORTLE -> R.string.tonight_sky_baseline_manual
                        BaselineSource.NONE -> R.string.tonight_sky_baseline_none
                    },
                )
                Text(
                    text = if (cursorSky != null && cursorSkyInstant != null) {
                        stringResource(
                            R.string.location_env_note_merged_cursor,
                            formatTime(cursorSkyInstant, zone),
                            baselineText,
                        )
                    } else {
                        stringResource(
                            R.string.location_env_note_merged_dusk,
                            baselineText,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sky.totalSky.moonDegraded) {
                    Text(
                        text = stringResource(
                            R.string.tonight_moon_degraded,
                            percentRounded(sky.moonIllumination),
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Date switch (ui-design §7.3): steppers plus a calendar dialog, bounded
 * to anchor −7 … +30 days by the domain window; dates off the anchor
 * carry a relative label and a one-tap return.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateSwitchRow(
    date: LocalDate,
    anchorDate: LocalDate,
    onDateChange: (LocalDate) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val relative = TonightDateWindow.relative(anchorDate, date)
    val dateText = remember(date) {
        val day = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        "$day $weekday"
    }
    val label = when (relative) {
        TonightDateWindow.Relative.TONIGHT -> stringResource(R.string.date_label_tonight, dateText)
        TonightDateWindow.Relative.TOMORROW -> stringResource(R.string.date_label_tomorrow, dateText)
        TonightDateWindow.Relative.YESTERDAY -> stringResource(R.string.date_label_yesterday, dateText)
        TonightDateWindow.Relative.FUTURE -> stringResource(
            R.string.date_label_future,
            TonightDateWindow.daysFromAnchor(anchorDate, date).toInt(),
            dateText,
        )
        TonightDateWindow.Relative.PAST -> stringResource(
            R.string.date_label_past,
            -TonightDateWindow.daysFromAnchor(anchorDate, date).toInt(),
            dateText,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OutlinedButton(
            onClick = { onDateChange(TonightDateWindow.stepped(anchorDate, date, -1)) },
            enabled = TonightDateWindow.canStep(anchorDate, date, -1),
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("◀") }
        TextButton(onClick = { showPicker = true }) { Text(label) }
        OutlinedButton(
            onClick = { onDateChange(TonightDateWindow.stepped(anchorDate, date, 1)) },
            enabled = TonightDateWindow.canStep(anchorDate, date, 1),
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("▶") }
    }
    if (relative != TonightDateWindow.Relative.TONIGHT) {
        TextButton(
            onClick = { onDateChange(anchorDate) },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.date_back_to_tonight))
        }
    }
    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val picked = Instant.ofEpochMilli(utcTimeMillis)
                        .atZone(ZoneOffset.UTC).toLocalDate()
                    return TonightDateWindow.isInWindow(anchorDate, picked)
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            onDateChange(
                                TonightDateWindow.clamp(
                                    anchorDate,
                                    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(),
                                ),
                            )
                        }
                        showPicker = false
                    },
                ) { Text(stringResource(R.string.location_edit_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(R.string.location_edit_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState, showModeToggle = false)
        }
    }
}

/** The moonlight-interference tier's display name (status row and target detail share it). */
fun moonInterferenceNameRes(tier: TimelineCursor.MoonInterference): Int = when (tier) {
    TimelineCursor.MoonInterference.NONE -> R.string.cursor_interference_none
    TimelineCursor.MoonInterference.WEAK -> R.string.cursor_interference_weak
    TimelineCursor.MoonInterference.STRONG -> R.string.cursor_interference_strong
}

/** Display name of a key moment (ui-design §7.4 moment set), for the cursor bubble's snap label. */
@Composable
private fun momentKindName(kind: TonightOverviewUseCase.MomentKind): String = stringResource(
    when (kind) {
        TonightOverviewUseCase.MomentKind.SUNSET -> R.string.moment_sunset
        TonightOverviewUseCase.MomentKind.CIVIL_DUSK_END -> R.string.moment_civil_dusk
        TonightOverviewUseCase.MomentKind.NAUTICAL_DUSK_END -> R.string.moment_nautical_dusk
        TonightOverviewUseCase.MomentKind.ASTRO_DUSK -> R.string.moment_astro_dusk
        TonightOverviewUseCase.MomentKind.MOONRISE -> R.string.moment_moonrise
        TonightOverviewUseCase.MomentKind.MOONSET -> R.string.moment_moonset
        TonightOverviewUseCase.MomentKind.GC_CULMINATION -> R.string.moment_gc_culmination
        TonightOverviewUseCase.MomentKind.RADIANT_CULMINATION -> R.string.moment_radiant_culmination
        TonightOverviewUseCase.MomentKind.ASTRO_DAWN -> R.string.moment_astro_dawn
        TonightOverviewUseCase.MomentKind.SUNRISE -> R.string.moment_sunrise
    },
)

/**
 * The cursor state's strip below the axis (ui-design §7.5): the
 * former "time · segment" status row was deleted in 0.8.4 — the
 * cursor time lives on the axis bubble and in the overview block's
 * Moon-altitude label, and the facts moved to the tonight-at-a-glance
 * block above the timeline (ui-design §7.2), so nothing is stated
 * twice. What remains here is only what has no other home: the
 * loading ellipsis while an evaluation for the current cursor
 * position is still in flight (never stale results presented as
 * current), and the catalogue-unavailable note. Target details live
 * in the dialog opened from the arc lanes.
 */
@Composable
private fun CursorStatusCard(
    evaluation: TonightOverviewUseCase.CursorEvaluation?,
) {
    if (evaluation != null && evaluation.targetsAvailable) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (evaluation == null) {
                Text(text = "…", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(text = stringResource(R.string.tonight_recommendations_unavailable))
            }
        }
    }
}

/**
 * Which calculator a catalogue target's detail dialog jumps to
 * (ui-design §7.5): every catalogue type is a deep-sky object, so the
 * table maps them all to the deep-sky calculator. A type with no
 * calculator would map to null and the dialog would show no jump
 * action — a destination is never invented.
 */
fun targetCalcCategory(type: DeepSkyObjectType): CalcCategory? = when (type) {
    DeepSkyObjectType.GALAXY,
    DeepSkyObjectType.OPEN_CLUSTER,
    DeepSkyObjectType.GLOBULAR_CLUSTER,
    DeepSkyObjectType.NEBULA,
    DeepSkyObjectType.PLANETARY_NEBULA,
    DeepSkyObjectType.SUPERNOVA_REMNANT,
    DeepSkyObjectType.DOUBLE_STAR,
    DeepSkyObjectType.ASTERISM,
    DeepSkyObjectType.OTHER,
    -> CalcCategory.DEEP_SKY
}

/**
 * Which calculator a tonight subject's detail dialog jumps to
 * (ui-design §7.8): deep-sky objects keep the type table above;
 * constellations and wide-field viewpoints go to the wide-field
 * calculator with the centre declination carried in (the same
 * carry-in rule as deep-sky objects); a radiant goes to the meteor
 * calculator with its shower selected.
 */
fun subjectCalcCategory(
    subject: TonightOverviewUseCase.TonightSubject,
): CalcCategory? = when (subject) {
    is TonightOverviewUseCase.TonightSubject.DeepSky ->
        targetCalcCategory(subject.obj.type)
    is TonightOverviewUseCase.TonightSubject.Constellation -> CalcCategory.WIDE_FIELD
    is TonightOverviewUseCase.TonightSubject.Viewpoint -> CalcCategory.WIDE_FIELD
    is TonightOverviewUseCase.TonightSubject.Radiant -> CalcCategory.METEOR
}

/**
 * The cursor state's lane assembly (ui-design §7.8): the ranked
 * shootable targets, then the whole-night radiant lanes that are
 * not already among them. The cursor evaluation's universe includes
 * tonight's qualifying radiants, so a radiant can arrive from both
 * sources; merging by subject id keeps exactly one lane per
 * radiant — the cursor-evaluated one, which carries this instant's
 * arc. Lanes without a subject id cannot collide and always pass
 * through; duplicates within the radiant list itself also collapse.
 */
internal fun mergeCursorStateArcs(
    targetArcs: List<TimelineArc>,
    radiantArcs: List<TimelineArc>,
): List<TimelineArc> {
    val seen = HashSet<String>()
    val out = ArrayList<TimelineArc>(targetArcs.size + radiantArcs.size)
    for (arc in targetArcs) {
        if (arc.subjectId.isNotEmpty()) seen += arc.subjectId
        out += arc
    }
    for (arc in radiantArcs) {
        if (arc.subjectId.isNotEmpty() && !seen.add(arc.subjectId)) continue
        out += arc
    }
    return out
}

/** A cursor-state target as a timeline lane (ui-design §7.8). */
@Composable
private fun TonightOverviewUseCase.CursorTarget.toTimelineArc(
    highlightedSubjectId: String?,
): TimelineArc = TimelineArc(
    intervals = arcIntervals,
    label = "${subject.category.categoryTag()} ${subject.laneLabel()}",
    altitudeSamples = altitudeSamples,
    phases = phases,
    recommendedFrame = recommendedFrame,
    subjectId = subject.id,
    subjectName = subject.laneLabel(),
    category = subject.category,
    radiant = subject is TonightOverviewUseCase.TonightSubject.Radiant,
    highlighted = subject.id == highlightedSubjectId,
)

/** A whole-night lane entry as a timeline lane (ui-design §7.8). */
@Composable
private fun TonightOverviewUseCase.LaneEntry.toTimelineArc(
    highlightedSubjectId: String?,
): TimelineArc = TimelineArc(
    intervals = arcIntervals,
    label = "${subject.category.categoryTag()} ${subject.laneLabel()}",
    altitudeSamples = altitudeSamples,
    phases = phases,
    recommendedFrame = recommendedFrame,
    subjectId = subject.id,
    subjectName = subject.laneLabel(),
    category = subject.category,
    radiant = subject is TonightOverviewUseCase.TonightSubject.Radiant,
    highlighted = subject.id == highlightedSubjectId,
)

/**
 * A qualified entry as a targets-sheet row (ui-design §7.8,
 * 0.10.0): name and tag from the shared presentation source; the
 * key line carries the category's key quantities — deep sky its
 * magnitude and culmination, constellation and viewpoint their
 * culmination and shootable window, a radiant its peak (date and
 * signed distance), radiant window and ZHR. Every figure is the
 * use case's own; the row composes text, never numbers.
 */
@Composable
private fun TonightOverviewUseCase.QualifiedEntry.toTonightTarget(): TonightTarget {
    val culminationText = String.format(java.util.Locale.US, "%.1f", culminationAltitudeDeg)
    val keyLine = when (subject.category) {
        TonightOverviewUseCase.SubjectCategory.DEEP_SKY -> stringResource(
            R.string.targets_key_deep_sky,
            String.format(java.util.Locale.US, "%.1f", magnitude ?: 0.0),
            culminationText,
        )
        TonightOverviewUseCase.SubjectCategory.CONSTELLATION,
        TonightOverviewUseCase.SubjectCategory.WIDE_FIELD,
        -> stringResource(
            R.string.targets_key_window,
            culminationText,
            windowMinutes.toInt(),
        )
        TonightOverviewUseCase.SubjectCategory.METEOR -> {
            val peakDate = meteorPeakDate
            val days = meteorDaysFromPeak ?: 0.0
            val peakText = if (peakDate != null) {
                val english = isEnglishUiLanguage()
                val dateText = peakDate.format(
                    DateTimeFormatter.ofPattern(
                        if (english) "MMM d" else "M月d日",
                        if (english) java.util.Locale.US else java.util.Locale.CHINA,
                    ),
                )
                val rounded = kotlin.math.abs(days).roundToLong()
                dateText + when {
                    rounded == 0L -> stringResource(R.string.targets_peak_today)
                    days < 0 -> stringResource(R.string.targets_peak_in_days, rounded.toInt())
                    else -> stringResource(R.string.targets_peak_days_ago, rounded.toInt())
                }
            } else {
                ""
            }
            stringResource(
                R.string.targets_key_meteor,
                peakText,
                windowMinutes.toInt(),
                meteorZhr ?: 0,
            )
        }
    }
    return TonightTarget(
        subjectId = subject.id,
        category = subject.category,
        name = subject.displayName(),
        tag = subject.category.categoryTag(),
        keyLine = keyLine,
        onBoard = onBoard,
        recommendedStart = recommended?.interval?.start,
        recommendedEnd = recommended?.interval?.end,
        relativeBest = recommended?.relativeBest == true,
        culmination = culmination,
    )
}

@Composable
fun BortleSelector(selected: Int?, onChange: (Int?) -> Unit) {
    Column {
        Text(text = stringResource(R.string.bortle_manual_label), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..9).forEach { bortle ->
                FilterChip(
                    modifier = Modifier.heightIn(min = 48.dp),
                    selected = selected == bortle,
                    onClick = { onChange(if (selected == bortle) null else bortle) },
                    label = { Text(bortle.toString()) },
                )
            }
        }
    }
}

/**
 * The previous run's crash record (0.11.1), pinned to the top of
 * the Tonight page until dismissed: the header line and the full
 * stack trace in monospace, scrollable inside a bounded height,
 * with copy and dismiss actions (dismiss deletes the record
 * file). Renders nothing at all when no record exists — in
 * normal use the page carries no trace of the mechanism.
 */
@Composable
private fun CrashRecordCard() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var record by remember { mutableStateOf(CrashTrap.read(context)) }
    val text = record ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle(stringResource(R.string.crash_record_title))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState()),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(text)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.crash_record_copy))
                }
                TextButton(
                    onClick = {
                        CrashTrap.clear(context)
                        record = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.crash_record_dismiss))
                }
            }
        }
    }
}
