package app.astroexposure.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
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
import app.astroexposure.domain.catalog.LightPollutionVersionFormat
import app.astroexposure.domain.geo.Coordinates
import app.astroexposure.domain.sky.BaselineSource
import app.astroexposure.domain.sky.MoonPhaseName
import app.astroexposure.domain.timeline.NightTimeline
import app.astroexposure.domain.timeline.TimelineCursor
import app.astroexposure.domain.timeline.TonightDateWindow
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Tonight overview (ui-design §4.1 and §7): location card (positioning +
 * light pollution in one card) → date switch → the static four-layer
 * timeline as the page backbone → moon/total-sky verdict → static
 * bright-object shortlist → ±3-day events. Conclusion first;
 * degradations stated plainly in their §7.4 positions.
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
    onRedLightChange: (Boolean) -> Unit,
    onUseGps: () -> Unit,
    manualBortle: Int?,
    onManualBortleChange: (Int?) -> Unit,
    onManualLocation: (Double, Double) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onEvaluateCursor: (Instant, String?) -> TonightOverviewUseCase.CursorEvaluation,
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
    // The activated target of the detail card (ui-design §7.5, 0.5.1):
    // an object id, not a list index, so the activation survives the
    // cursor moving to moments where the ranking changes — the card
    // keeps its target and shows its honest state there.
    var activeTargetId by remember(date) { mutableStateOf<String?>(null) }
    var lastSnappedMoment by remember(date) { mutableStateOf<Int?>(null) }

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
    // debounce so a drag does not evaluate every intermediate minute),
    // carrying the activated target's id so its detail state is
    // evaluated for the same instant.
    LaunchedEffect(cursorActive, retainedCursor, overview, activeTargetId) {
        cursorEval = if (cursorActive && retainedCursor != null) {
            delay(120)
            withContext(Dispatchers.Default) {
                onEvaluateCursor(retainedCursor!!, activeTargetId)
            }
        } else {
            null
        }
    }

    val evalMatchesCursor = cursorEval != null && cursorEval!!.instant == retainedCursor

    // Default activation (ui-design §7.5, 0.5.1): entering the cursor
    // state with no activated target activates the top-ranked one as
    // soon as its evaluation lands.
    LaunchedEffect(cursorEval, cursorActive) {
        val evaluation = cursorEval
        if (cursorActive && evaluation != null && evaluation.instant == retainedCursor &&
            activeTargetId == null
        ) {
            evaluation.targets.firstOrNull()?.let { activeTargetId = it.obj.id }
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
        // Both bubble figures describe the cursor instant: while the
        // evaluation is in flight the illumination is interpolated
        // from the timeline's own samples at the cursor, not taken
        // from dusk.
        val illumination = if (evalMatchesCursor) {
            cursorEval!!.moonIllumination
        } else {
            TimelineCursor.moonIlluminationAt(overview.timeline.samples, retainedCursor!!)
                ?: overview.moonIllumination
        }
        moonAlt?.let {
            stringResource(
                R.string.cursor_bubble_moon, it, Math.round(illumination * 100),
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
        // Status line: time-health dot + red-light switch.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (timeHealth.healthy) "●" else "●",
                color = if (timeHealth.healthy) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = stringResource(R.string.red_light), style = MaterialTheme.typography.labelSmall)
                Switch(checked = redLight, onCheckedChange = onRedLightChange)
            }
        }

        // Time-health warnings, stated plainly (design §2.2).
        for (warning in timeHealth.warnings) {
            Text(
                text = when (warning) {
                    TimeHealthCheck.Warning.ZONE_MISMATCH ->
                        stringResource(R.string.time_health_zone_mismatch)
                    TimeHealthCheck.Warning.CLOCK_SKEW ->
                        stringResource(
                            R.string.time_health_clock_skew,
                            timeHealth.clockSkewSeconds ?: 0L,
                        )
                },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        LocationCard(
            overview = overview,
            latitudeDeg = latitudeDeg,
            longitudeDeg = longitudeDeg,
            locationLabel = locationLabel,
            locationSourceText = locationSourceText,
            manualBortle = manualBortle,
            onManualBortleChange = onManualBortleChange,
            onManualLocation = onManualLocation,
            onUseGps = onUseGps,
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

        NightTimelineCard(
            timeline = overview.timeline,
            marks = overview.moments.map { moment ->
                timelineMarkFor(moment, overview, zone)
            },
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
                    )
                }
            } else {
                emptyList()
            },
            highlightedArc = if (cursorActive && evalMatchesCursor) {
                cursorEval!!.targets.indexOfFirst { it.obj.id == activeTargetId }
                    .takeIf { it >= 0 }
            } else {
                null
            },
            onCursorTap = { tapped ->
                if (!cursorActive && retainedCursor != null) {
                    // After "whole night", the next tap restores the
                    // retained cursor position (ui-design §7.5).
                    cursorActive = true
                } else {
                    placeCursor(tapped)
                }
            },
            onCursorDrag = { dragged -> placeCursor(dragged) },
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
                // Tapping a lane activates its target (ui-design §7.5,
                // 0.5.1): the detail card below switches to it.
                if (evalMatchesCursor) {
                    cursorEval!!.targets.getOrNull(index)?.let { activeTargetId = it.obj.id }
                }
            },
        )

        // Moon / total-sky verdict card (the Bortle fallback lives in the
        // location card now; the source annotation stays here).
        val sky = overview.skyAtDusk
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SectionTitle(stringResource(R.string.tonight_sky_title))
                if (overview.moonDegraded) {
                    Text(
                        text = stringResource(
                            R.string.tonight_moon_degraded,
                            Math.round(overview.moonIllumination * 100),
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (sky == null) {
                    Text(text = stringResource(R.string.tonight_sky_source_none))
                } else {
                    InfoRow(
                        stringResource(R.string.tonight_sky_total, sky.totalSky.totalMpsas, sky.totalSky.bortleEstimate?.toString() ?: "—"),
                        "",
                    )
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

        if (cursorActive && retainedCursor != null) {
            // Cursor state (ui-design §7.5): the recommendation area
            // switches to "shootable at this moment".
            CursorTargetsCard(
                evaluation = if (evalMatchesCursor) cursorEval else null,
                cursorTimeText = formatTime(retainedCursor!!, zone),
                zone = zone,
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
                            Math.round(detail.illumination * 100),
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
 * Location card (ui-design §7.2): positioning and light pollution in one
 * card. Coordinates are shown as numbers with an edit affordance and are
 * edited in place; the GPS button overwrites without confirmation. The
 * light-pollution row shows the grid cell value with its data version,
 * or — where the grid has no data — the manual Bortle selector, the only
 * one in the app.
 */
@Composable
private fun LocationCard(
    overview: TonightOverviewUseCase.Result,
    latitudeDeg: Double,
    longitudeDeg: Double,
    locationLabel: String?,
    locationSourceText: String,
    manualBortle: Int?,
    onManualBortleChange: (Int?) -> Unit,
    onManualLocation: (Double, Double) -> Unit,
    onUseGps: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var latText by remember { mutableStateOf("") }
    var lonText by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (locationLabel != null) {
                Text(text = locationLabel, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                text = locationSourceText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!editing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "%.4f°  %.4f°  ✎".format(latitudeDeg, longitudeDeg),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.clickable {
                            latText = "%.4f".format(latitudeDeg)
                            lonText = "%.4f".format(longitudeDeg)
                            editError = false
                            editing = true
                        },
                    )
                    OutlinedButton(onClick = onUseGps) {
                        Text(stringResource(R.string.location_use_gps))
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

            val info = overview.lightPollutionInfo
            if (info?.artificialMcdPerM2 != null) {
                Text(
                    text = stringResource(
                        R.string.location_light_row,
                        info.bortleEstimate ?: 0,
                        info.artificialMcdPerM2,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Short display composed from the grid header fields
                // (ui-design §7.2), in the current UI language; the raw
                // header string is the fallback when a field is missing.
                val english = androidx.compose.ui.platform.LocalConfiguration.current
                    .locales[0]?.language == "en"
                val shortVersion = info.versionInfo?.let {
                    LightPollutionVersionFormat.shortDisplay(it, english)
                } ?: info.dataVersion
                Text(
                    text = stringResource(R.string.location_light_version, shortVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        ) { Text("◀") }
        TextButton(onClick = { showPicker = true }) { Text(label) }
        OutlinedButton(
            onClick = { onDateChange(TonightDateWindow.stepped(anchorDate, date, 1)) },
            enabled = TonightDateWindow.canStep(anchorDate, date, 1),
        ) { Text("▶") }
    }
    if (relative != TonightDateWindow.Relative.TONIGHT) {
        TextButton(onClick = { onDateChange(anchorDate) }) {
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
 * The "shootable at this moment" card (ui-design §7.5): title with the
 * cursor time and the qualifying count, the four-item status row, and —
 * since the 0.5.1 revision — the detail of the ONE activated target
 * instead of the ranked list. Tapping an arc lane on the timeline
 * switches the activation. [evaluation] is null while an evaluation
 * for the current cursor position is still in flight — the card then
 * shows its title only, never stale results presented as current.
 */
@Composable
private fun CursorTargetsCard(
    evaluation: TonightOverviewUseCase.CursorEvaluation?,
    cursorTimeText: String,
    zone: ZoneId,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionTitle(stringResource(R.string.cursor_title, cursorTimeText))
            if (evaluation == null) {
                Text(text = "…", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            // The qualifying count is rendered only when the catalogue
            // actually loaded: on failure the count is unknown, not
            // zero, and the unavailable line below says so instead.
            if (evaluation.targetsAvailable) {
                Text(
                    text = if (evaluation.totalShootable > evaluation.targets.size) {
                        stringResource(
                            R.string.cursor_count,
                            evaluation.totalShootable, evaluation.targets.size,
                        )
                    } else {
                        stringResource(R.string.cursor_count_all, evaluation.totalShootable)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Status row: segment, Sun altitude, moonlight tier,
            // low-moonlight window membership (ui-design §7.5).
            val segmentText = stringResource(
                when (evaluation.segment) {
                    NightTimeline.SunBand.DAY -> R.string.cursor_segment_day
                    NightTimeline.SunBand.CIVIL_TWILIGHT -> R.string.cursor_segment_civil
                    NightTimeline.SunBand.NAUTICAL_TWILIGHT -> R.string.cursor_segment_nautical
                    NightTimeline.SunBand.ASTRONOMICAL_TWILIGHT ->
                        R.string.cursor_segment_astronomical_twilight
                    NightTimeline.SunBand.ASTRONOMICAL_NIGHT ->
                        R.string.cursor_segment_astronomical_night
                },
            )
            val interferenceText = stringResource(
                when (evaluation.moonInterference) {
                    TimelineCursor.MoonInterference.NONE -> R.string.cursor_interference_none
                    TimelineCursor.MoonInterference.WEAK -> R.string.cursor_interference_weak
                    TimelineCursor.MoonInterference.STRONG -> R.string.cursor_interference_strong
                },
            )
            val lowMoonText = when (val status = evaluation.lowMoonStatus) {
                is TimelineCursor.LowMoonStatus.Inside ->
                    stringResource(R.string.cursor_low_moon_inside, formatTime(status.end, zone))
                is TimelineCursor.LowMoonStatus.Next ->
                    stringResource(R.string.cursor_low_moon_next, formatTime(status.start, zone))
                TimelineCursor.LowMoonStatus.None ->
                    stringResource(R.string.cursor_low_moon_none)
            }
            Text(
                text = stringResource(
                    R.string.cursor_status,
                    segmentText, evaluation.sunAltitudeDeg, interferenceText, lowMoonText,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!evaluation.targetsAvailable) {
                Text(text = stringResource(R.string.tonight_recommendations_unavailable))
            } else if (evaluation.activeTarget != null) {
                ActiveTargetDetail(active = evaluation.activeTarget, zone = zone)
            } else if (evaluation.targets.isEmpty()) {
                Text(text = stringResource(R.string.cursor_none))
            } else {
                // Targets qualify and an activation is being resolved
                // (the default activation lands with the next frame).
                Text(text = "…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * The activated target's detail (ui-design §7.5, 0.5.1 revision):
 * catalogue facts (only the fields the catalogue actually has — a
 * missing magnitude, size or constellation is omitted, never filled
 * in), the target's state at the cursor instant, tonight's ≥20°
 * window with its culmination, the moonlight interference toward this
 * target at the cursor, and the full-frame framing advice.
 */
@Composable
private fun ActiveTargetDetail(
    active: TonightOverviewUseCase.ActiveTarget,
    zone: ZoneId,
) {
    val obj = active.obj
    Text(
        text = obj.nameZh ?: obj.name,
        style = MaterialTheme.typography.titleMedium,
    )
    val catalogParts = buildList {
        add(
            stringResource(
                when (obj.type) {
                    app.astroexposure.domain.catalog.DeepSkyObjectType.GALAXY ->
                        R.string.obj_type_galaxy
                    app.astroexposure.domain.catalog.DeepSkyObjectType.OPEN_CLUSTER ->
                        R.string.obj_type_open_cluster
                    app.astroexposure.domain.catalog.DeepSkyObjectType.GLOBULAR_CLUSTER ->
                        R.string.obj_type_globular_cluster
                    app.astroexposure.domain.catalog.DeepSkyObjectType.NEBULA ->
                        R.string.obj_type_nebula
                    app.astroexposure.domain.catalog.DeepSkyObjectType.PLANETARY_NEBULA ->
                        R.string.obj_type_planetary_nebula
                    app.astroexposure.domain.catalog.DeepSkyObjectType.SUPERNOVA_REMNANT ->
                        R.string.obj_type_supernova_remnant
                    app.astroexposure.domain.catalog.DeepSkyObjectType.DOUBLE_STAR ->
                        R.string.obj_type_double_star
                    app.astroexposure.domain.catalog.DeepSkyObjectType.ASTERISM ->
                        R.string.obj_type_asterism
                    app.astroexposure.domain.catalog.DeepSkyObjectType.OTHER ->
                        R.string.obj_type_other
                },
            ),
        )
        obj.magnitude?.let { add(stringResource(R.string.cursor_detail_magnitude, it)) }
        val sizeMajor = obj.sizeMajorArcmin
        val sizeMinor = obj.sizeMinorArcmin
        if (sizeMajor != null) {
            add(
                if (sizeMinor != null) {
                    stringResource(
                        R.string.cursor_detail_size_both,
                        Math.round(sizeMajor), Math.round(sizeMinor),
                    )
                } else {
                    stringResource(R.string.cursor_detail_size_major, Math.round(sizeMajor))
                },
            )
        }
        obj.constellation?.let { add(stringResource(R.string.cursor_detail_constellation, it)) }
    }
    Text(
        text = catalogParts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // State at the cursor instant. The phase wording covers the whole
    // pass (ui-design §7.5): rising before the culmination span, "near
    // culmination" inside it, setting from its end — an altitude below
    // 20° shows as the number itself, the phase does not hide it.
    val phaseText = when (active.phase) {
        TimelineCursor.PassPhase.RISING -> stringResource(R.string.cursor_target_rising)
        TimelineCursor.PassPhase.NEAR_CULMINATION ->
            stringResource(R.string.cursor_phase_near_culmination)
        TimelineCursor.PassPhase.SETTING -> stringResource(R.string.cursor_target_setting)
        null -> stringResource(R.string.cursor_phase_no_window)
    }
    Text(
        text = stringResource(
            R.string.cursor_detail_state,
            active.altitudeDeg, active.azimuthDeg, phaseText,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    val phases = active.phases
    if (phases != null) {
        Text(
            text = stringResource(
                R.string.cursor_detail_window,
                formatTime(phases.window.start, zone),
                formatTime(phases.window.end, zone),
                formatTime(phases.culmination.instant, zone),
                phases.culmination.altitudeDeg,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        text = stringResource(
            R.string.cursor_detail_moonlight,
            stringResource(
                when (active.moonInterference) {
                    TimelineCursor.MoonInterference.NONE -> R.string.cursor_interference_none
                    TimelineCursor.MoonInterference.WEAK -> R.string.cursor_interference_weak
                    TimelineCursor.MoonInterference.STRONG -> R.string.cursor_interference_strong
                },
            ),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Full-frame framing advice (36.0 x 24.0 mm; domain FullFrameFraming
    // owns the formulas and the boundary rules — the card renders the
    // result form it is given, including the honest capped forms).
    val framing = active.framing
    Text(
        text = when (framing) {
            is app.astroexposure.domain.camera.FullFrameFraming.Advice.NoSizeData ->
                stringResource(R.string.cursor_framing_no_size)
            is app.astroexposure.domain.camera.FullFrameFraming.Advice.Range ->
                stringResource(
                    R.string.cursor_framing_range,
                    Math.round(framing.lowFocalMm),
                    Math.round(framing.highFocalMm),
                    Math.round(framing.referenceFocalMm),
                    Math.round(framing.referenceShare * 100),
                )
            is app.astroexposure.domain.camera.FullFrameFraming.Advice.CappedAtMaxFocal ->
                stringResource(
                    R.string.cursor_framing_capped_max,
                    Math.round(
                        app.astroexposure.domain.camera.FullFrameFraming.MAX_FOCAL_MM,
                    ).toInt(),
                    Math.round(framing.shareAtMaxFocal * 100),
                )
            is app.astroexposure.domain.camera.FullFrameFraming.Advice.CappedAtMinFocal ->
                stringResource(
                    R.string.cursor_framing_capped_min,
                    Math.round(
                        app.astroexposure.domain.camera.FullFrameFraming.MIN_FOCAL_MM,
                    ).toInt(),
                    Math.round(framing.shareAtMinFocal * 100),
                )
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun BortleSelector(selected: Int?, onChange: (Int?) -> Unit) {
    Column {
        Text(text = stringResource(R.string.bortle_manual_label), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..9).forEach { bortle ->
                FilterChip(
                    selected = selected == bortle,
                    onClick = { onChange(if (selected == bortle) null else bortle) },
                    label = { Text(bortle.toString()) },
                )
            }
        }
    }
}
