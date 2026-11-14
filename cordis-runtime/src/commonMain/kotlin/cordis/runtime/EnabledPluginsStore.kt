package cordis.runtime

import cordis.kernel.PluginKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persists the user's enable/disable overrides; it only ever holds values that differ from the catalog defaults.
 */
public interface EnabledPluginsStore {
    /** Returns the stored overrides. */
    public suspend fun load(): Map<PluginKey, Boolean>

    /** Replaces the stored overrides with [overrides]. */
    public suspend fun save(overrides: Map<PluginKey, Boolean>)
}

/**
 * An [EnabledPluginsStore] kept in memory, starting from [initial]; safe to call from any thread.
 */
public class InMemoryEnabledPluginsStore(
    initial: Map<PluginKey, Boolean> = emptyMap(),
) : EnabledPluginsStore {
    private val mutex = Mutex()
    private var stored: Map<PluginKey, Boolean> = initial.toMap()

    override suspend fun load(): Map<PluginKey, Boolean> = mutex.withLock { stored.toMap() }

    override suspend fun save(overrides: Map<PluginKey, Boolean>) {
        mutex.withLock { stored = overrides.toMap() }
    }
}
