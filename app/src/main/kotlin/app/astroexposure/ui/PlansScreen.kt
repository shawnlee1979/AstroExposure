package app.astroexposure.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.astroexposure.R
import app.astroexposure.persistence.SavedPlan

/**
 * Plans tab (design §7.3): saved plans newest first, deletion, and the two
 * distinct exports — plain text for humans, JSON backup for machines —
 * plus JSON import. File picking happens through the activity-level intent
 * launchers wired in MainActivity; this screen only emits intents.
 */
@Composable
fun PlansScreen(
    plans: List<SavedPlan>,
    importMessage: String?,
    onDelete: (SavedPlan) -> Unit,
    onExportText: () -> Unit,
    onExportJson: () -> Unit,
    onImportJson: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle(stringResource(R.string.plans_title))
        if (importMessage != null) {
            Text(text = importMessage, color = MaterialTheme.colorScheme.primary)
        }
        OutlinedButton(onClick = onExportText, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.plans_export_text))
        }
        OutlinedButton(onClick = onExportJson, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.plans_export_json))
        }
        OutlinedButton(onClick = onImportJson, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.plans_import_json))
        }
        if (plans.isEmpty()) {
            Text(text = stringResource(R.string.plans_empty))
        } else {
            plans.forEach { plan ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "${plan.date} · ${plan.title}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(text = plan.bodyText, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { onDelete(plan) }) {
                            Text(stringResource(R.string.plans_delete))
                        }
                    }
                }
            }
        }
    }
}
