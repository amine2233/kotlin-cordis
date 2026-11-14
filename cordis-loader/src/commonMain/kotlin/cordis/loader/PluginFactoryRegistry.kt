package cordis.loader

import cordis.kernel.Context
import cordis.kernel.Plugin
import cordis.kernel.PluginHandle
import cordis.kernel.PluginKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

public typealias LoaderAction = suspend (Context) -> PluginHandle<*>

/**
 * Maps [PluginKey]s to plugin types and their config decoders.
 */
public class PluginFactoryRegistry(
    public val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val makers = mutableMapOf<PluginKey, (JsonElement?) -> LoaderAction>()

    /**
     * Registers [plugin] under [key] (default: its `pluginKey`), decoding its config using [serializer].
     * A missing config decodes as `{}`, so a config type whose fields all have defaults loads with them.
     */
    public fun <C> register(
        plugin: Plugin<C>,
        serializer: KSerializer<C>,
        key: PluginKey? = null,
    ) {
        register(plugin, key) { config ->
            json.decodeFromJsonElement(serializer, config ?: JsonObject(emptyMap()))
        }
    }

    /**
     * Registers a plugin with custom config decoding; [decode] receives `null` when the entry has no config.
     */
    public fun <C> register(
        plugin: Plugin<C>,
        key: PluginKey? = null,
        decode: (JsonElement?) -> C,
    ) {
        val resolvedKey =
            requireNotNull(key ?: plugin.pluginKey) {
                "Plugin '${plugin.name}' has no key: pass `key` or declare `pluginKey`"
            }
        makers[resolvedKey] = { rawConfig ->
            val config = decode(rawConfig)
            val action: LoaderAction = { ctx: Context -> ctx.plugin(plugin, config) }
            action
        }
    }

    /**
     * Registers a plugin without configuration ([Unit]).
     */
    public fun register(
        plugin: Plugin<Unit>,
        key: PluginKey? = null,
    ) {
        register(plugin, key) { }
    }

    public fun isRegistered(key: PluginKey): Boolean = makers.containsKey(key)

    public fun loader(
        key: PluginKey,
        config: JsonElement?,
    ): LoaderAction? {
        val maker = makers[key] ?: return null
        return maker(config)
    }
}
