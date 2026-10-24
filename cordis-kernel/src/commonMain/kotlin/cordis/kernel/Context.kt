package cordis.kernel

import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val zoneId: ZoneId? = parent?.zoneId
) {
    val name: String
        get() = fork?.name ?: "root"

    companion object {
        /**
         * Creates the root of a new plugin graph (your "App").
         */
        fun root(
            dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
        ): Context {
            val registry = Registry(dispatcher)
            val scope = Scope(
                failureHandler = { error ->
                    registry.emit(TeardownFailed(plugin = "root", error = error))
                },
                coroutineScope = registry.coroutineScope
            )
            return Context(
                registry = registry,
                parent = null,
                fork = null,
                scope = scope
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

    fun slot(service: ServiceId): Slot {
        return Slot(service = service, realm = realms[service] ?: Slot.SHARED_REALM)
    }

    // MARK: Services

    operator fun <T : Any> get(key: ServiceKey<T>): T? {
        val entry = registry.entry(slot(key.serviceId)) ?: return null
        @Suppress("UNCHECKED_CAST")
        return entry.value as? T
    }

    fun <T : Any> require(key: ServiceKey<T>): T {
        return get(key) ?: throw CordisException.MissingService(key.serviceId)
    }

    /**
     * Registers a service instance in this context's realm.
     * Withdrawn automatically when this context's scope is disposed,
     * which in turn stops/restarts every plugin that injected it.
     */
    suspend fun <T : Any> provide(key: ServiceKey<T>, value: T) {
        withContext(registry.dispatcher) {
            if (scope.isDisposed) throw CordisException.Disposed()

            val targetSlot = slot(key.serviceId)
            val layersForSlot = layers[key.serviceId] ?: emptyList()
            val wrapped = layersForSlot.asReversed()
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
    suspend fun <T : Any> update(key: ServiceKey<T>, mutate: (T) -> Unit) {
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
    suspend fun <T : Any> replaceValue(key: ServiceKey<T>, mutate: (T) -> T) {
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
    fun isolate(key: ServiceKey<*>, label: String? = null): Context {
        val newRealms = realms.toMutableMap()
        newRealms[key.serviceId] = label ?: registry.anonymousRealm()
        return Context(
            registry = registry,
            parent = this,
            fork = fork,
            scope = scope,
            realms = newRealms,
            layers = layers,
            zoneId = zoneId
        )
    }

    // MARK: Event routing — zones

    /**
     * Returns a child context in the zone `label` under this context's zone.
     * Listeners registered through it only hear events emitted from the same zone or a descendant;
     * listeners with no zone hear everything. Independent of scope and realms; not an authorization
     * boundary.
     */
    fun zone(label: String): Context {
        return Context(
            registry = registry,
            parent = this,
            fork = fork,
            scope = scope,
            realms = realms,
            layers = layers,
            zoneId = registry.zone(label, under = zoneId)
        )
    }

    // MARK: Intercept

    /**
     * Returns a child context whose `provide` of `key` runs `layer` over the value first.
     * Layers are inherited by derived contexts and plugins; the first-registered layer is the outermost
     * wrapper.
     * Values already provided are never rewritten.
     */
    fun <T : Any> intercept(key: ServiceKey<T>, layer: (T) -> T): Context {
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
            zoneId = zoneId
        )
    }

    // MARK: Composition

    /**
     * Loads a sub-plugin. Its lifetime is bound to this context:
     * unloading the parent unloads the whole subtree.
     */
    suspend fun <C> plugin(plugin: Plugin<C>, config: C): PluginHandle<C> {
        return withContext(registry.dispatcher) {
            val newFork = Fork(
                name = plugin.name,
                inject = plugin.inject,
                parent = this@Context,
                config = config
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
    }

    suspend fun plugin(plugin: Plugin<Unit>): PluginHandle<Unit> {
        return plugin(plugin, Unit)
    }

    // MARK: Introspection

    /**
     * Read-only view of the plugins and service slots of this graph.
     */
    fun snapshot(): RuntimeSnapshot {
        return registry.snapshot()
    }

    // MARK: Effects & events

    /**
     * Registers a teardown for this context.
     */
    fun onDispose(teardown: Disposer): Disposable {
        return scope.collect(teardown)
    }

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
    suspend fun effect(setup: suspend () -> Disposer): Disposable {
        return withContext(registry.dispatcher) {
            val teardown = setup()
            scope.collect(teardown)
        }
    }

    /**
     * Subscribes to an event type. Automatically unsubscribed with this context.
     */
    fun <E : CordisEvent> on(
        type: KClass<E>,
        handler: suspend (E) -> Unit
    ): Disposable {
        val id = registry.on(type, zone = zoneId, handler)
        return scope.collect { registry.off(type, id) }
    }

    /**
     * Subscribes to an event type using reified generics.
     */
    inline fun <reified E : CordisEvent> on(
        noinline handler: suspend (E) -> Unit
    ): Disposable = on(E::class, handler)

    suspend fun emit(event: CordisEvent) {
        withContext(registry.dispatcher) {
            registry.emit(event, from = zoneId)
        }
    }
}
