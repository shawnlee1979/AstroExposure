package app.astroexposure.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.astroexposure.R
import app.astroexposure.domain.catalog.LightPollutionVersionFormat
import app.astroexposure.domain.geo.Coordinates
import app.astroexposure.domain.sky.BaselineSource
import app.astroexposure.domain.sky.MoonPhaseName
import app.astroexposure.domain.timeline.NightTimeline
import app.astroexposure.domain.timeline.TonightDateWindow
import app.astroexposure.support.TimeHealthCheck
import app.astroexposure.usecase.TonightOverviewUseCase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * Tonight overview (ui-design §4.1 and §7): location card (positioning +
 * light pollution in one card) → date switch → the static four-layer
 * timeline as the page backbone → moon/total-sky verdict → static
 * bright-object shortlist → ±3-day events. Conclusion first;
 * degradations stated plainly in their §7.4 positions.
 */
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
private fun timelineMarkFor(
    moment: TonightOverviewUseCase.Moment,
    overview: TonightOverviewUseCase.Result,
    zone: ZoneId,
): TimelineMark {
    fun twilightMark(titleRes: Int, solarAltitudeDeg: Double) = TimelineMark(
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
            twilightMark(R.string.moment_civil_dusk, NightTimeline.CIVIL_LIMIT_DEG)
        TonightOverviewUseCase.MomentKind.NAUTICAL_DUSK_END ->
            twilightMark(R.string.moment_nautical_dusk, NightTimeline.NAUTICAL_LIMIT_DEG)
        TonightOverviewUseCase.MomentKind.ASTRO_DUSK ->
            twilightMark(R.string.moment_astro_dusk, NightTimeline.ASTRONOMICAL_LIMIT_DEG)
        TonightOverviewUseCase.MomentKind.ASTRO_DAWN ->
            twilightMark(R.string.moment_astro_dawn, NightTimeline.ASTRONOMICAL_LIMIT_DEG)
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
