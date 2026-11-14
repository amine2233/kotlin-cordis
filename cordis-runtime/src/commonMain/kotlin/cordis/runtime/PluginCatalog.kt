package cordis.runtime

import cordis.kernel.Context
import cordis.kernel.Plugin
import cordis.kernel.PluginKey
import cordis.loader.LoaderAction

/**
 * An ordered, immutable list of plugins the runtime can enable and disable by [PluginKey].
 */
public class PluginCatalog internal constructor(
    /** The entries, in declaration order. */
    public val entries: List<Entry>,
) {
    /**
     * One plugin of the catalog: its runtime [key], its [metadata] and how to load it with its default config.
     */
    public class Entry internal constructor(
        /** The identity the runtime uses for this entry; it need not equal the plugin's own `pluginKey`. */
        public val key: PluginKey,
        /** User-facing description, lock and default of this entry. */
        public val metadata: PluginMetadata,
        internal val load: LoaderAction,
    )

    internal fun entry(key: PluginKey): Entry? = entries.firstOrNull { it.key == key }
}

/**
 * Collects the entries of a [pluginCatalog].
 */
public class PluginCatalogBuilder internal constructor() {
    private val entries = mutableListOf<PluginCatalog.Entry>()

    /**
     * Adds [plugin] under [key], loaded with [config].
     * Throws [IllegalArgumentException] on a duplicate key, or on a locked entry that is not enabled by default.
     */
    public fun <C> plugin(
        key: PluginKey,
        plugin: Plugin<C>,
        config: C,
        metadata: PluginMetadata,
    ) {
        require(entries.none { it.key == key }) { "Duplicate catalog key '${key.value}'" }
        require(!metadata.locked || metadata.enabledByDefault) {
            "Catalog entry '${key.value}' is locked, so it must be enabled by default"
        }
        val load: LoaderAction = { ctx: Context -> ctx.plugin(plugin, config) }
        entries.add(PluginCatalog.Entry(key = key, metadata = metadata, load = load))
    }

    /**
     * Adds a plugin without configuration ([Unit]) under [key].
     */
    public fun plugin(
        key: PluginKey,
        plugin: Plugin<Unit>,
        metadata: PluginMetadata,
    ) {
        plugin(key, plugin, Unit, metadata)
    }

    internal fun build(): PluginCatalog = PluginCatalog(entries.toList())
}

/**
 * Type-safe [PluginCatalog] builder.
 *
 * Example:
 * ```kotlin
 * val catalog = pluginCatalog {
 *     plugin(PluginKey("db"), DatabasePlugin, DatabaseConfig(url), PluginMetadata("Database", "Storage", locked = true))
 *     plugin(PluginKey("search"), SearchPlugin, PluginMetadata("Search", "Full-text search"))
 * }
 * ```
 */
public fun pluginCatalog(block: PluginCatalogBuilder.() -> Unit): PluginCatalog = PluginCatalogBuilder().apply(block).build()
