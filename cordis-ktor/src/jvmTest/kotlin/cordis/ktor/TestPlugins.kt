package cordis.ktor

import cordis.kernel.Context
import cordis.kernel.Plugin
import cordis.kernel.PluginKey
import cordis.kernel.ServiceKey
import cordis.runtime.InMemoryEnabledPluginsStore
import cordis.runtime.PluginMetadata
import cordis.runtime.PluginRuntime
import cordis.runtime.PluginRuntimeDefault
import cordis.runtime.PluginState
import cordis.runtime.pluginCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ProvidedKey : ServiceKey<Int>

object ProviderPlugin : Plugin<Int> {
    override suspend fun apply(
        ctx: Context,
        config: Int,
    ) {
        ctx.provide(ProvidedKey, config)
    }
}

class FeaturePlugin(
    override val name: String,
) : Plugin<Unit> {
    override suspend fun apply(
        ctx: Context,
        config: Unit,
    ) {}
}

val Core = PluginKey("core")
val Feature = PluginKey("feature")
val Provider = PluginKey("provider")

const val PROVIDED_VALUE = 42

/** Core is locked, Feature is enabled by default, Provider is disabled by default and provides [ProvidedKey]. */
val testCatalog =
    pluginCatalog {
        plugin(Core, FeaturePlugin("Core"), PluginMetadata("Core", "Always on", locked = true))
        plugin(Feature, FeaturePlugin("Feature"), PluginMetadata("Feature", "On by default"))
        plugin(
            Provider,
            ProviderPlugin,
            PROVIDED_VALUE,
            PluginMetadata("Provider", "Off by default", enabledByDefault = false),
        )
    }

fun testRuntime(context: Context): PluginRuntime = PluginRuntimeDefault(context, testCatalog, InMemoryEnabledPluginsStore())

/** Records `start` and `stop`, and whether the context was already disposed when `stop` was called. */
class PluginRuntimeMock(
    private val context: Context,
) : PluginRuntime {
    override val state: StateFlow<List<PluginState>> = MutableStateFlow(emptyList())
    var startCount = 0
        private set
    var stopCount = 0
        private set
    var contextDisposedAtStop: Boolean? = null
        private set

    override suspend fun start() {
        startCount += 1
    }

    override suspend fun setEnabled(
        key: PluginKey,
        enabled: Boolean,
    ) {}

    override suspend fun stop() {
        stopCount += 1
        contextDisposedAtStop = context.scope.isDisposed
    }
}

fun List<PluginState>.of(key: PluginKey): PluginState = first { it.key == key }
