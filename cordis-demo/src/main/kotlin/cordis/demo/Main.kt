package cordis.demo

import cordis.ServicePlugin
import cordis.kernel.Context
import cordis.kernel.CordisEvent
import cordis.kernel.Inject
import cordis.kernel.Plugin
import cordis.kernel.PluginStatusChanged
import cordis.kernel.ServiceKey
import kotlinx.coroutines.runBlocking

// MARK: - Service contracts (the "public API" of the graph)

interface Logger {
    fun info(message: String)
}

interface Database {
    val label: String

    fun save(
        key: String,
        value: String,
    )

    fun load(key: String): String?
}

class UserRepository(
    private val db: Database,
) {
    fun create(name: String) {
        db.save("user:$name", name)
    }

    fun find(name: String): String? = db.load("user:$name")

    val backend: String
        get() = db.label
}

object LoggerKey : ServiceKey<Logger>

object DatabaseKey : ServiceKey<Database>

object UserRepositoryKey : ServiceKey<UserRepository>

// Cordis-style mixins: ctx.logger, ctx.database, ctx.users
val Context.logger: Logger?
    get() = this[LoggerKey]

val Context.database: Database?
    get() = this[DatabaseKey]

val Context.users: UserRepository?
    get() = this[UserRepositoryKey]

// MARK: - Plugins (implementations)

class ConsoleLogger(
    private val prefix: String,
) : Logger {
    override fun info(message: String) {
        println("$prefix $message")
    }
}

object ConsoleLoggerPlugin : ServicePlugin<LoggerKey, Logger, String> {
    override val key = LoggerKey

    override suspend fun make(
        ctx: Context,
        config: String,
    ): Logger = ConsoleLogger(prefix = config)
}

class InMemoryDatabase(
    override val label: String,
) : Database {
    private val storage = mutableMapOf<String, String>()

    override fun save(
        key: String,
        value: String,
    ) {
        storage[key] = value
    }

    override fun load(key: String): String? = storage[key]
}

/**
 * A DB driver. Swapping it at runtime restarts everything that depends on it.
 */
object InMemoryDatabasePlugin : ServicePlugin<DatabaseKey, Database, String> {
    override val key = DatabaseKey
    override val inject = Inject.of(optional = listOf(LoggerKey))

    override suspend fun make(
        ctx: Context,
        config: String,
    ): Database {
        ctx.logger?.info("🔌 DB '$config' connected")
        ctx.onDispose { ctx.logger?.info("🔌 DB '$config' closed") }
        return InMemoryDatabase(label = config)
    }
}

/**
 * Domain service built on top of another service.
 */
object UserRepositoryPlugin : Plugin<Unit> {
    override val inject =
        Inject.of(
            required = listOf(DatabaseKey),
            optional = listOf(LoggerKey),
        )

    override suspend fun apply(
        ctx: Context,
        config: Unit,
    ) {
        val db = ctx.require(DatabaseKey)
        ctx.provide(UserRepositoryKey, UserRepository(db = db))
        ctx.logger?.info("👤 UserRepository ready on '${db.label}'")
    }
}

data class UserSignedUp(
    val name: String,
) : CordisEvent

/**
 * A feature: reacts to events, uses services, cleans up by itself.
 */
object WelcomeFeature : Plugin<Unit> {
    override val inject =
        Inject.of(
            required = listOf(UserRepositoryKey),
            optional = listOf(LoggerKey),
        )

    override suspend fun apply(
        ctx: Context,
        config: Unit,
    ) {
        val users = ctx.require(UserRepositoryKey)
        ctx.on<UserSignedUp> { event ->
            users.create(event.name)
            ctx.logger?.info("🎉 Welcome ${event.name} (stored in '${users.backend}')")
        }
    }
}

// MARK: - Composition root

suspend fun runDemo() {
    val app = Context.root()

    app.on<PluginStatusChanged> { e ->
        println("   · ${e.plugin} -> ${e.status}")
    }

    println("\n1) Load features BEFORE their dependencies — they wait (pending)")
    app.plugin(WelcomeFeature)
    app.plugin(UserRepositoryPlugin)
    app.plugin(ConsoleLoggerPlugin, "[app]")

    println("\n2) Plug a database — the whole chain comes alive")
    val db = app.plugin(InMemoryDatabasePlugin, "sqlite")
    app.emit(UserSignedUp(name = "Amine"))

    println("\n3) Hot-swap the DB driver — dependents restart automatically")
    db.update("postgres")
    app.emit(UserSignedUp(name = "Michel"))

    println("\n4) Unplug the DB — dependents go back to pending, listeners removed")
    db.dispose()
    app.emit(UserSignedUp(name = "Ghost")) // nobody listening anymore

    println("\n5) Space: two isolated tenants, each with its own DB, same plugins")
    for (tenant in listOf("acme", "globex")) {
        val scoped =
            app
                .isolate(DatabaseKey, label = tenant)
                .isolate(UserRepositoryKey, label = tenant)
        scoped.plugin(InMemoryDatabasePlugin, "db-$tenant")
        scoped.plugin(UserRepositoryPlugin)
    }
    val acme = app.isolate(UserRepositoryKey, label = "acme")
    acme.users?.create("wile")
    println("   acme sees wile:   ${acme.users?.find("wile") ?: "null"}")
    println("   globex sees wile: ${app.isolate(UserRepositoryKey, label = "globex").users?.find("wile") ?: "null"}")

    println("\n6) Shut down the app — everything is rolled back in reverse order")
    app.dispose()
}

fun main() =
    runBlocking {
        runDemo()
    }
