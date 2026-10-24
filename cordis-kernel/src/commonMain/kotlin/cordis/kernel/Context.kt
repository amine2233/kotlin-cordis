package cordis.kernel

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KClass

/**
 * The only API a plugin sees. A context is a view on the plugin graph with:
 * - a scope (time): everything registered through it dies with it;
 * - a set of realms (space): which instance of each service it resolves to.
 */
class Context internal constructor(
    val registry: Registry,
    val parent: Context?,
    val fork: Fork?,
    val scope: Scope,
    internal val realms: Map<ServiceId, String> = parent?.realms ?: emptyMap(),
    internal val layers: Map<ServiceId, List<(Any) -> Any>> = parent?.layers ?: emptyMap(),
    val zoneId: ZoneId? = parent?.zoneId,
) {
    val name: String
        get() = fork?.name ?: "root"

    companion object {
        /**
         * Creates the root of a new plugin graph (your "App").
         */
        fun root(dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)): Context {
            val registry = Registry(dispatcher)
            val scope =
                Scope(
                    failureHandler = { error ->
                        registry.emit(TeardownFailed(plugin = "root", error = error))
                    },
                    coroutineScope = registry.coroutineScope,
                )
            return Context(
                registry = registry,
                parent = null,
                fork = null,
                scope = scope,
            )
        }
    }

    /**
     * Tears down the whole graph, children first.
     */
    suspend fun dispose() {
        withContext(registry.dispatcher) {
            scope.dispose()
        }
    }

    fun slot(service: ServiceId): Slot = Slot(service = service, realm = realms[service] ?: Slot.SHARED_REALM)

    // MARK: Services

    operator fun <T : Any> get(key: ServiceKey<T>): T? {
        val entry = registry.entry(slot(key.serviceId)) ?: return null
        @Suppress("UNCHECKED_CAST")
        return entry.value as? T
    }

    fun <T : Any> require(key: ServiceKey<T>): T = get(key) ?: throw CordisException.MissingService(key.serviceId)

    /**
     * Registers a service instance in this context's realm.
     * Withdrawn automatically when this context's scope is disposed,
     * which in turn stops/restarts every plugin that injected it.
     */
    suspend fun <T : Any> provide(
        key: ServiceKey<T>,
        value: T,
    ) {
        withContext(registry.dispatcher) {
            if (scope.isDisposed) throw CordisException.Disposed()

            val targetSlot = slot(key.serviceId)
            val layersForSlot = layers[key.serviceId] ?: emptyList()
            val wrapped =
                layersForSlot
                    .asReversed()
                    .fold(value as Any) { current, layer -> layer(current) }

            registry.provide(targetSlot, wrapped, by = this@Context)
            val ownerContext = this@Context
            val forkId = fork?.id
            scope.collect {
                registry.withdraw(targetSlot, owner = ownerContext, fork = forkId)
            }
        }
    }

    /**
     * Mutates, in place, the value this context published for `key`.
     * The occurrence is unchanged: dependents are not restarted and no `ServiceChanged` is emitted.
     * Throws [CordisException.NotPublished] when this context did not publish `key`.
     */
    suspend fun <T : Any> update(
        key: ServiceKey<T>,
        mutate: (T) -> Unit,
    ) {
        withContext(registry.dispatcher) {
            if (scope.isDisposed) throw CordisException.Disposed()
            registry.update(slot(key.serviceId), by = this@Context, mutate = mutate)
        }
    }

    /**
     * Replaces, in place, the value this context published for `key`.
     * The occurrence is unchanged: dependents are not restarted and no `ServiceChanged` is emitted.
     * Throws [CordisException.NotPublished] when this context did not publish `key`.
     */
    suspend fun <T : Any> replaceValue(
        key: ServiceKey<T>,
        mutate: (T) -> T,
    ) {
        withContext(registry.dispatcher) {
            if (scope.isDisposed) throw CordisException.Disposed()
            registry.replaceValue(slot(key.serviceId), by = this@Context, mutate = mutate)
        }
    }

    // MARK: Space — isolation

    /**
     * Returns a child context where `key` resolves to a separate realm.
     * Same `label` => shared realm (e.g. one DB per tenant, shared by that tenant's plugins).
     * No label => a private, anonymous realm.
     */
    fun isolate(
        key: ServiceKey<*>,
        label: String? = null,
    ): Context {
        val newRealms = realms.toMutableMap()
        newRealms[key.serviceId] = label ?: registry.anonymousRealm()
        return Context(
            registry = registry,
            parent = this,
            fork = fork,
            scope = scope,
            realms = newRealms,
            layers = layers,
            zoneId = zoneId,
        )
    }

    // MARK: Event routing — zones

    /**
     * Returns a child context in the zone `label` under this context's zone.
     * Listeners registered through it only hear events emitted from the same zone or a descendant;
     * listeners with no zone hear everything. Independent of scope and realms; not an authorization
     * boundary.
     */
    fun zone(label: String): Context =
        Context(
            registry = registry,
            parent = this,
            fork = fork,
            scope = scope,
            realms = realms,
            layers = layers,
            zoneId = registry.zone(label, under = zoneId),
        )

    // MARK: Intercept

    /**
     * Returns a child context whose `provide` of `key` runs `layer` over the value first.
     * Layers are inherited by derived contexts and plugins; the first-registered layer is the outermost
     * wrapper.
     * Values already provided are never rewritten.
     */
    fun <T : Any> intercept(
        key: ServiceKey<T>,
        layer: (T) -> T,
    ): Context {
        val newLayers = layers.toMutableMap()
        val existing = newLayers[key.serviceId]?.toMutableList() ?: mutableListOf()
        existing.add { current ->
            @Suppress("UNCHECKED_CAST")
            layer(current as T)
        }
        newLayers[key.serviceId] = existing
        return Context(
            registry = registry,
            parent = this,
            fork = fork,
            scope = scope,
            realms = realms,
            layers = newLayers,
            zoneId = zoneId,
        )
    }

    // MARK: Composition

    /**
     * Loads a sub-plugin. Its lifetime is bound to this context:
     * unloading the parent unloads the whole subtree.
     */
    suspend fun <C> plugin(
        plugin: Plugin<C>,
        config: C,
    ): PluginHandle<C> =
        withContext(registry.dispatcher) {
            val newFork =
                Fork(
                    name = plugin.name,
                    inject = plugin.inject,
                    parent = this@Context,
                    config = config,
                ) { ctx ->
                    plugin.apply(ctx, config)
                }
            registry.register(newFork)
            scope.collect { newFork.dispose() }
            if (!scope.isDisposed) {
                newFork.refresh()
            }
            PluginHandle(newFork, plugin)
        }

    suspend fun plugin(plugin: Plugin<Unit>): PluginHandle<Unit> = plugin(plugin, Unit)

    /**
     * Loads an anonymous one-off plugin from a lambda.
     */
    suspend fun plugin(
        name: String,
        inject: Inject = Inject.NONE,
        apply: suspend (Context) -> Unit,
    ): PluginHandle<Unit> =
        plugin(
            object : Plugin<Unit> {
                override val name = name
                override val inject = inject

                override suspend fun apply(
                    ctx: Context,
                    config: Unit,
                ) = apply(ctx)
            },
        )

    // MARK: Introspection

    /**
     * Read-only view of the plugins and service slots of this graph.
     */
    fun snapshot(): RuntimeSnapshot = registry.snapshot()

    // MARK: Effects & events

    /**
     * Registers a teardown for this context.
     */
    fun onDispose(teardown: Disposer): Disposable = scope.collect(teardown)

    /**
     * Cordis' `ctx.effect`: run setup now, run the returned teardown on dispose.
     *
     * Example:
     * ```kotlin
     * ctx.effect {
     *     val timer = startTimer()
     *     return@effect { timer.stop() }
     * }
     * ```
     */
    suspend fun effect(setup: suspend () -> Disposer): Disposable =
        withContext(registry.dispatcher) {
            val teardown = setup()
            scope.collect(teardown)
        }

    /**
     * Subscribes to an event type. Automatically unsubscribed with this context.
     */
    fun <E : CordisEvent> on(
        type: KClass<E>,
        handler: suspend (E) -> Unit,
    ): Disposable {
        val id = registry.on(type, zone = zoneId, handler)
        return scope.collect { registry.off(type, id) }
    }

    /**
     * Subscribes to an event type using reified generics.
     */
    inline fun <reified E : CordisEvent> on(noinline handler: suspend (E) -> Unit): Disposable = on(E::class, handler)

    suspend fun emit(event: CordisEvent) {
        withContext(registry.dispatcher) {
            registry.emit(event, from = zoneId)
        }
    }

    // MARK: Flow & structured concurrency

    /**
     * Cold flow of events of type [type]; the listener lives only while the flow is collected
     * (and no longer than this context).
     */
    fun <E : CordisEvent> events(type: KClass<E>): Flow<E> =
        callbackFlow {
            val subscription = on(type) { trySend(it) }
            awaitClose { registry.coroutineScope.launch { subscription() } }
        }.buffer(Channel.UNLIMITED)

    inline fun <reified E : CordisEvent> events(): Flow<E> = events(E::class)

    /**
     * Emits the current value of [key] (null when unavailable), then again each time the slot is
     * provided or withdrawn. In-place [update] / [replaceValue] emit nothing, like `ServiceChanged`.
     */
    fun <T : Any> serviceFlow(key: ServiceKey<T>): Flow<T?> =
        callbackFlow {
            val target = slot(key.serviceId)
            val id =
                registry.on(ServiceChanged::class, zone = null) {
                    if (it.slot == target) trySend(get(key))
                }
            trySend(get(key))
            awaitClose { registry.off(ServiceChanged::class, id) }
        }.buffer(Channel.UNLIMITED)

    /**
     * Launches a coroutine bound to this context's scope: it is cancelled and joined when the
     * plugin unloads, restarts or fails.
     */
    fun launch(
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        if (scope.isDisposed) throw CordisException.Disposed()
        val job = registry.coroutineScope.launch(context, block = block)
        val teardown =
            scope.collect {
                if (currentCoroutineContext()[Job] !== job) {
                    job.cancel()
                    job.join()
                }
            }
        job.invokeOnCompletion { registry.coroutineScope.launch { teardown() } }
        return job
    }
}
