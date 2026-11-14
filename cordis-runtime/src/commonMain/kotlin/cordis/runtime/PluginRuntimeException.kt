package cordis.runtime

import cordis.kernel.PluginKey

/**
 * Every refusal of a [PluginRuntime] call; a refused call changes neither state nor store.
 */
public sealed class PluginRuntimeException(
    override val message: String,
) : Exception(message) {
    /** No catalog entry has this key. */
    public data class UnknownPlugin(
        val key: PluginKey,
    ) : PluginRuntimeException("No catalog entry has key '${key.value}'")

    /** The entry is locked, so it cannot be disabled. */
    public data class LockedPlugin(
        val key: PluginKey,
    ) : PluginRuntimeException("Catalog entry '${key.value}' is locked and cannot be disabled")

    /** The runtime is not started. */
    public data object NotStarted : PluginRuntimeException("The plugin runtime is not started")
}
