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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.astroexposure.R
import app.astroexposure.usecase.TonightOverviewUseCase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun formatTime(instant: Instant?, zone: ZoneId): String =
    instant?.let { TIME_FORMAT.format(it.atZone(zone)) } ?: "—"

@Composable
fun TonightScreen(
    overview: TonightOverviewUseCase.Result,
    date: LocalDate,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.sample_location_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.tonight_date, date.toString()),
            style = MaterialTheme.typography.titleMedium,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val night = overview.night
                if (night == null) {
                    Text(text = stringResource(R.string.tonight_no_night))
                } else {
                    InfoRow(
                        stringResource(R.string.tonight_dusk),
                        formatTime(night.dusk, zone),
                    )
                    InfoRow(
                        stringResource(R.string.tonight_dawn),
                        formatTime(night.dawn, zone),
                    )
                }
                InfoRow(
                    stringResource(R.string.moon_illumination),
                    "${Math.round(overview.moonIllumination * 100)}%",
                )
                InfoRow(
                    stringResource(R.string.moonrise),
                    overview.moonrise?.let { formatTime(it, zone) }
                        ?: stringResource(R.string.moon_event_none),
                )
                InfoRow(
                    stringResource(R.string.moonset),
                    overview.moonset?.let { formatTime(it, zone) }
                        ?: stringResource(R.string.moon_event_none),
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.gc_title),
                    style = MaterialTheme.typography.titleSmall,
                )
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
                    val windowText = when {
                        gc.risesAbove != null && gc.setsBelow != null -> stringResource(
                            R.string.gc_window,
                            formatTime(gc.risesAbove, zone),
                            formatTime(gc.setsBelow, zone),
                        )
                        gc.risesAbove == null && gc.setsBelow != null ->
                            stringResource(R.string.gc_edge_open) + " · " + formatTime(gc.setsBelow, zone)
                        gc.risesAbove != null && gc.setsBelow == null ->
                            formatTime(gc.risesAbove, zone) + " · " + stringResource(R.string.gc_edge_close)
                        else -> stringResource(R.string.gc_edge_open) + " · " + stringResource(R.string.gc_edge_close)
                    }
                    Text(text = windowText)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value)
    }
}
