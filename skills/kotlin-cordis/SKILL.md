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

## Modules and coordinates

Group `io.github.amine2233`, published to GitHub Packages (`https://maven.pkg.github.com/amine2233/kotlin-cordis`).

| Module | Use |
|---|---|
| `cordis` | what apps depend on: re-exports the kernel, adds `ServicePlugin`, `ctx.after`, `ctx.every` |
| `cordis-loader` | `LoadPlan`, `loadPlan { }`, `PluginRegistry`, `ctx.load(...)` (kotlinx-serialization JSON) |
| `cordis-testing` | `withTestApp`, `settle`, `ManualClock` (test scope only) |
| `cordis-kernel` | zero-sugar core; pulled in by `cordis` |

Packages: `cordis.kernel.*` (Context, Plugin, Inject, ServiceKey, events), `cordis.*` (ServicePlugin, timers), `cordis.loader.*`.

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
- Built-in events: `ServiceChanged`, `PluginStatusChanged`, `PluginFailed`, `TeardownFailed`.

Space and routing: `ctx.isolate(Key, label)` gives a child where `Key` resolves to a separate instance (same label = shared);
`ctx.zone("ui")` scopes event delivery; `ctx.intercept(Key) { wrap(it) }` decorates future `provide`s.

Declarative loading (`cordis-loader`):
```kotlin
val registry = PluginRegistry().apply {
    register(DbPlugin, key = "db", serializer = DbConfig.serializer())
    register(Metrics, key = "metrics")                    // Plugin<Unit>
}
val plan = loadPlan {
    plugin("db", config = DbConfig(url), serializer = DbConfig.serializer())
    plugin("metrics", name = "custom-metrics")
}
val outcomes = app.load(plan, registry)                   // List<LoadOutcome(key, name, status)>
```
An invalid plan (unknown key, duplicate name, bad config) loads nothing; every entry is reported `Failed`.
Raw JSON is also fine: `LoadPlan(listOf(LoadPlan.Entry(key = "db", config = """{"url":"…"}""")))`.

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
