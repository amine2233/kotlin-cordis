# Examples

Three self-contained examples, from smallest to most involved. All of them are compiled and run in the
repo's test suite. Imports used throughout:
`cordis.ServicePlugin`, `cordis.kernel.*` (`Context`, `Plugin`, `Inject`, `ServiceKey`, `CordisEvent`, `Fork`),
`cordis.loader.*` (medium only).

## Simple — a service and a reactive consumer

A consumer declares what it needs; it is `Pending` until a provider exists, restarts when the provider is
updated, and goes back to `Pending` when the provider is disposed. Nothing is wired by hand.

```kotlin
object GreetingKey : ServiceKey<String>

object GreetingPlugin : ServicePlugin<GreetingKey, String, String> {
    override val key = GreetingKey

    override suspend fun make(ctx: Context, config: String) = "hello $config"
}

class Printer(private val out: MutableList<String>) : Plugin<Unit> {
    override val inject = Inject.of(required = listOf(GreetingKey))

    override suspend fun apply(ctx: Context, config: Unit) {
        out += ctx.require(GreetingKey)
    }
}

suspend fun simpleExample(out: MutableList<String>) {
    val app = Context.root()
    val printer = app.plugin(Printer(out))              // Pending: nobody provides GreetingKey yet
    val greeting = app.plugin(GreetingPlugin, "world")  // Printer starts -> "hello world"
    greeting.update("kotlin")                           // Printer restarts -> "hello kotlin"
    greeting.dispose()                                  // Printer falls back to Pending
    check(printer.status == Fork.Status.Pending)
    app.dispose()
}
```

## Medium — config, events, lambda plugin, declarative loading

A mailer service is loaded from a typed plan (config encoded to JSON, decoded by the `PluginRegistry`).
An anonymous plugin injects it and listens for an event; its listener disappears with the plugin.

```kotlin
data class UserSignedUp(val name: String) : CordisEvent

class Mailer(val from: String, val sent: MutableList<String> = mutableListOf()) {
    fun send(to: String) { sent += "$from -> $to" }
}

object MailerKey : ServiceKey<Mailer>

@Serializable
data class MailerConfig(val from: String)

object MailerPlugin : ServicePlugin<MailerKey, Mailer, MailerConfig> {
    override val key = MailerKey

    override suspend fun make(ctx: Context, config: MailerConfig) = Mailer(config.from)
}

suspend fun mediumExample(): List<String> {
    val app = Context.root()
    val registry = PluginRegistry().apply {
        register(MailerPlugin, key = "mailer", serializer = MailerConfig.serializer())
    }

    val outcomes = app.load(
        loadPlan { plugin("mailer", config = MailerConfig("noreply@a.io"), serializer = MailerConfig.serializer()) },
        registry,
    )
    check(outcomes.single().status == Fork.Status.Active)

    app.plugin("welcome", Inject.of(required = listOf(MailerKey))) { ctx ->
        val mailer = ctx.require(MailerKey)
        ctx.on<UserSignedUp> { mailer.send(it.name) }
    }

    app.emit(UserSignedUp("ada"))
    val mailer = app.require(MailerKey)
    return mailer.sent.toList()
}
```

Extend it:
- `app.plugin("x") { ctx -> ctx.launch { ... } }` for background work tied to the plugin.
- `app.events<UserSignedUp>()` / `app.serviceFlow(MailerKey)` to feed a UI or a `Flow` pipeline.
- `handle.update(MailerConfig(...))` to hot-swap the mailer; `welcome` restarts automatically.

## Hard — multi-tenant isolation, interception, rollback

Each tenant gets its own `Db` and `Repo` through realms (`isolate`), plugins nest (a tenant plugin loads
sub-plugins), every `Db` is wrapped by an audit decorator (`intercept`), and disposing one tenant rolls back
exactly its resources — closing its connection — while the other keeps running.

```kotlin
interface Db {
    val url: String
    fun query(sql: String): String
}

class RealDb(override val url: String, private val log: MutableList<String>) : Db {
    override fun query(sql: String) = "$url:$sql"
    fun close() { log += "close $url" }
}

object DbKey : ServiceKey<Db>
object RepoKey : ServiceKey<Repo>

class Repo(private val db: Db) {
    fun user(id: Int) = db.query("select $id")
}

class DbPlugin(private val log: MutableList<String>) : ServicePlugin<DbKey, Db, String> {
    override val key = DbKey

    override suspend fun make(ctx: Context, config: String): Db {
        val db = RealDb(config, log)
        ctx.onDispose { db.close() }                    // runs on restart, unload and app.dispose()
        return db
    }
}

object RepoPlugin : Plugin<Unit> {
    override val inject = Inject.of(required = listOf(DbKey))

    override suspend fun apply(ctx: Context, config: Unit) {
        ctx.provide(RepoKey, Repo(ctx.require(DbKey)))
    }
}

class TenantPlugin(private val log: MutableList<String>) : Plugin<String> {
    override suspend fun apply(ctx: Context, config: String) {
        val tenant = ctx.isolate(DbKey, config).isolate(RepoKey, config)   // this tenant's own slots
        tenant.plugin(DbPlugin(log), "db://$config")
        tenant.plugin(RepoPlugin)
    }
}

suspend fun hardExample(log: MutableList<String>) {
    val audited = Context.root()
        .intercept(DbKey) { inner ->                    // decorate every Db provided below this context
            object : Db by inner {
                override fun query(sql: String): String {
                    log += "audit $sql"
                    return inner.query(sql)
                }
            }
        }

    val tenantPlugin = TenantPlugin(log)
    val a = audited.plugin(tenantPlugin, "a")
    audited.plugin(tenantPlugin, "b")

    fun repoOf(tenant: String) =
        audited.isolate(DbKey, tenant).isolate(RepoKey, tenant).require(RepoKey)

    check(repoOf("a").user(1) == "db://a:select 1")     // isolated: a and b never see each other
    check(repoOf("b").user(1) == "db://b:select 1")

    a.dispose()                                          // rolls back a's Db + Repo, b untouched
    check(log.contains("close db://a") && !log.contains("close db://b"))
    audited.dispose()
}
```

What to notice:
- `ctx.isolate(DbKey, label)` — same label, same instance; different label, separate slot. Plugins loaded
  from the isolated context resolve and provide in that realm.
- `intercept` only wraps values provided from that context downward; already-provided values are not rewritten.
- `ctx.onDispose` inside `make` is the whole cleanup story: no manual unregister, runs LIFO on restart, unload
  and app shutdown.
