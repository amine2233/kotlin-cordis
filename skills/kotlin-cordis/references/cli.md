# CLI project

A CLI is a short-lived graph: build it, run the command, dispose it. Plugins give you ordered setup and guaranteed teardown even on Ctrl-C.

## Skeleton (JVM or Kotlin/Native)

```kotlin
fun main(args: Array<String>) = runBlocking {
    val app = Context.root()
    val shutdown = Thread { runBlocking { app.dispose() } }     // JVM: Ctrl-C / SIGTERM
    Runtime.getRuntime().addShutdownHook(shutdown)
    try {
        app.plugin(Logging)
        app.plugin(ConfigPlugin, args.configPath())             // provides ConfigKey
        val cmd = app.plugin(RunCommand, args.toCommand())      // Inject.of(required = listOf(ConfigKey))
        when (val s = cmd.awaitActive()) {
            Fork.Status.Active -> {}
            is Fork.Status.Failed -> { System.err.println("error: ${s.reason}"); exitProcess(1) }
            else -> exitProcess(2)
        }
    } finally {
        app.dispose()
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
    }
}
```
On Kotlin/Native replace the shutdown hook with a signal handler (`platform.posix.signal`) that cancels the main job.

## Command as a plugin

```kotlin
class RunCommand(private val opts: Options) : Plugin<Unit> {
    override val inject = Inject.of(required = listOf(ConfigKey, ClientKey))
    override suspend fun apply(ctx: Context, config: Unit) {
        val client = ctx.require(ClientKey)
        ctx.launch { client.sync(ctx.require(ConfigKey).endpoint) }      // job joined on dispose
    }
}
```
For a command that must finish before exit, collect an event instead of racing `awaitActive`:
```kotlin
val done = CompletableDeferred<Unit>()
app.on<SyncFinished> { done.complete(Unit) }
app.plugin(RunCommand(opts))
done.await()
```

## Config-driven plugin set (cordis-loader)

Let a JSON file choose which plugins run:
```jsonc
// plugins.json  → LoadPlan.entries
[{ "key": "http", "config": "{\"timeoutMs\":5000}" }, { "key": "export" }]
```
```kotlin
val registry = PluginRegistry().apply {
    register(HttpPlugin, key = "http", serializer = HttpConfig.serializer())
    register(ExportPlugin, key = "export")
}
val plan = Json.decodeFromString<LoadPlan>(File("plugins.json").readText())   // needs @Serializable on LoadPlan, else build entries by hand
val errors = plan.validate(against = registry)
if (errors.isNotEmpty()) { errors.forEach { System.err.println(it.message) }; exitProcess(64) }
app.load(plan, registry).filter { it.status is Fork.Status.Failed }.forEach { /* report */ }
```
`LoadPlan` is not annotated `@Serializable` in this repo: either parse your own file shape and map to `LoadPlan.Entry`, or use the `loadPlan { }` builder.

## Pitfalls
- Exit codes: map `Fork.Status.Failed` / `LoaderError` to non-zero explicitly; plugin failures do not throw out of `ctx.plugin`.
- Output ordering: log through a service plugin (`LoggerKey`) rather than `println` inside many plugins, so tests can capture it.
- Tests: `withTestApp` + a fake `ConfigKey` provider; no process, no real clock (`ManualClock` for timers).
