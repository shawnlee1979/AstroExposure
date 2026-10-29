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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.astroexposure.R
import app.astroexposure.domain.catalog.DeepSkyObject
import app.astroexposure.domain.catalog.DeepSkyObjectType
import app.astroexposure.domain.geo.Coordinates
import app.astroexposure.domain.sky.BaselineSource
import app.astroexposure.domain.sky.MoonPhaseName
import app.astroexposure.domain.timeline.NightTimeline
import app.astroexposure.domain.timeline.TimelineCursor
import app.astroexposure.domain.timeline.TonightDateWindow
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    onOpenTargetCalculator: (CalcCategory, DeepSkyObject) -> Unit,
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
    val bubbleMoonText = if (cursorActive && retainedCursor != null) {
        val moonAlt = if (evalMatchesCursor) {
            cursorEval!!.moonAltitudeDeg
        } else {
            TimelineCursor.moonAltitudeAt(overview.timeline.samples, retainedCursor!!)
        }
        // All bubble figures describe the cursor instant: while the
        // evaluation is in flight the illumination is interpolated
        // from the timeline's own samples at the cursor, not taken
        // from dusk. The light-pollution figure is the location
        // card's value for this site (same source, same format).
        val illumination = if (evalMatchesCursor) {
            cursorEval!!.moonIllumination
        } else {
            TimelineCursor.moonIlluminationAt(overview.timeline.samples, retainedCursor!!)
                ?: overview.moonIllumination
        }
        moonAlt?.let {
            stringResource(
                R.string.cursor_bubble_moon, it, percentRounded(illumination),
                overview.lightPollutionInfo?.artificialMcdPerM2?.let { lp ->
                    "%.2f".format(lp)
                } ?: "—",
            )
        }
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
            arcs = if (cursorActive && evalMatchesCursor) {
                cursorEval!!.targets.map { target ->
                    TimelineArc(
                        intervals = target.arcIntervals,
                        phases = target.phases,
                        culmination = target.culmination,
                        label = target.obj.nameZh ?: target.obj.name,
                        culminationText = stringResource(
                            R.string.cursor_arc_culmination,
                            formatTime(target.culmination, zone),
                            target.culminationAltitudeDeg,
                        ),
                        recommendedFrame = target.recommendedFrame,
                    )
                }
            } else {
                emptyList()
            },
            onCursorTap = { tapped ->
                if (!cursorActive && retainedCursor != null) {
                    // After "whole night", the next tap restores the
                    // retained cursor position (ui-design §7.5) — a
                    // restore, not a new placement, so the lock does
                    // not block it.
                    cursorActive = true
                } else if (
                    TimelineCursor.mayMoveCursor(
                        cursorPlacement, TimelineCursor.CursorGesture.TAP,
                    )
                ) {
                    placeCursor(tapped)
                }
                // A placed cursor is never moved by a plain tap
                // (anti-mistouch, ui-design §7.5): repositioning takes
                // the long-press drag below.
            },
            onCursorDrag = { dragged -> placeCursor(dragged) },
            cursorLocked = cursorLocked,
            onCursorLongPressDrag = { dragged ->
                if (
                    TimelineCursor.mayMoveCursor(
                        cursorPlacement, TimelineCursor.CursorGesture.LONG_PRESS_DRAG,
                    )
                ) {
                    placeCursor(dragged)
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
            },
            onArcTap = { index ->
                // Tapping a lane opens its target's detail dialog
                // (ui-design §7.5): the detail is evaluated on demand
                // for the tapped target at the cursor instant.
                if (evalMatchesCursor) {
                    cursorEval!!.targets.getOrNull(index)?.let { detailObjectId = it.obj.id }
                }
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
            val detail = targetDetail?.takeIf { it.obj.id == id }
            val tappedName = cursorEval?.targets?.firstOrNull { it.obj.id == id }?.obj
                ?.let { it.nameZh ?: it.name }
            val category = detail?.let { targetCalcCategory(it.obj.type) }
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
                    onAction = if (category != null) {
                        {
                            onOpenTargetCalculator(category, detail.obj)
                            detailObjectId = null
                        }
                    } else {
                        null
                    },
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
            CursorStatusCard(
                evaluation = if (evalMatchesCursor) cursorEval else null,
            )
        } else {
            // Static bright-object shortlist (unchanged whole-night rules).
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SectionTitle(stringResource(R.string.tonight_recommendations_title))
                    if (!overview.recommendationsAvailable) {
                        Text(text = stringResource(R.string.tonight_recommendations_unavailable))
                    } else if (overview.recommendations.isEmpty()) {
                        Text(text = stringResource(R.string.tonight_recommendations_none))
                    } else {
                        overview.recommendations.forEach { rec ->
                            Text(
                                text = stringResource(
                                    R.string.tonight_recommendation_item,
                                    rec.obj.nameZh ?: rec.obj.name,
                                    rec.obj.magnitude?.toString() ?: "—",
                                    formatTime(rec.pass.culmination, zone),
                                    rec.pass.culminationAltitude.value,
                                ),
                            )
                        }
                    }
                }
            }
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
 * Build the label-free timeline mark for one key moment (ui-design
 * §7.4): Sun and Moon moments open the shared Sun / Moon detail (rise
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
 * are shown as numbers with an edit affordance and are edited in
 * place. The light-pollution row shows only the Bortle estimate, or —
 * where the grid has no data — the manual Bortle selector, the only
 * one in the app. The environment section below it carries the
 * moonlight contribution and the total sky (the page's only copy of
 * those figures), the scope footnote directly under the total, the
 * full-moon degradation line and the baseline source note. While a
 * cursor is placed, the moonlight and total-sky figures follow it
 * ([cursorSky], the cursor evaluation's own galactic-centre sample at
 * [cursorSkyInstant]); the light-pollution row does not follow — it
 * is a property of the site. A GPS or
 * manual site shows the altitude line (a fix altitude, or "—" when
 * there is none), and so does a selected favourite whose record
 * carries a GPS altitude source; a GPS or manual site that is not yet
 * a favourite also gets the button that saves it as one.
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // GPS sync dot (ui-design §7.2): ahead of the
                        // coordinate figures while the site's
                        // coordinates came from a GPS fix — the state
                        // "ok" colour, which the red-light theme maps
                        // into its red scale like every state colour.
                        if (showGpsSyncDot) {
                            Text(
                                text = "●",
                                color = app.astroexposure.ui.theme.LocalStateColors.current.ok,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        // The edit entry is a full 48dp-tall touch target
                        // (ui-design §2), not just the text line's height.
                        Box(
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clickable {
                                    latText = formatCoordinateValue(latitudeDeg)
                                    lonText = formatCoordinateValue(longitudeDeg)
                                    editError = false
                                    editing = true
                                },
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = "${formatCoordinateValue(latitudeDeg)}°  ${formatCoordinateValue(longitudeDeg)}°  ✎",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.titleMedium,
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
            // Saving the current site as a favourite (ui-design
            // §7.2): offered while the site is an unsaved GPS fix or a
            // manual-coordinate entry; once saved, the favourite match
            // takes over the name line and this button disappears.
            if (canSaveFavourite) {
                OutlinedButton(
                    onClick = {
                        favouriteName = ""
                        savingFavourite = true
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.location_save_favourite))
                }
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

            // Environment section (ui-design §7.2): light pollution,
            // moonlight contribution and total sky under one heading —
            // the page's only copy of the sky figures.
            Text(
                text = stringResource(R.string.location_env_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val info = overview.lightPollutionInfo
            if (info?.artificialMcdPerM2 != null) {
                // Only the Bortle estimate (ui-design §7.2): the
                // brightness figure and the data version stay with the
                // dataset; the baseline source note at the section's
                // end still names where the value comes from.
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
            // colour; the scope footnote sits directly under it and
            // names the displayed sample's instant, then the
            // full-moon degradation line — judged, like the figures,
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
                Text(
                    text = if (cursorSky != null && cursorSkyInstant != null) {
                        stringResource(
                            R.string.location_env_note_cursor,
                            formatTime(cursorSkyInstant, zone),
                        )
                    } else {
                        stringResource(R.string.location_env_note)
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
                Text(
                    text = stringResource(
                        when (sky.totalSky.baselineSource) {
                            BaselineSource.ATLAS -> R.string.tonight_sky_source_atlas
                            BaselineSource.MANUAL_BORTLE -> R.string.tonight_sky_source_manual
                            BaselineSource.NONE -> R.string.tonight_sky_source_none
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
