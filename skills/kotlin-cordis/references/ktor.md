# Ktor server

Use the JVM artifacts. One graph per `Application`; routes only read services. Plugins own long-lived
resources (DB pool, HTTP client, scheduler) so config reloads and shutdown are automatic.

## Install helper

```kotlin
val CordisAttribute = AttributeKey<Context>("cordis")
val Application.cordis: Context get() = attributes[CordisAttribute]

fun Application.installCordis(setup: suspend Context.() -> Unit): Context {
    val ctx = Context.root()
    runBlocking { ctx.setup() }
    attributes.put(CordisAttribute, ctx)
    monitor.subscribe(ApplicationStopped) { runBlocking { ctx.dispose() } }     // tears everything down, LIFO
    return ctx
}
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
    installCordis {
        plugin(DbPlugin, DbConfig(config.property("db.url").getString()))
        plugin(HttpClientPlugin)
        plugin(Cleanup)
    }
    routing {
        get("/users/{id}") {
            val db = call.application.cordis.require(DbKey)   // lock-free read
            call.respond(db.find(call.parameters["id"]!!))    // ! – route param is declared in the path
        }
    }
}
```

## Health endpoint from the snapshot

```kotlin
get("/healthz") {
    val plugins = call.application.cordis.snapshot().plugins
    val ok = plugins.all { it.status is Fork.Status.Active }
    call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, plugins.map { "${it.name}: ${it.status}" })
}
```
`RuntimeSnapshot` is marked provisional (`PluginInfo`: id, name, status, parent), so keep this behind one helper.

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
- `installCordis` blocks at startup (`runBlocking`) — keep `apply` quick; start I/O with `ctx.launch`.
- Never use `call.application` coroutine scope for plugin work; use `ctx.launch` so shutdown cancels it.
- Tests: `testApplication { application { module() } … }`; or test plugins alone with `withTestApp`.
