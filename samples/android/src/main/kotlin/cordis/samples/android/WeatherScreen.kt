package cordis.samples.android

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

@Composable
fun WeatherScreen(viewModel: WeatherViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val reading = state.reading
    if (reading == null) {
        Text("Weather is not available", modifier = Modifier.padding(16.dp))
        return
    }
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            "${reading.temperatureCelsius} °C",
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier.testTag(TestTags.WEATHER_TEMPERATURE),
        )
        Text("Update #${reading.update}")
    }
}
