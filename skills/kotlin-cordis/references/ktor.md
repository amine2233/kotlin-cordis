# Ktor server

Use the JVM artifacts. One graph per `Application`; routes only read services. Plugins own long-lived
resources (DB pool, HTTP client, scheduler) so config reloads and shutdown are automatic.

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
authenticate("admin") {                                // host-supplied auth: cordisAdmin has none
    route("/admin") { cordisAdmin(checkNotNull(application.cordisRuntime)) }
}
```
`GET plugins` lists every `PluginState` as JSON (`key`, `metadata`, `enabled`, `status`, `error`, `missing`);
`PUT plugins/{key}` with `{"enabled": false}` toggles one and returns its new state. Refusals: 400 malformed body,
404 unknown key, 409 locked entry, 503 runtime not started. No `ContentNegotiation` is needed.

## Health endpoint from the snapshot

```kotlin
get("/healthz") {
    val plugins = call.application.cordis.snapshot().plugins
    val ok = plugins.all { it.status is Fork.Status.Active }
    call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, plugins.map { "${it.name}: ${it.status}" })
}
```
`RuntimeSnapshot` is marked provisional (`PluginInfo`: id, name, status, parent, key, inject, missing), so keep this behind one helper.

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
- `cordisAdmin` has no authentication or authorization: always mount it behind the host's own auth.
- Install `Cordis` once per `Application`; a second `install` fails with Ktor's duplicate-plugin error.
- Never use `call.application` coroutine scope for plugin work; use `ctx.launch` so shutdown cancels it.
- Tests: `testApplication { application { module() } … }`; or test plugins alone with `withTestApp`.
