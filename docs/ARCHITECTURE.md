# Architecture of kotlin-cordis

`kotlin-cordis` is a Kotlin Multiplatform port of the Cordis architectural pattern. It enables building extensible, modular systems where everything is a plugin and plugins compose along the axes of time (lifecycles/scopes) and space (isolation realms), mediated by deterministic reactivity.

## 1. Concurrency and Serial Execution

In Swift, concurrency was managed via a single `@CordisActor`. In Kotlin Multiplatform:
- **Dispatcher-confined**: `Context.provide`, `Context.update` (in place), `Context.replaceValue`, `Context.plugin`, `Context.effect`, `Context.emit`, `Context.dispose`, `PluginHandle.update` and `PluginHandle.dispose` run on a dedicated serial dispatcher (`Dispatchers.Default.limitedParallelism(1)` by default, configurable in `Context.root(dispatcher)`). Withdrawals and fork reconciliation happen inside these calls, so they are serialized too.
- **Graph-owned lifecycle job**: `Context.plugin`, `Context.provide`, `Context.dispose`, `PluginHandle.update` and `PluginHandle.dispose` start, stop, restart, reconfigure or dispose forks, so they run in a job of the registry's internal, never-cancelled lifecycle scope rather than in the caller's job. The caller suspends until the operation completes and gets its result or exception; cancelling the caller only ends that wait, and the plugin still reaches the status it would have reached. A lifecycle call made from inside a lifecycle job of the same registry (for example `ctx.plugin` or `ctx.provide` inside `apply`) runs inline in that job instead of scheduling a new one.
- **Lock-guarded, callable from any thread**: the non-suspending `Context.on`, `Context.onDispose` and `Context.launch`, `Disposable.invoke` (its action runs exactly once, however many callers race) and the `Scope.collect` / `Scope.dispose` bookkeeping. They take a short internal lock (`Registry` listeners, `Scope` handles) that never suspends nor runs user code; teardowns and handlers run after it is released.
- `Context.zone` and `Context.isolate` are not yet thread-safe: call them from the dispatcher (for example inside `apply`) or from a single thread.
- Because `withContext(dispatcher)` enters immediately when already running on that dispatcher, nested calls (such as a plugin calling `ctx.provide()` inside `apply()`) run without thread-switching or queueing delays.
- Heavy asynchronous workloads belong inside the services themselves, not in the plugin registration kernel.

## 2. Building Blocks (`cordis-kernel`)

| File / Component | Type | Role |
|---|---|---|
| `Scope.kt` | `Scope`, `Disposable`, `Disposer` | LIFO, idempotent teardown. Represents the time axis. `Disposable` is atomic: concurrent `invoke()` calls run its action once. `Scope` requires a `CoroutineScope`: a teardown registered after the scope is disposed is launched at once on it. The graph's scopes use the registry's never-cancelled lifecycle scope, so late teardowns run on the graph dispatcher, even after root dispose. |
| `Service.kt` | `ServiceKey`, `ServiceId`, `Slot`, `Inject`, events, `CordisException` | Typed service identity and plugin dependency specifications. |
| `Registry.kt` | `Registry` | Per-graph state: slots with generation counters, live forks, event listeners, zones. |
| `Plugin.kt` | `Plugin`, `Fork`, `PluginHandle`, `UpdateOutcome` | Plugin definition and its reactive lifecycle (`Pending`, `Loading`, `Active`, `Failed`, `Disposed`). |
| `Context.kt` | `Context` | The primary API plugins interact with: service lookup, provision, events, isolation, interceptors. |
| `Snapshot.kt` | `RuntimeSnapshot`, `PluginInfo`, `SlotInfo` | Read-only point-in-time snapshot of the graph structure and statuses. |

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
- The graph's coroutine scope has a `CoroutineExceptionHandler`. A throwing `ctx.launch` body, timer body (`after` / `every`) or internal launch is reported as `TaskFailed(plugin, error)`, where `plugin` is the launching context's name (`root` when there is no owner). The exception never reaches the platform's uncaught-exception handler.
- A `CancellationException` out of `apply` is never a failure: the run is rolled back, the fork returns to `Pending` (unless disposed), no `Failed` status is set and no `PluginFailed` is emitted.
- `Disposed` is terminal: once a fork is `Disposed`, no further status change or `PluginFailed` is emitted for it. Disposing a fork while its `apply` is suspended cancels that `apply`, waits for its rollback and ends in `Disposed`.
- Root `Context.dispose()` tears the graph down children first, then cancels the graph's coroutine scope (`Registry.coroutineScope`), so every `ctx.launch` job and timer stops. A second `dispose()` is a no-op.
- Neither `ListenerFailed` nor `TaskFailed` changes plugin status: the plugin stays `Active`. Only a throwing `apply` or a config rejected by `validate` on load yields `Failed` and `PluginFailed`.

## 9. Timer Effects (`cordis`)

- `ctx.after(delay, clock) { ... }` runs once after a delay.
- `ctx.every(interval, clock) { ... }` runs periodically.
- Built on `ctx.effect`: when the context, plugin, or scope is disposed, the timer's job is cancelled and joined immediately, preventing background leaks.
- Injectable `CordisClock` allows tests to use `ManualClock` for zero-delay deterministic time simulation.

## 10. Declarative Loading (`cordis-loader`)

- `PluginRegistry` binds resolve keys (strings) to plugin singletons and their serializers.
- `LoadPlan` defines a sequence of entries with keys, optional names, and serialized JSON configs.
- `plan.validate(against = registry)` validates all entries upfront: unknown keys, duplicate entry names, and JSON decoding errors are collected into `LoaderError`.
- If validation finds any errors, `Context.load` aborts without loading any plugin.
