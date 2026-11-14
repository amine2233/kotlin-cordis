package cordis.samples.android

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import cordis.android.DataStoreEnabledPluginsStore
import cordis.kernel.Context
import cordis.runtime.PluginRuntime
import cordis.runtime.PluginRuntimeDefault
import cordis.samples.shared.sampleCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val android.content.Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "enabled_plugins")

class SampleApplication : Application() {
    val context: Context = Context.root()

    // The DataStore delegate needs the application context, which is only attached after construction.
    val runtime: PluginRuntime by lazy {
        PluginRuntimeDefault(context, sampleCatalog(), DataStoreEnabledPluginsStore(dataStore))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        scope.launch { runtime.start() }
    }
}
