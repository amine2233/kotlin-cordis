# Ktor server

Use the JVM artifacts. One graph per `Application`; routes only read services. Plugins own long-lived
resources (DB pool, HTTP client, scheduler) so config reloads and shutdown are automatic.

Working server: `samples/ktor` (gated routes, `cordisAdmin`, an SSE stream and an admin page; `./gradlew -p samples :ktor:run`), see `samples/README.md`.

## Install the plugin

Depend on `io.github.amine2233:cordis-ktor` (JVM only). `install(Cordis)` owns one root `Context` per
`Application`: it creates `Context.root()` (or takes `context = myRoot`), builds and starts the optional
runtime before `install` returns, and on `ApplicationStopped` stops the runtime, then disposes the context.

```kotlin
install(Cordis) {
    runtime = { ctx -> PluginRuntimeDefault(ctx, catalog, InMemoryEnabledPluginsStore()) }   // optional
}
val ctx: Context = application.cordis                 // or call.cordis inside a route
val runtime: PluginRuntime? = application.cordisRuntime
```

## Module

```kotlin
object DbKey : ServiceKey<Database>
object ClientKey : ServiceKey<HttpClient>

object DbPlugin : ServicePlugin<DbKey, Database, DbConfig> {
    override val key = DbKey
    override suspend fun make(ctx: Context, config: DbConfig): Database {
        val db = Database.connect(config.url)
        ctx.onDispose { db.close() }                          // pool closed on stop/reload
        return db
    }
}

object HttpClientPlugin : UnitServicePlugin<ClientKey, HttpClient> {
    override val key = ClientKey
    override suspend fun make(ctx: Context): HttpClient =
        HttpClient(CIO).also { c -> ctx.onDispose { c.close() } }
}

object Cleanup : Plugin<Unit> {                               // background job tied to the graph
    override val inject = Inject.of(required = listOf(DbKey))
    override suspend fun apply(ctx: Context, config: Unit) {
        ctx.every(1.hours) { ctx.require(DbKey).purgeExpired() }
    }
}

fun Application.module() {
    val config = environment.config
    install(Cordis)
    runBlocking {
        cordis.plugin(DbPlugin, DbConfig(config.property("db.url").getString()))
        cordis.plugin(HttpClientPlugin)
        cordis.plugin(Cleanup)
    }
    routing {
        get("/users/{id}") {
            val db = call.cordis.require(DbKey)               // lock-free read
            call.respond(db.find(call.parameters["id"]!!))    // ! – route param is declared in the path
        }
    }
}
```

## Gate a route on a service

```kotlin
route("/users/{id}", HttpMethod.Get) {
    requireService(DbKey) { db -> call.respond(db.find(call.parameters["id"]!!)) }   // ! – declared in the path
}
route("/search", HttpMethod.Get) {
    requireService(SearchKey, whenMissing = HttpStatusCode.NotFound) { search -> call.respondText(search.run()) }
}
```
The slot is read on every call: while it is empty the call gets 503 (or `whenMissing`) with an empty body and the
handler does not run, so turning a provider off with the runtime hides the route without touching routing.

## Admin routes

```kotlin
authenticate("admin") {                                // host-supplied auth: cordisAdmin and cordisSnapshot have none
    route("/admin") {
        cordisAdmin(checkNotNull(application.cordisRuntime))
        cordisSnapshot(application.cordis)
    }
}
```
`GET plugins` lists every `PluginState` as JSON (`key`, `metadata`, `enabled`, `status`, `error`, `missing`);
`PUT plugins/{key}` with `{"enabled": false}` toggles one and returns its new state. Refusals: 400 malformed body,
404 unknown key, 409 locked entry, 503 runtime not started. No `ContentNegotiation` is needed.

`cordisSnapshot(context)` is read-only and needs no runtime. Each request takes a fresh `context.snapshot()` and a new
visitor: `GET snapshot/tree` (`TextTreeVisitor`) and `GET snapshot/mermaid` (`MermaidVisitor`) answer `text/plain`;
`GET health` (`HealthVisitor`) answers `{"healthy", "failed", "missingServices", "pending"}`, each item
`{"name", "key", "status", "reason", "missing"}`, with 200 when healthy and 503 otherwise.

## Health endpoint from the snapshot

Use the built-in `health` route of `cordisSnapshot` (see Admin routes) instead of a hand-written one: it answers 503
with the failed, missing-service and pending plugins. Mount it behind the host's auth, since it exposes plugin names,
keys and failure reasons.

## Hot reload of config without restarting the server

```kotlin
val db = ctx.plugin(DbPlugin, DbConfig(url))
// on config change (admin endpoint, file watcher…):
when (val r = db.update(DbConfig(newUrl))) {
    UpdateOutcome.Applied -> db.awaitActive()
    UpdateOutcome.Unchanged -> {}
    is UpdateOutcome.Rejected -> log.warn("bad config", r.error)   // running instance untouched
}
```
Dependents of `DbKey` restart on their own against the new instance.

## Per-tenant isolation

```kotlin
val tenant = ctx.isolate(DbKey, label = "tenant-$id")   // DbKey resolves to its own slot here
tenant.plugin(DbPlugin, DbConfig(tenantUrl))
```

## Pitfalls
- `install(Cordis)` starts the runtime with `runBlocking` at install time, so it blocks startup — keep `apply` quick; start I/O with `ctx.launch`.
- `cordisAdmin` and `cordisSnapshot` have no authentication or authorization: always mount them behind the host's own auth.
- Install `Cordis` once per `Application`; a second `install` fails with Ktor's duplicate-plugin error.
- Never use `call.application` coroutine scope for plugin work; use `ctx.launch` so shutdown cancels it.
- Tests: `testApplication { application { module() } … }`; or test plugins alone with `withTestApp`.
