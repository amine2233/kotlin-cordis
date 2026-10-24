package cordis.loader

import cordis.kernel.Context
import cordis.kernel.Fork
import cordis.kernel.Plugin
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

typealias LoaderAction = suspend (Context) -> Fork.Status

/**
 * Maps string resolve keys to plugin types; the only place strings name plugins.
 */
class PluginRegistry(
    val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val makers = mutableMapOf<String, (String?) -> LoaderAction>()

    /**
     * Registers [plugin] under [key], decoding its config using [serializer].
     */
    fun <C> register(
        plugin: Plugin<C>,
        key: String,
        serializer: KSerializer<C>,
    ) {
        register(plugin, key) { rawConfig ->
            if (rawConfig == null) {
                throw IllegalArgumentException("config is required")
            }
            json.decodeFromString(serializer, rawConfig)
        }
    }

    /**
     * Registers a plugin with custom config decoding.
     */
    fun <C> register(
        plugin: Plugin<C>,
        key: String,
        decode: (String?) -> C,
    ) {
        makers[key] = { rawConfig ->
            val config = decode(rawConfig)
            val action: LoaderAction = { ctx: Context -> ctx.plugin(plugin, config).status }
            action
        }
    }

    /**
     * Registers a plugin without configuration ([Unit]).
     */
    fun register(
        plugin: Plugin<Unit>,
        key: String,
    ) {
        register(plugin, key) { Unit }
    }

    fun isRegistered(key: String): Boolean = makers.containsKey(key)

    fun loader(
        key: String,
        config: String?,
    ): LoaderAction? {
        val maker = makers[key] ?: return null
        return maker(config)
    }
}
