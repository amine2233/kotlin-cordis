package cordis.samples.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cordis.samples.shared.NavEntries
import cordis.samples.shared.NotesKey
import cordis.samples.shared.WeatherKey

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as SampleApplication
        val factory =
            viewModelFactory {
                initializer { DrawerViewModel(app.context.contributions(NavEntries)) }
                initializer { SettingsViewModel(app.runtime) }
                initializer { NotesViewModel(app.context.serviceFlow(NotesKey)) }
                initializer { WeatherViewModel(app.context.serviceFlow(WeatherKey)) }
            }
        setContent {
            MaterialTheme {
                SampleApp(factory = factory, plugins = app.runtime.state)
            }
        }
    }
}
