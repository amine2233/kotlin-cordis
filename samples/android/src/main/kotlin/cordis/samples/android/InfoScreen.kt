package cordis.samples.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cordis.runtime.PluginState
import kotlinx.coroutines.flow.StateFlow

@Composable
fun InfoScreen(
    id: String,
    title: String,
    plugins: StateFlow<List<PluginState>>,
) {
    val states by plugins.collectAsStateWithLifecycle()
    val description =
        states
            .firstOrNull { it.key.value == id }
            ?.metadata
            ?.description
            .orEmpty()
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag(TestTags.INFO_TITLE))
        Text(description)
    }
}
