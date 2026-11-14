package cordis.samples.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun NotesScreen(viewModel: NotesViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.available) {
        Text("Notes is not available", modifier = Modifier.padding(16.dp))
        return
    }
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::onInputChange,
                label = { Text("New note") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag(TestTags.NOTES_INPUT),
            )
            Button(
                onClick = viewModel::add,
                enabled = state.canAdd,
                modifier = Modifier.testTag(TestTags.NOTES_ADD),
            ) {
                Text("Add")
            }
        }
        LazyColumn(modifier = Modifier.testTag(TestTags.NOTES_LIST), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.notes) { note -> Text(note) }
        }
    }
}
