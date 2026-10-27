package app.astroexposure.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.astroexposure.R
import app.astroexposure.domain.sky.BaselineSource
import app.astroexposure.support.TimeHealthCheck
import app.astroexposure.usecase.TonightOverviewUseCase
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tonight overview (ui-design §4.1): status line (site + date + time-health
 * dot + red-light switch), twilight card, moon/total-sky verdict card,
 * low-moonlight windows, ±3-day events, the static bright-object shortlist
 * and the key-moments list. Conclusion first; degradations stated plainly.
 */
@Composable
fun TonightScreen(
    overview: TonightOverviewUseCase.Result,
    date: LocalDate,
    zone: ZoneId,
    locationLabel: String,
    locationSourceText: String,
    timeHealth: TimeHealthCheck.Result,
    redLight: Boolean,
    onRedLightChange: (Boolean) -> Unit,
    onUseGps: () -> Unit,
    manualBortle: Int?,
    onManualBortleChange: (Int?) -> Unit,
    onManualLocation: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var manualLat by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var manualLon by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Status line.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = locationLabel, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = locationSourceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.tonight_date, date.toString()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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
        OutlinedButton(onClick = onUseGps) { Text(stringResource(R.string.location_use_gps)) }

        // Manual coordinates channel (design §2.1): the degradation path
        // when there is no GPS permission or no fix.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.location_manual_title),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = manualLat,
                    onValueChange = { manualLat = it },
                    label = { Text(stringResource(R.string.location_latitude)) },
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = manualLon,
                    onValueChange = { manualLon = it },
                    label = { Text(stringResource(R.string.location_longitude)) },
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedButton(
                onClick = {
                    val lat = manualLat.toDoubleOrNull()
                    val lon = manualLon.toDoubleOrNull()
                    if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                        onManualLocation(lat, lon)
                    }
                },
            ) { Text(stringResource(R.string.location_apply)) }
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

        // Twilight card.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val sun = overview.sunEvents
                InfoRow(stringResource(R.string.tonight_sunset), formatTime(sun.sunset, zone))
                InfoRow(stringResource(R.string.tonight_civil_dusk), formatTime(sun.civilDuskEnd, zone))
                InfoRow(stringResource(R.string.tonight_nautical_dusk), formatTime(sun.nauticalDuskEnd, zone))
                val night = overview.night
                if (night == null) {
                    Text(text = stringResource(R.string.tonight_no_night))
                } else {
                    InfoRow(stringResource(R.string.tonight_dusk), formatTime(night.dusk, zone))
                    InfoRow(stringResource(R.string.tonight_dawn), formatTime(night.dawn, zone))
                }
                InfoRow(
                    stringResource(R.string.moon_illumination),
                    "${Math.round(overview.moonIllumination * 100)}%",
                )
                InfoRow(stringResource(R.string.moonrise), formatTime(overview.moonrise, zone))
                InfoRow(stringResource(R.string.moonset), formatTime(overview.moonset, zone))
            }
        }

        // Moon / total-sky verdict card.
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
                    if (sky.totalSky.baselineSource == BaselineSource.NONE) {
                        BortleSelector(manualBortle, onManualBortleChange)
                    }
                }
            }
        }

        // Galactic centre.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SectionTitle(stringResource(R.string.gc_title))
                val gc = overview.galacticCenter
                if (gc == null) {
                    Text(text = stringResource(R.string.gc_not_visible))
                } else {
                    Text(
                        text = stringResource(
                            R.string.gc_culmination,
                            formatTime(gc.culmination, zone),
                            gc.culminationAltitude.value,
                        ),
                    )
                    Text(
                        text = stringResource(
                            R.string.gc_window,
                            gc.risesAbove?.let { formatTime(it, zone) }
                                ?: stringResource(R.string.gc_edge_open),
                            gc.setsBelow?.let { formatTime(it, zone) }
                                ?: stringResource(R.string.gc_edge_close),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Low-moonlight windows.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SectionTitle(stringResource(R.string.tonight_low_moonlight_title))
                if (overview.lowMoonlightWindows.isEmpty()) {
                    Text(text = stringResource(R.string.tonight_low_moonlight_none))
                } else {
                    overview.lowMoonlightWindows.forEach { window ->
                        Text(
                            text = stringResource(
                                R.string.tonight_low_moonlight_item,
                                formatTime(window.start, zone),
                                formatTime(window.end, zone),
                            ),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        // Events within ±3 days.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SectionTitle(stringResource(R.string.tonight_events_title))
                if (overview.events.isEmpty()) {
                    Text(text = stringResource(R.string.tonight_events_none))
                } else {
                    overview.events.forEach { event ->
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

        // Static bright-object shortlist.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SectionTitle(stringResource(R.string.tonight_recommendations_title))
                if (overview.recommendations.isEmpty()) {
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

        // Key moments timeline (list form, design §7.2 MVP).
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SectionTitle(stringResource(R.string.tonight_moments_title))
                overview.moments.forEach { moment ->
                    InfoRow(
                        stringResource(
                            when (moment.kind) {
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
                        ),
                        formatTime(moment.instant, zone),
                    )
                }
            }
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
