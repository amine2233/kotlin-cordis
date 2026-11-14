package cordis.runtime

import cordis.kernel.Context
import cordis.kernel.PluginHandle
import cordis.kernel.PluginKey
import cordis.kernel.RuntimeSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Enables and disables the plugins of a [PluginCatalog] by key, persisting the user's overrides.
 */
public interface PluginRuntime {
    /** One [PluginState] per catalog entry, in catalog order; readable from any thread. */
    public val state: StateFlow<List<PluginState>>

    /** Reads the store, loads every enabled entry and starts following the kernel snapshot; a no-op when started. */
    public suspend fun start()

    /**
     * Saves the new override, then loads or disposes the entry; a no-op when [enabled] is already the effective value.
     * Throws [PluginRuntimeException.UnknownPlugin], [PluginRuntimeException.LockedPlugin] or
     * [PluginRuntimeException.NotStarted] without changing anything.
     */
    public suspend fun setEnabled(
        key: PluginKey,
        enabled: Boolean,
    )

    /** Disposes every loaded entry in reverse catalog order and stops following the snapshot; a no-op when stopped. */
    public suspend fun stop()
}

/**
 * The [PluginRuntime] loading [catalog] into [context] and persisting overrides in [store].
 * `start`, `setEnabled` and `stop` are serialised by one mutex, so they may be called concurrently from any thread.
 */
public class PluginRuntimeDefault(
    private val context: Context,
    private val catalog: PluginCatalog,
    private val store: EnabledPluginsStore,
) : PluginRuntime {
    /** What the runtime holds: the non-default overrides and the handle of every loaded entry. */
    private data class Loaded(
        val overrides: Map<PluginKey, Boolean>,
        val handles: Map<PluginKey, PluginHandle<*>>,
    )

    private val operations = Mutex()
    private val publishing = Mutex()

    @Volatile
    private var loaded = Loaded(overrides = emptyMap(), handles = emptyMap())
    private var follower: Job? = null

    private val stateFlow = MutableStateFlow(derive(loaded, snapshot = null))
    override val state: StateFlow<List<PluginState>> = stateFlow.asStateFlow()

    override suspend fun start() {
        operations.withLock {
            if (follower != null) return
            val overrides =
                store.load().filter { (key, enabled) ->
                    val entry = catalog.entry(key) ?: return@filter false
                    enabled != entry.metadata.enabledByDefault && !(entry.metadata.locked && !enabled)
                }
            val handles =
                catalog.entries
                    .filter { isEnabled(it, overrides) }
                    .associate { it.key to it.load(context) }
            loaded = Loaded(overrides = overrides, handles = handles)
            follower = context.launch { context.snapshotFlow().collect { publish() } }
            publish()
        }
    }

    override suspend fun setEnabled(
        key: PluginKey,
        enabled: Boolean,
    ) {
        operations.withLock {
            val entry = catalog.entry(key) ?: throw PluginRuntimeException.UnknownPlugin(key)
            if (!enabled && entry.metadata.locked) throw PluginRuntimeException.LockedPlugin(key)
            if (follower == null) throw PluginRuntimeException.NotStarted
            val current = loaded
            if (isEnabled(entry, current.overrides) == enabled) return
            val overrides =
                if (enabled == entry.metadata.enabledByDefault) current.overrides - key else current.overrides + (key to enabled)
            store.save(overrides)
            if (enabled) {
                val handle = entry.load(context)
                loaded = Loaded(overrides = overrides, handles = current.handles + (key to handle))
            } else {
                loaded = Loaded(overrides = overrides, handles = current.handles - key)
                current.handles[key]?.dispose()
            }
            publish()
        }
    }

    override suspend fun stop() {
        operations.withLock {
            val running = follower ?: return
            val handles = loaded.handles
            loaded = loaded.copy(handles = emptyMap())
            for (entry in catalog.entries.asReversed()) {
                handles[entry.key]?.dispose()
            }
            running.cancelAndJoin()
            follower = null
            publish()
        }
    }

    /** Recomputes state from the latest holdings and snapshot; serialised so a stale result never lands last. */
    private suspend fun publish() {
        publishing.withLock { stateFlow.value = derive(loaded, context.snapshotFlow().value) }
    }

    private fun isEnabled(
        entry: PluginCatalog.Entry,
        overrides: Map<PluginKey, Boolean>,
    ): Boolean = entry.metadata.locked || (overrides[entry.key] ?: entry.metadata.enabledByDefault)

    private fun derive(
        loaded: Loaded,
        snapshot: RuntimeSnapshot?,
    ): List<PluginState> =
        catalog.entries.map { entry ->
            val handle = loaded.handles[entry.key]
            val info = handle?.let { snapshot?.plugins?.firstOrNull { it.id == handle.fork.id } }
            PluginState(
                key = entry.key,
                metadata = entry.metadata,
                enabled = isEnabled(entry, loaded.overrides),
                status = handle?.let { info?.status ?: it.status },
                missing = info?.missing ?: emptyList(),
            )
        }
}
