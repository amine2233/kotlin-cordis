package cordis.kernel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Everything is a plugin: features, services, adapters, even the app shell.
 *
 * Example:
 * ```kotlin
 * object Greeter : Plugin<String> {
 *     override val inject = Inject.of(required = listOf(LoggerKey))
 *     override suspend fun apply(ctx: Context, config: String) {
 *         ctx.require(LoggerKey).info("Hello $config")
 *     }
 * }
 * ```
 *
 * `apply` must only register side effects through `ctx` (provide, on, effect, plugin…).
 * Anything registered that way is automatically undone when the plugin is unloaded
 * or restarted — you never write a matching "unregister" by hand.
 */
interface Plugin<C> {
    val name: String get() = this::class.simpleName ?: "Plugin"
    val inject: Inject get() = Inject.NONE

    suspend fun apply(
        ctx: Context,
        config: C,
    )

    /**
     * Checks the shape of a config before `update` tears anything down; throw to reject it.
     */
    fun validate(config: C) {}
}

/**
 * Result of [PluginHandle.update]: whether the new config was accepted for application.
 */
sealed class UpdateOutcome {
    /** The config passed validation and was applied; read `status` for the apply result. */
    data object Applied : UpdateOutcome()

    /** Validation threw; the running instance was left untouched. */
    data class Rejected(
        val error: Throwable,
    ) : UpdateOutcome()

    /** The plugin is active and the config equals the last applied one, so nothing was restarted. */
    data object Unchanged : UpdateOutcome()
}

/**
 * One application of a plugin in a given context (Cordis calls this a "fork").
 * It owns a reactive lifecycle: pending → loading → active, and back,
 * driven by the availability of its injected services.
 */
class Fork(
    val name: String,
    val inject: Inject,
    val parent: Context,
    private var config: Any? = null,
    private var body: suspend (Context) -> Unit,
) {
    sealed class Status {
        data object Pending : Status()

        data object Loading : Status()

        data object Active : Status()

        data class Failed(
            val reason: String,
        ) : Status()

        data object Disposed : Status()
    }

    val id: PluginId = PluginId(this)
    private val statusState = MutableStateFlow<Status>(Status.Pending)
    val statusFlow: StateFlow<Status> = statusState.asStateFlow()
    val status: Status get() = statusState.value

    private val registry: Registry = parent.registry
    private var appliedConfig: Any? = null

    private data class Runtime(
        val context: Context,
        val snapshot: Map<Slot, OccurrenceId?>,
    )

    private var runtime: Runtime? = null
    private var isReconciling = false
    private var needsReconcile = false
    private var forceRestart = false

    // MARK: Reactive core

    /** Does this fork react to changes on [slot]? */
    fun dependsOn(slot: Slot): Boolean = inject.all.any { parent.slot(it) == slot }

    /**
     * Current occurrences of every injected slot, or null if a required one is missing.
     * Slots provided by this very fork are ignored to avoid self-restart loops.
     */
    private fun resolve(): Map<Slot, OccurrenceId?>? {
        val snapshot = mutableMapOf<Slot, OccurrenceId?>()
        for (id in inject.required) {
            val slot = parent.slot(id)
            val entry = registry.entry(slot) ?: return null
            if (entry.fork == this.id) continue
            snapshot[slot] = entry.occurrence
        }
        for (id in inject.optional) {
            val slot = parent.slot(id)
            val entry = registry.entry(slot)
            if (entry?.fork == this.id) continue
            snapshot[slot] = entry?.occurrence
        }
        return snapshot
    }

    /** Serialized, re-entrancy-safe reconciliation. */
    suspend fun refresh() {
        if (isReconciling) {
            needsReconcile = true
            return
        }
        isReconciling = true
        do {
            needsReconcile = false
            reconcile()
        } while (needsReconcile)
        isReconciling = false
    }

    private suspend fun reconcile() {
        if (status == Status.Disposed) return

        val wanted = resolve()
        val current = runtime
        when {
            current == null && wanted == null -> {
                setStatus(Status.Pending)
            }

            current == null && wanted != null -> {
                start(wanted)
            }

            current != null && wanted == null -> {
                stop()
                setStatus(Status.Pending)
            }

            current != null && wanted != null -> {
                if (forceRestart || current.snapshot != wanted) {
                    stop()
                    start(wanted)
                }
            }
        }
        forceRestart = false
    }

    private suspend fun start(snapshot: Map<Slot, OccurrenceId?>) {
        val scope =
            Scope(
                failureHandler = { error ->
                    registry.emit(TeardownFailed(plugin = name, error = error))
                },
                coroutineScope = registry.coroutineScope,
            )
        val ctx =
            Context(
                registry = registry,
                parent = parent,
                fork = this,
                scope = scope,
                realms = parent.realms,
                layers = parent.layers,
                zoneId = parent.zoneId,
            )
        runtime = Runtime(context = ctx, snapshot = snapshot)
        setStatus(Status.Loading)
        try {
            body(ctx)
            if (status == Status.Loading) {
                appliedConfig = config
                setStatus(Status.Active)
            }
        } catch (t: Throwable) {
            stop()
            setStatus(Status.Failed(t.message ?: t.toString()))
            parent.emit(PluginFailed(plugin = name, error = t))
        }
    }

    private suspend fun stop() {
        val current = runtime ?: return
        runtime = null
        current.context.scope.dispose()
    }

    private suspend fun setStatus(newStatus: Status) {
        if (newStatus == status) return
        statusState.value = newStatus
        parent.emit(PluginStatusChanged(plugin = name, status = newStatus))
    }

    // MARK: Public controls

    /**
     * Replaces the plugin body (new config) and restarts if running,
     * unless the config equals the last applied one.
     */
    suspend fun reconfigure(
        config: Any?,
        body: suspend (Context) -> Unit,
    ): Boolean {
        if (status == Status.Active && appliedConfig != null && appliedConfig == config) {
            return false
        }
        this.config = config
        this.body = body
        forceRestart = true
        refresh()
        return true
    }

    suspend fun dispose() {
        if (status == Status.Disposed) return
        stop()
        setStatus(Status.Disposed)
        registry.unregister(this)
    }
}

/**
 * Typed handle returned by `ctx.plugin(...)`.
 */
class PluginHandle<C>(
    val fork: Fork,
    private val plugin: Plugin<C>,
) {
    val status: Fork.Status
        get() = fork.status

    /**
     * Suspends until the plugin leaves Pending / Loading; returns Active, Failed or Disposed.
     * Must not be called from the plugin's own `apply`, which would wait on itself.
     */
    suspend fun awaitActive(): Fork.Status = fork.statusFlow.first { it !is Fork.Status.Pending && it !is Fork.Status.Loading }

    /**
     * Hot-reconfigure: validates the config, then tears the plugin down and re-applies it.
     * A no-op (`Unchanged`) when the plugin is active and config equals the last applied one.
     */
    suspend fun update(config: C): UpdateOutcome {
        try {
            plugin.validate(config)
        } catch (t: Throwable) {
            return UpdateOutcome.Rejected(t)
        }
        val restarted =
            fork.reconfigure(config) { ctx ->
                plugin.apply(ctx, config)
            }
        return if (restarted) UpdateOutcome.Applied else UpdateOutcome.Unchanged
    }

    /**
     * Hot-unload: every side effect of this plugin (and its sub-plugins) is rolled back.
     */
    suspend fun dispose() {
        fork.dispose()
    }
}
