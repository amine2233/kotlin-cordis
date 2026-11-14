package cordis.samples.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DebugScreen(viewModel: DebugViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (state.healthy) "Healthy" else "Not healthy",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag(TestTags.DEBUG_HEALTH),
        )
        HealthGroup(title = "Failed", lines = state.failed)
        HealthGroup(title = "Missing services", lines = state.missingServices)
        HealthGroup(title = "Pending", lines = state.pending)
        if (state.tree.isEmpty()) {
            Text("No plugins")
        } else {
            Text(
                state.tree,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag(TestTags.DEBUG_TREE),
            )
        }
    }
}

@Composable
private fun HealthGroup(
    title: String,
    lines: List<String>,
) {
    if (lines.isEmpty()) return
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        lines.forEach { Text(it) }
    }
}
