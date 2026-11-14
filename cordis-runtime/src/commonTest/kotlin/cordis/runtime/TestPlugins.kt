package cordis.runtime

import cordis.kernel.Context
import cordis.kernel.Fork
import cordis.kernel.Inject
import cordis.kernel.Plugin
import cordis.kernel.PluginKey
import cordis.kernel.ServiceKey

object ProvidedKey : ServiceKey<Int>

object ProviderPlugin : Plugin<Int> {
    override suspend fun apply(
        ctx: Context,
        config: Int,
    ) {
        ctx.provide(ProvidedKey, config)
    }
}

object DependentPlugin : Plugin<Unit> {
    override val inject = Inject.of(required = listOf(ProvidedKey))

    override suspend fun apply(
        ctx: Context,
        config: Unit,
    ) {}
}

class FeaturePlugin(
    override val name: String,
) : Plugin<Unit> {
    override suspend fun apply(
        ctx: Context,
        config: Unit,
    ) {}
}

val Provider = PluginKey("provider")
val Dependent = PluginKey("dependent")
val Core = PluginKey("core")
val Feature = PluginKey("feature")
val Extra = PluginKey("extra")

val CorePlugin = FeaturePlugin("Core")
val FeatureEntryPlugin = FeaturePlugin("Feature")
val ExtraPlugin = FeaturePlugin("Extra")

/** Core is locked, Feature is enabled by default, Extra is disabled by default. */
val testCatalog =
    pluginCatalog {
        plugin(Core, CorePlugin, PluginMetadata("Core", "Always on", locked = true))
        plugin(Feature, FeatureEntryPlugin, PluginMetadata("Feature", "On by default"))
        plugin(Extra, ExtraPlugin, PluginMetadata("Extra", "Off by default", enabledByDefault = false))
    }

val dependencyCatalog =
    pluginCatalog {
        plugin(Provider, ProviderPlugin, 7, PluginMetadata("Provider", "Provides a service"))
        plugin(Dependent, DependentPlugin, PluginMetadata("Dependent", "Requires the provider"))
    }

/** Records every `save` on top of an [InMemoryEnabledPluginsStore]; throws on `save` when [failSaves] is set. */
class EnabledPluginsStoreMock(
    initial: Map<PluginKey, Boolean> = emptyMap(),
    private val failSaves: Boolean = false,
) : EnabledPluginsStore {
    private val backing = InMemoryEnabledPluginsStore(initial)
    var saveCount = 0
        private set

    override suspend fun load(): Map<PluginKey, Boolean> = backing.load()

    override suspend fun save(overrides: Map<PluginKey, Boolean>) {
        saveCount += 1
        if (failSaves) throw StoreFailure()
        backing.save(overrides)
    }
}

class StoreFailure : Exception("Save failed")

fun List<PluginState>.of(key: PluginKey): PluginState = first { it.key == key }

fun Context.liveForks(name: String): Int = snapshot().plugins.count { it.name == name && it.status != Fork.Status.Disposed }
