# Architecture of kotlin-cordis

`kotlin-cordis` is a Kotlin Multiplatform port of the Cordis architectural pattern. It enables building extensible, modular systems where everything is a plugin and plugins compose along the axes of time (lifecycles/scopes) and space (isolation realms), mediated by deterministic reactivity.

## 1. Concurrency and Serial Execution

In Swift, concurrency was managed via a single `@CordisActor`. In Kotlin Multiplatform every call falls in one of three groups.

**Safe from any thread**
- **Lock-free reads**: `ctx[Key]` (and so `ctx.require(Key)`) reads the registry's copy-on-write slot map without a lock or a dispatcher switch, so handlers and UI threads can call it directly.
- **Lock-guarded**: the non-suspending `Context.on`, `Context.onDispose`, `Context.launch` and `Context.contributions`, disposal of a contribution handle, `Disposable.invoke` (its action runs exactly once, however many callers race) and the `Scope.collect` / `Scope.dispose` bookkeeping. They take a short internal lock (`Registry` listeners and contributions, `Scope` handles) that never suspends nor runs user code; teardowns and handlers run after it is released.
- **`cordis-runtime`**: `PluginRuntime.start`, `setEnabled` and `stop` are serialised by one `Mutex` and callable from any thread, and `PluginRuntime.state` is readable from any thread (see section 12).

**Runs on the graph dispatcher**
- **Dispatcher-confined**: `Context.provide`, `Context.update` (in place), `Context.replaceValue`, `Context.contribute`, `Context.plugin`, `Context.effect`, `Context.emit`, `Context.dispose`, `PluginHandle.update` and `PluginHandle.dispose` run on the graph's serial dispatcher. Withdrawals and fork reconciliation happen inside these calls, so they are serialized too.
- **Graph-owned lifecycle job**: `Context.plugin`, `Context.provide`, `Context.dispose`, `PluginHandle.update` and `PluginHandle.dispose` start, stop, restart, reconfigure or dispose forks, so they run in a job of the registry's internal, never-cancelled lifecycle scope rather than in the caller's job. The caller suspends until the operation completes and gets its result or exception; cancelling the caller only ends that wait, and the plugin still reaches the status it would have reached. A lifecycle call made from inside a lifecycle job of the same registry (for example `ctx.plugin` or `ctx.provide` inside `apply`) runs inline in that job instead of scheduling a new one.
- **How the dispatcher orders steps**: it runs one step at a time. It is `Dispatchers.Default.limitedParallelism(1)` by default, configurable in `Context.root(dispatcher)`. Because `withContext(dispatcher)` enters immediately when already running on that dispatcher, nested calls (such as a plugin calling `ctx.provide()` inside `apply()`) run inline, without thread-switching or re-queueing.

**Not thread-safe yet**
- `Context.zone` and `Context.isolate`: call them from the dispatcher (for example inside `apply`) or from a single thread.

Heavy asynchronous workloads belong inside the services themselves, not in the plugin registration kernel.

## 2. Building Blocks (`cordis-kernel`)

| File / Component | Type | Role |
|---|---|---|
| `Scope.kt` | `Scope`, `Disposable`, `Disposer` | LIFO, idempotent teardown. Represents the time axis. `Disposable` is atomic: concurrent `invoke()` calls run its action once. `Scope` requires a `CoroutineScope`: a teardown registered after the scope is disposed is launched at once on it. The graph's scopes use the registry's never-cancelled lifecycle scope, so late teardowns run on the graph dispatcher, even after root dispose. |
| `Service.kt` | `ServiceKey`, `ContributionKey`, `ServiceId`, `Slot`, `Inject`, events, `CordisException` | Typed service and contribution point identity and plugin dependency specifications. |
| `Registry.kt` | `Registry` (internal to the kernel) | Per-graph state: slots with generation counters, live forks, event listeners, zones, contribution lists. |
| `Plugin.kt` | `Plugin`, `Fork`, `PluginHandle`, `UpdateOutcome` | Plugin definition and its reactive lifecycle (`Pending`, `Loading`, `Active`, `Failed`, `Disposed`). |
| `Context.kt` | `Context` | The primary API plugins interact with: service lookup, provision, contributions, events, isolation, interceptors. |
| `Snapshot.kt` | `RuntimeSnapshot`, `PluginInfo`, `SlotInfo`, `PluginId`, `PluginKey` | Read-only point-in-time snapshot of the graph structure and statuses, also published live through `Context.snapshotFlow()`. |

## 3. Reactivity and Cascade Reconciler

1. `ctx.provide` bumps a global generation counter, allocates an opaque `OccurrenceId`, and stores an `Entry` in the slot.
2. `Registry.changed` emits `ServiceChanged` and invokes `Fork.refresh()` on all forks that depend on that slot.
3. `Fork.reconcile` computes the required and optional slot occurrences:
   - Missing required service: fork stops and returns to `Pending`.
   - Changed service occurrence: fork stops and starts afresh.
   - All services present and unchanged: no-op.
4. **Re-entrancy Safety**: If a cascading restart or dependency provision triggers another `refresh()` while `reconcile()` is in-flight, an internal flag `needsReconcile` is set and processed iteratively by a `do-while` loop. `refresh()` clears its re-entrancy flag in a `finally` block, so an exception during reconciliation never leaves a fork unable to reconcile again.
5. `Context.provide` automatically registers a withdraw hook in the context's `Scope`. Unloading a provider withdraws the service, automatically cascading to its dependents.

## 4. Convergence and In-Place Mutation

Each `provide` allocates a new `OccurrenceId`. Dependents restart if and only if an occurrence changes or is removed.
For cases where a published service needs to be updated without restarting dependents:
- `ctx.update(Key) { value -> ... }` mutates the published value in-place without bumping generation or occurrence.
- No `ServiceChanged` is emitted and dependents are not restarted.
- Attempting to update a slot not published by the calling context throws `CordisException.NotPublished`.

## 5. Space: Isolation Realms

A `Slot` is the combination of `(ServiceId, Realm)`.
- Default realm is `Slot.SHARED_REALM` ("shared").
- `ctx.isolate(Key, label = "tenant")` returns a child context resolving `Key` in a tenant-specific realm.
- Same label shares the realm (e.g., all plugins belonging to tenant "acme" share the "acme" DB instance).
- Omitting the label creates a private, anonymous realm (`anon-1`, `anon-2`).

## 6. Zones: Hierarchical Event Routing

`ctx.zone(label)` produces a child context within a hierarchical event zone.
- Listeners with no zone hear all events (global).
- Listeners in an ancestor zone receive events emitted from descendant zones.
- Sibling zones are completely isolated from each other's zoned events.
- Zones are a routing convenience and do not represent a security sandbox.

## 7. Intercept Layers

`ctx.intercept(Key) { value -> wrap(value) }` creates a child context with layered decorators.
- Layers are applied at provide-time in reverse order of registration (first registered layer is the outermost wrapper).
- Layers are inherited by child contexts (`isolate`) and plugins loaded through that context.
- Intercept layers are never retroactive: already provided services are untouched.

## 8. Failure Model and Error Handling

- If `Plugin.apply` throws, the fork's scope is immediately rolled back (all `onDispose` hooks executed), its status is set to `Failed`, and `PluginFailed` is emitted. The rest of the graph remains stable.
- `PluginHandle.update` runs `Plugin.validate(config)` before tearing anything down. If validation throws, `UpdateOutcome.Rejected` is returned and the running plugin is untouched.
- `Context.plugin(plugin, config)` (and so `cordis-loader`) also runs `Plugin.validate(config)` before the first `apply`. If validation throws, the fork is still registered and a handle is returned, but `apply` is never called: the status is set to `Failed` and `PluginFailed` is emitted, even when required services are missing. The fork stays `Failed` while its injected services appear, change or go away, until a valid `PluginHandle.update` reconfigures it; `dispose` works as usual.
- Conflicting providers for the same slot throw `CordisException.ServiceConflict`, failing the incoming plugin while keeping the original provider active.
- Teardowns may throw. `Scope.dispose` runs all registered disposables in reverse order, catching each error and emitting it as `TeardownFailed(plugin, error)` without aborting the remaining disposables.
- Listeners are isolated from each other and from the emitter. `Registry.emit` calls each listener in its own `try`/`catch`: a throwing listener does not stop the remaining listeners, the emitter never sees the error, and the failure is emitted as `ListenerFailed(event, error)` from the same zone. A listener that throws while handling `ListenerFailed` is not reported again, so there is no recursion. `CancellationException` still propagates.
- The graph's coroutine scope has a `CoroutineExceptionHandler`. A throwing `ctx.launch` body, timer body (`after` / `every`), timer clock (a `CordisClock.delay` that throws) or internal launch is reported as `TaskFailed(plugin, error)`, where `plugin` is the launching context's name (`root` when there is no owner). The exception never reaches the platform's uncaught-exception handler.
- A `CancellationException` out of `apply` is never a failure: the run is rolled back, the fork returns to `Pending` (unless disposed), no `Failed` status is set and no `PluginFailed` is emitted.
- `Disposed` is terminal: once a fork is `Disposed`, no further status change or `PluginFailed` is emitted for it. Disposing a fork while its `apply` is suspended cancels that `apply`, waits for its rollback and ends in `Disposed`.
- Root `Context.dispose()` tears the graph down children first, then cancels the graph's coroutine scope (`Registry.coroutineScope`), so every `ctx.launch` job and timer stops. A second `dispose()` is a no-op.
- Neither `ListenerFailed` nor `TaskFailed` changes plugin status: the plugin stays `Active`. Only a throwing `apply` or a config rejected by `validate` on load yields `Failed` and `PluginFailed`.
- `PluginKey` is an optional, stable identifier a plugin declares with `override val pluginKey = PluginKey("db")` (or the `key` parameter of the lambda `ctx.plugin(name, inject, key) { }`). It is never derived from `name` or the class name, so it survives R8 obfuscation, and it is not unique: every application of the plugin carries it. It appears as `PluginInfo.key`, as `SlotInfo.providerKey` and as the `key` of `PluginStatusChanged`, `PluginFailed` and `TeardownFailed` (`null` for the root and for plugins without a key).
- `PluginInfo.inject` is the fork's declared `Inject`, and `PluginInfo.missing` lists the required services whose slot (in the fork's own realms) has no provider right now, in declaration order. Optional services never appear in it, and a service the fork provides itself counts as present, so an empty `missing` means the plugin is not blocked on dependencies.
- `Context.snapshotFlow()` is one `StateFlow<RuntimeSnapshot>` per graph, shared by every context and ignoring zones. The registry republishes `snapshot()` on each registration, removal, provide, withdraw and status change, before the matching event is emitted; in-place `update` / `replaceValue` do not republish.

## 9. Timer Effects (`cordis`)

- `ctx.after(delay, clock) { ... }` runs once after a delay.
- `ctx.every(interval, clock) { ... }` runs periodically.
- Built on `ctx.launch`: when the context, plugin, or scope is disposed, the timer's job is cancelled and joined immediately, preventing background leaks. A timer that finishes frees its scope entry.
- Injectable `CordisClock` allows tests to use `ManualClock` for zero-delay deterministic time simulation.

## 10. Declarative Loading (`cordis-loader`)

- `PluginFactoryRegistry` binds `PluginKey`s to plugin singletons and their config decoders. `register` takes an optional `key`; it defaults to the plugin's own `pluginKey`, and registering with neither throws `IllegalArgumentException`.
- `LoadPlan` is `@Serializable`: a sequence of entries with a `PluginKey` (a plain JSON string), an optional name, and an optional `JsonElement` config. `LoadPlan.fromJson(text)` parses `{"entries":[...]}` strictly and throws `SerializationException` on malformed input.
- A config registered with a serializer is decoded from the entry's `JsonElement`; a missing config decodes as `{}`, so a config type whose fields all have defaults loads with those defaults.
- `plan.validate(against = registry)` validates all entries upfront: unknown keys, duplicate entry names, and JSON decoding errors are collected into `LoaderError`.
- `Context.load` returns one `LoadOutcome` per entry. If validation finds any errors it loads nothing: entries with errors are `Invalid(errors)` and the others `NotLoaded`. Otherwise each entry is `Loaded(handle)`, carrying the `PluginHandle` (`status`, `awaitActive`, `update`, `dispose`); a plugin that fails its own `validate` or `apply` is `Loaded` with a `Fork.Status.Failed` status.

## 11. Contribution Points

A contribution point is a typed list that many plugins add to, such as the items of a drawer or the routes of an admin panel.
- `object DrawerItems : ContributionKey<DrawerItem>` declares a point. `ContributionKey` has no members; its identity is the key's class.
- `ctx.contribute(Key, item, order = 0)` adds one entry and returns a `Disposable`. It runs on the dispatcher and throws `CordisException.Disposed` on a disposed context. The entry is removed when the handle is invoked or when the contributing context's scope is disposed (plugin disabled, restarted, failed, or an ancestor unloaded). Invoking the handle twice is a no-op.
- `ctx.contributions(Key)` returns a `StateFlow<List<T>>`, `emptyList()` until something is contributed. Every add and remove publishes a new list.
- Ordering: ascending `order`, then registration order. Removing an entry never reorders the others. Entries are tracked by registration, not by equality, so the same item contributed twice is two entries with their own handles.
- Lists are per graph: every context of the graph (root, plugins, `zone` / `isolate` / `intercept` children) gets the same `StateFlow` instance for a key, and realms do not split lists.
- Unlike services, a point has many providers and never throws `ServiceConflict`. Contributions do not drive reactivity: they refresh, start, stop or restart no fork, emit no `CordisEvent` and are not part of `snapshot()` / `snapshotFlow()`.
- The lists live in `Registry`, guarded by its lock. Adds and removals run on the dispatcher, so each new list is published in order and outside the lock.

## 12. Runtime Toggles (`cordis-runtime`)

`PluginRuntimeDefault` enables and disables the entries of a `PluginCatalog` in a `Context` while the app runs.
- A catalog is built with `pluginCatalog { plugin(key, plugin, config, metadata) }`, in declaration order. The catalog key is the runtime's identity for the entry and need not equal `plugin.pluginKey`. A duplicate key, or an entry that is `locked` but not `enabledByDefault`, throws `IllegalArgumentException`.
- Persistence: an `EnabledPluginsStore` holds only overrides that differ from the catalog default. The effective value is the override, else `enabledByDefault`; a locked entry is always enabled. On `start()` the store is read once, and overrides for unknown keys, overrides equal to the default and `false` for a locked entry are dropped. Each `save` writes the full current override set, so setting a value back to its default removes its key and unknown keys are not written back. `start()` and `stop()` never save.
- Refusals throw the sealed `PluginRuntimeException`: `UnknownPlugin(key)` for a key not in the catalog, `LockedPlugin(key)` for disabling a locked entry and `NotStarted` before `start()` or after `stop()`. A refused call changes neither state nor store. A no-op toggle neither saves nor touches the kernel.
- Order of a toggle: save the new override set, then load the entry (`ctx.plugin`) or dispose its `PluginHandle`, then publish. A failing `save` propagates and leaves kernel and state unchanged. `stop()` disposes every loaded handle in reverse catalog order.
- State: `state` is a `StateFlow<List<PluginState>>` with one entry per catalog entry, in catalog order. `status` and `missing` come from the entry's fork in the latest `RuntimeSnapshot`; `status` is `null` when the runtime holds no handle (disabled or not started). While started, a collector launched with `ctx.launch` follows `snapshotFlow()`, so kernel changes the runtime did not cause (a dependent going `Pending` when its provider is disabled) show up on their own. `MutableStateFlow` equality drops identical lists.
- Serialisation: one `Mutex` orders `start`, `setEnabled` and `stop`, so concurrent calls from any thread end in the state of the last call to take the lock, with state, store and kernel in agreement. Publishing is guarded by a second mutex and always derives from the latest holdings and snapshot, so a stale list never lands last.

## 13. Flows and Launch

`Context` exposes the graph to coroutine code through four flows, a structured `launch` and `PluginHandle.awaitActive()`.
- `ctx.events<E>()` / `ctx.events(KClass)`: a cold `Flow<E>` built on `callbackFlow` and buffered `Channel.UNLIMITED`. Each collection registers its own listener, filtered by the context's zone like `ctx.on`, and removes it when collection stops. It completes normally when the collector stops or the context is disposed.
- `ctx.serviceFlow(Key)`: a cold `Flow<T?>` that emits the current value (`null` when the slot is empty), then the new value on each provide or withdraw of that slot. It ignores zones. In-place `update` / `replaceValue` emit nothing, like `ServiceChanged`. It completes normally when the context is disposed.
- `ctx.snapshotFlow()`: a `StateFlow<RuntimeSnapshot>`, one per graph and shared by every context (see section 8 for when it is republished). It is a `StateFlow`, so it never completes, also after the context is disposed.
- `ctx.contributions(Key)`: a `StateFlow<List<T>>`, one per graph and key (see section 11). It never completes; an entry is removed when its contributing scope is disposed.
- `ctx.launch(context) { }`: starts a coroutine in the registry's coroutine scope and returns its `Job`. The job is cancelled and joined when the context's scope is disposed (plugin unloaded, restarted or failed). A thrown exception is reported as `TaskFailed` and leaves the plugin status unchanged. On a disposed context `launch` throws `CordisException.Disposed`.
- `PluginHandle.awaitActive()`: suspends until the plugin's status is neither `Pending` nor `Loading`, then returns `Active`, `Failed` or `Disposed`; a plugin disposed while waiting returns `Disposed`. It must not be called from the plugin's own `apply`, which would wait on itself.

## 14. Ktor Server (`cordis-ktor`)

`cordis-ktor` ties a graph to a Ktor 3 server `Application`. It is JVM only.
- `install(Cordis) { }` owns one root context: `context = ...` passes one in, and the default `null` makes the plugin create `Context.root()`. `runtime = { ctx -> ... }` optionally builds a `PluginRuntime` on that context.
- The runtime is started with `runBlocking` before `install` returns, so the enabled catalog entries are loaded before the server handles a call.
- On `ApplicationStopped` the runtime is stopped, then the context is disposed in a `finally`, so the context is disposed even if `stop()` throws.
- `Application.cordis` / `ApplicationCall.cordis` return the root context and `Application.cordisRuntime` / `ApplicationCall.cordisRuntime` the runtime (`null` when none is configured). All of them throw `IllegalStateException` when `Cordis` is not installed.
- `Route.requireService(key, whenMissing = HttpStatusCode.ServiceUnavailable) { service -> }` reads the slot on every call: while it is empty the call gets `whenMissing` (503 by default) with an empty body and the handler does not run.
- `Route.cordisAdmin(runtime)` mounts `GET plugins` and `PUT plugins/{key}` as JSON. It has no authentication or authorization of its own and must be mounted behind the host's.

## 15. Android (`cordis-android`)

`cordis-android` is an Android library for the platform glue.
- `AppForegroundPlugin` (a `Plugin<LifecycleOwner>`), loaded by `ctx.installAppForeground(owner)`, provides `AppForegroundKey` (value `AppForeground`) while the owner's lifecycle is at least `STARTED` and withdraws it below that, so plugins that require it pause in the background. The default owner is `ProcessLifecycleOwner.get()`.
- Disposing the returned `PluginHandle` stops observing the owner and withdraws the service.
- `DataStoreEnabledPluginsStore(dataStore)` is an `EnabledPluginsStore` on a Preferences `DataStore`. It persists overrides as booleans under its own `cordis.enabled.<key>` preferences and never reads or writes other keys.
- The module is opt-in: `settings.gradle.kts` includes it only when an Android SDK is found (`sdk.dir` in `local.properties`, `ANDROID_HOME` or `ANDROID_SDK_ROOT`). It is not published; consume it as a project dependency.
