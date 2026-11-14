package cordis.runtime

/**
 * Describes a catalog entry to users: what it is, whether it can be turned off and its default.
 *
 * @property title Short human-readable name.
 * @property description What the plugin does.
 * @property locked A locked entry is always enabled and cannot be disabled.
 * @property enabledByDefault Whether the entry is enabled when the store holds no override for it.
 */
public data class PluginMetadata(
    val title: String,
    val description: String,
    val locked: Boolean = false,
    val enabledByDefault: Boolean = true,
)
