---
name: kotlin-cordis
description: Use when writing or reviewing code that uses the kotlin-cordis plugin kernel (Context, Plugin, ServiceKey, Inject, ServicePlugin, LoadPlan) — defining services and plugins, reactive dependencies, flows, structured coroutines, JSON load plans, testing — or wiring it into an Android app, a Ktor server, or a CLI.
---

# kotlin-cordis

Everything is a plugin. A plugin only touches the world through its `Context`; whatever it registers
(services, listeners, effects, jobs, sub-plugins) is undone automatically, LIFO, when the plugin
unloads, restarts or fails. Dependencies are reactive: a plugin is `Pending` until its required
services exist, restarts when they change, and falls back to `Pending` when they vanish.

Worked examples (simple / medium / hard): `references/examples.md`.

Platform recipes (read the one that matches the task):
- Android / Compose → `references/android.md`
- Ktor server → `references/ktor.md`
- CLI → `references/cli.md`

Runnable sample apps (Ktor server, Android Compose app, shared sample plugins): `samples/README.md`.

## Modules and coordinates

Group `io.github.amine2233`, published to GitHub Packages (`https://maven.pkg.github.com/amine2233/kotlin-cordis`).
GitHub Packages needs a token with `read:packages`, even for public packages:
```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/amine2233/kotlin-cordis")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
        }
    }
}
dependencies { implementation("io.github.amine2233:cordis:<version>") }
```

| Module | Use |
|---|---|
| `cordis` | what apps depend on: re-exports the kernel, adds `ServicePlugin`, `ctx.after`, `ctx.every` |
| `cordis-loader` | `LoadPlan`, `loadPlan { }`, `LoadPlan.fromJson`, `PluginFactoryRegistry`, `ctx.load(...)` (kotlinx-serialization JSON) |
| `cordis-runtime` | `pluginCatalog { }`, `PluginMetadata`, `EnabledPluginsStore`, `PluginRuntimeDefault`: enable/disable plugins by key, observable `StateFlow<List<PluginState>>` |
| `cordis-ktor` | JVM only: `install(Cordis) { runtime = { ctx -> ... } }`, `call.cordis`, `call.cordisRuntime`, `Route.requireService(key) { service -> }`, `Route.cordisAdmin(runtime)` (no auth: mount behind the host's) |
| `cordis-android` | Android only, opt-in, not published yet (project dependency): `ctx.installAppForeground()` provides `AppForegroundKey` while the app is `STARTED`; `DataStoreEnabledPluginsStore(dataStore)` for `PluginRuntimeDefault` |
| `cordis-testing` | `withTestApp`, `settle`, `ManualClock` (test scope only) |
| `cordis-kernel` | zero-sugar core; pulled in by `cordis` |

Packages: `cordis.kernel.*` (Context, Plugin, Inject, ServiceKey, events), `cordis.*` (ServicePlugin, timers), `cordis.loader.*`, `cordis.runtime.*`, `cordis.ktor.*`.

## Core API cheat sheet

```kotlin
object DbKey : ServiceKey<Database>                       // typed service key = an object

object DbPlugin : ServicePlugin<DbKey, Database, DbConfig> {  // provides one service
    override val key = DbKey
    override suspend fun make(ctx: Context, config: DbConfig) = Database.open(config.url)
}

object Repo : Plugin<Unit> {                              // consumer
    override val inject = Inject.of(required = listOf(DbKey), optional = listOf(LoggerKey))
    override suspend fun apply(ctx: Context, config: Unit) {
        val db = ctx.require(DbKey)                       // throws MissingService if absent
        ctx.provide(RepoKey, Repo(db))                    // withdrawn on dispose
        ctx.on<UserSignedUp> { db.save(it) }              // unsubscribed on dispose
        ctx.effect { val c = db.connect(); return@effect { c.close() } }
        ctx.launch { db.watch().collect { /* … */ } }     // cancelled+joined on dispose
        ctx.onDispose { /* teardown */ }
    }
}

val app = Context.root()                                  // the graph; app.dispose() tears everything down
val handle = app.plugin(DbPlugin, DbConfig(url))          // PluginHandle<DbConfig>
handle.update(DbConfig(other))                            // validate → restart dependents; Unchanged if equal
handle.awaitActive()                                      // Active | Failed | Disposed
handle.dispose()
app.plugin("metrics", Inject.of(required = listOf(LoggerKey))) { ctx -> /* lambda plugin */ }
```

Reading and reacting:
- `ctx[Key]` → `T?`, lock-free, safe from any thread (handlers, UI thread).
- `ctx.require(Key)` → `T` or `CordisException.MissingService`.
- `ctx.serviceFlow(Key): Flow<T?>` — current value, then each provide/withdraw. `replaceValue` / `update` (in place) emit nothing.
- `ctx.events<E>(): Flow<E>`; emit with `ctx.emit(event)`; events implement `CordisEvent`.
- Both `events()` and `serviceFlow()` complete normally when their context is disposed.
- `ctx.snapshotFlow(): StateFlow<RuntimeSnapshot>` — the live `snapshot()` of the whole graph (same instance from every context, ignores zones), updated before the matching event. `PluginInfo.missing` lists the required services a plugin is waiting for.
- Give a plugin a stable, obfuscation-proof identity with `override val pluginKey = PluginKey("db")` (lambda plugins: `ctx.plugin("db", key = PluginKey("db")) { }`). It is optional and not unique, and shows up as `PluginInfo.key`, `SlotInfo.providerKey` and the `key` of the plugin events.
- Built-in events: `ServiceChanged`, `PluginStatusChanged`, `PluginFailed`, `TeardownFailed`, `ListenerFailed`, `TaskFailed`.

Contribution points (many providers, one list per graph):
```kotlin
object DrawerItems : ContributionKey<DrawerItem>          // declares a point
val remove: Disposable = ctx.contribute(DrawerItems, item, order = 0)   // on the dispatcher; removed with the scope
val items: StateFlow<List<DrawerItem>> = ctx.contributions(DrawerItems) // by `order`, then registration
```
Lists are per graph (not split by realms or zones) and drive no reactivity: no fork restarts, no events, not in `snapshot()`.

Space and routing: `ctx.isolate(Key, label)` gives a child where `Key` resolves to a separate instance (same label = shared);
`ctx.zone("ui")` scopes event delivery; `ctx.intercept(Key) { wrap(it) }` decorates future `provide`s.

Declarative loading (`cordis-loader`):
```kotlin
val registry = PluginFactoryRegistry().apply {
    register(DbPlugin, serializer = DbConfig.serializer(), key = PluginKey("db"))
    register(Metrics)                                     // Plugin<Unit>; key defaults to Metrics.pluginKey
}
val plan = loadPlan {
    plugin(PluginKey("db"), config = DbConfig(url), serializer = DbConfig.serializer())
    plugin(PluginKey("metrics"), name = "custom-metrics")
}
val outcomes = app.load(plan, registry)                   // List<LoadOutcome>: Loaded(key, name, handle) | Invalid(key, name, errors) | NotLoaded(key, name)
```
An invalid plan (unknown key, duplicate name, bad config) loads nothing: entries with errors are `Invalid`, the others `NotLoaded`.
A plugin that fails its own `validate`/`apply` is `Loaded` with `status is Fork.Status.Failed`. A missing config decodes as `{}`, so all-defaults config types need none.
Raw JSON is also fine: `LoadPlan.fromJson("""{"entries":[{"key":"db","config":{"url":"…"}}]}""")`.

## Rules and pitfalls

- Register side effects only through `ctx` inside `apply`. Never `GlobalScope.launch`, never a bare `Thread`, never a listener you unsubscribe by hand — use `ctx.launch`, `ctx.on`, `ctx.effect`.
- Services are not singletons held in your own fields; resolve via `ctx.require`/`ctx[...]` so restarts pick up the new instance. Do not cache a service beyond the plugin's lifetime.
- One provider per slot: a second `provide` of the same key from another plugin throws `ServiceConflict`. Use `isolate` for per-tenant instances.
- `Inject.required` ⇒ plugin stays `Pending` (not failed) until available. Use `optional` + `ctx[Key]` for soft deps (plugin restarts when they appear/vanish).
- Do not call `handle.awaitActive()` from that plugin's own `apply` (waits on itself).
- `apply` throwing ⇒ scope rolled back, status `Failed`, `PluginFailed` emitted; dependents are unaffected.
- Graph mutations run on the root's single-threaded dispatcher; `Context.root(dispatcher)` to override. Reads need no switch.
- Make configs data classes: `update` skips the restart when the config `==` the applied one.
- Always `dispose()` the root on shutdown (Android `onTerminate` is unreliable — see android reference; Ktor `ApplicationStopped`; CLI shutdown hook).

## Testing

```kotlin
@Test fun repoStartsWhenDbAppears() = runTest {
    withTestApp { app, events ->
        val repo = app.plugin(Repo)
        assertEquals(Fork.Status.Pending, repo.status)
        app.plugin(DbPlugin, DbConfig("mem"))
        assertEquals(Fork.Status.Active, repo.awaitActive())
        assertTrue(events.failures.isEmpty())
    }
}
```
`settle { condition }` yields until true (bounded); `ManualClock` drives `ctx.after`/`ctx.every` without wall time
(`ctx.every(1.seconds, clock = manualClock) { … }`, then advance the clock).
`withTestApp(StandardTestDispatcher(testScheduler)) { app, events -> … }` runs the graph on the test scheduler; settle it with `advanceUntilIdle()`.
