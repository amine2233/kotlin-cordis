package cordis.kernel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

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
public interface Plugin<C> {
    public val name: String get() = this::class.simpleName ?: "Plugin"
    public val inject: Inject get() = Inject.NONE

    public suspend fun apply(
        ctx: Context,
        config: C,
    )

    /**
     * Checks the shape of a config before its first `apply` (in `Context.plugin`) and before
     * `update` tears anything down; throw to reject it.
     * Throwing on load marks the plugin `Failed` and emits `PluginFailed` without calling `apply`.
     */
    public fun validate(config: C) {}
}

/**
 * Result of [PluginHandle.update]: whether the new config was accepted for application.
 */
public sealed class UpdateOutcome {
    /** The config passed validation and was applied; read `status` for the apply result. */
    public data object Applied : UpdateOutcome()

    /** Validation threw; the running instance was left untouched. */
    public data class Rejected(
        val error: Throwable,
    ) : UpdateOutcome()

    /** The plugin is active and the config equals the last applied one, so nothing was restarted. */
    public data object Unchanged : UpdateOutcome()
}

/**
 * One application of a plugin in a given context (Cordis calls this a "fork").
 * It owns a reactive lifecycle: pending → loading → active, and back,
 * driven by the availability of its injected services.
 */
public class Fork internal constructor(
    public val name: String,
    public val inject: Inject,
    public val parent: Context,
    private var config: Any? = null,
    private var body: suspend (Context) -> Unit,
) {
    public sealed class Status {
        public data object Pending : Status()

        public data object Loading : Status()

        public data object Active : Status()

        public data class Failed(
            val reason: String,
        ) : Status()

        public data object Disposed : Status()
    }

    public val id: PluginId = PluginId(this)
    private val statusState = MutableStateFlow<Status>(Status.Pending)
    public val statusFlow: StateFlow<Status> = statusState.asStateFlow()
    public val status: Status get() = statusState.value

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
    private var isDisposing = false
    private var isRejected = false

    /** The job running the in-flight `apply`, and one that completes once that run is settled. */
    private var applyJob: Job? = null
    private var runSettled: Job? = null

    /** This fork's teardown entry in the parent scope. */
    internal var parentEntry: Disposable? = null

    // MARK: Reactive core

    /** Does this fork react to changes on [slot]? */
    public fun dependsOn(slot: Slot): Boolean = inject.all.any { parent.slot(it) == slot }

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
    internal suspend fun refresh() {
        if (isReconciling) {
            needsReconcile = true
            return
        }
        isReconciling = true
        try {
            do {
                needsReconcile = false
                reconcile()
            } while (needsReconcile)
        } finally {
            isReconciling = false
        }
    }

    private suspend fun reconcile() {
        if (isDisposing || isRejected) return

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
                coroutineScope = registry.lifecycleScope,
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
        val settled = Job()
        runSettled = settled
        val marker = ApplyMarker(fork = this, outer = currentCoroutineContext()[ApplyMarker])
        try {
            coroutineScope {
                applyJob = coroutineContext.job
                withContext(marker) { body(ctx) }
            }
            applyJob = null
            if (status == Status.Loading && !isDisposing) {
                appliedConfig = config
                setStatus(Status.Active)
            }
        } catch (e: CancellationException) {
            applyJob = null
            withContext(NonCancellable) {
                stop()
                if (!isDisposing) setStatus(Status.Pending)
            }
            if (!isDisposing) throw e
        } catch (t: Throwable) {
            applyJob = null
            withContext(NonCancellable) {
                stop()
                if (!isDisposing) {
                    setStatus(Status.Failed(t.message ?: t.toString()))
                    parent.emit(PluginFailed(plugin = name, error = t))
                }
            }
        } finally {
            settled.complete()
        }
    }

    private suspend fun stop() {
        val current = runtime ?: return
        runtime = null
        current.context.scope.dispose()
    }

    private suspend fun setStatus(newStatus: Status) {
        if (newStatus == status || status == Status.Disposed) return
        statusState.value = newStatus
        parent.emit(PluginStatusChanged(plugin = name, status = newStatus))
    }

    // MARK: Public controls

    /**
     * Replaces the plugin body (new config) and restarts if running,
     * unless the config equals the last applied one.
     */
    internal suspend fun reconfigure(
        config: Any?,
        body: suspend (Context) -> Unit,
    ): Boolean {
        if (status == Status.Active && appliedConfig != null && appliedConfig == config) {
            return false
        }
        this.config = config
        this.body = body
        isRejected = false
        forceRestart = true
        refresh()
        return true
    }

    /**
     * Marks the fork `Failed` because its config was rejected by validation; it stays failed until `reconfigure`.
     */
    internal suspend fun reject(error: Throwable) {
        isRejected = true
        setStatus(Status.Failed(error.message ?: error.toString()))
        parent.emit(PluginFailed(plugin = name, error = error))
    }

    /**
     * Cancels an in-flight `apply` and waits for its rollback, unless called from inside that `apply`.
     */
    internal suspend fun dispose() {
        if (isDisposing) return
        isDisposing = true
        val running = applyJob
        if (running != null && !ApplyMarker.isInside(this, currentCoroutineContext())) {
            running.cancel()
            runSettled?.join()
        }
        stop()
        setStatus(Status.Disposed)
        registry.unregister(this)
        parentEntry?.invoke()
    }
}

/**
 * Marks the coroutine running a fork's `apply`, chained to the enclosing fork's marker.
 */
private class ApplyMarker(
    val fork: Fork,
    val outer: ApplyMarker?,
) : AbstractCoroutineContextElement(ApplyMarker) {
    companion object Key : CoroutineContext.Key<ApplyMarker> {
        fun isInside(
            fork: Fork,
            context: CoroutineContext,
        ): Boolean = generateSequence(context[ApplyMarker]) { it.outer }.any { it.fork === fork }
    }
}

/**
 * Typed handle returned by `ctx.plugin(...)`.
 */
public class PluginHandle<C>(
    public val fork: Fork,
    private val plugin: Plugin<C>,
) {
    public val status: Fork.Status
        get() = fork.status

    /**
     * Suspends until the plugin leaves Pending / Loading; returns Active, Failed or Disposed.
     * Must not be called from the plugin's own `apply`, which would wait on itself.
     */
    public suspend fun awaitActive(): Fork.Status = fork.statusFlow.first { it !is Fork.Status.Pending && it !is Fork.Status.Loading }

    /**
     * Hot-reconfigure: validates the config, then tears the plugin down and re-applies it.
     * A no-op (`Unchanged`) when the plugin is active and config equals the last applied one.
     */
    public suspend fun update(config: C): UpdateOutcome {
        try {
            plugin.validate(config)
        } catch (t: Throwable) {
            return UpdateOutcome.Rejected(t)
        }
        val restarted =
            fork.parent.registry.lifecycle {
                fork.reconfigure(config) { ctx ->
                    plugin.apply(ctx, config)
                }
            }
        return if (restarted) UpdateOutcome.Applied else UpdateOutcome.Unchanged
    }

    /**
     * Hot-unload: every side effect of this plugin (and its sub-plugins) is rolled back.
     */
    public suspend fun dispose() {
        fork.parent.registry.lifecycle {
            fork.dispose()
        }
    }
}
