package cordis.samples.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LazyColumn {
        items(state.rows, key = { it.key.value }) { row ->
            ListItem(
                headlineContent = { Text(row.title) },
                supportingContent = {
                    Column {
                        Text(row.description)
                        Text(row.status.label, style = MaterialTheme.typography.labelMedium)
                    }
                },
                trailingContent = {
                    Switch(
                        checked = row.enabled,
                        onCheckedChange = { viewModel.dispatch(SettingsAction.Toggle(row.key)) },
                        enabled = !row.locked,
                        modifier = Modifier.testTag(TestTags.settingsSwitch(row.key.value)),
                    )
                },
                modifier = Modifier.testTag(TestTags.settingsRow(row.key.value)),
            )
        }
    }
}
