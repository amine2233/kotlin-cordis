# kotlin-cordis

> **Unofficial** Kotlin Multiplatform (KMP) port of the [Cordis](https://github.com/cordiverse/cordis) principle
> (the plugin kernel behind Koishi), inspired by [swift-cordis](https://github.com/amine2233/swift-cordis). Not affiliated with the Cordis authors.

Cordis' core idea: **everything is a plugin**, and plugins compose along two orthogonal axes plus reactivity.

| Axis | Question | Mechanism |
|---|---|---|
| **Time** | What happens when a plugin loads / reloads / unloads? | `Scope`: every side effect registered through a `Context` is undone automatically, LIFO |
| **Space** | Which instance of a service does a plugin see? | `Slot = (service, realm)` and `ctx.isolate(Key, label)` |
| **Reactivity** | What if a dependency appears / changes / disappears? | `Fork`: dependents go pending → active, restart, or fall back to pending |

---

## Supported KMP Platforms

`kotlin-cordis` is designed from the ground up for Kotlin Multiplatform:
- **JVM** (Java 17, 21+)
- **Apple Native**: `macosArm64`, `macosX64`, `iosArm64`, `iosSimulatorArm64`, `iosX64`
- **Linux Native**: `linuxX64`, `linuxArm64`
- **JavaScript**: Node.js & modern browsers

---

## Modules

- `cordis-kernel`: `Scope`, `Fork`, `Registry`, `Context`, `ServiceKey`, `Inject`, events, zones, intercept layers, snapshots. Zero sugar.
- `cordis`: Re-exports the kernel and adds `ServicePlugin` and timer effects (`ctx.after`, `ctx.every`).
- `cordis-loader`: Declarative JSON load-plan loader (`LoadPlan`, `PluginRegistry`, `Context.load`) with `kotlinx.serialization`.
- `cordis-testing`: Test support module (`ManualClock`, `settle(until = ...)`, `withTestApp`, `RecordedEvents`).
- `cordis-demo`: Executable walkthrough showing the 6 core scenarios.

---

## Quickstart

```kotlin
import cordis.*
import cordis.kernel.*

// 1. Declare a typed service key
object GreetingKey : ServiceKey<String>

// 2. Define a service provider plugin
object GreetingPlugin : ServicePlugin<GreetingKey, String, String> {
    override val key = GreetingKey
    override suspend fun make(ctx: Context, config: String): String = "hello $config"
}

// 3. Define a consumer plugin with dependencies
object Printer : Plugin<Unit> {
    override val inject = Inject.of(required = listOf(GreetingKey))
    override suspend fun apply(ctx: Context, config: Unit) {
        println(ctx.require(GreetingKey))
    }
}

// 4. Run the reactive graph
suspend fun main() {
    val app = Context.root()
    app.plugin(Printer)                                    // pending: no greeting yet
    val greeting = app.plugin(GreetingPlugin, "world")     // Printer starts: "hello world"
    greeting.update("kotlin")                             // hot swap: Printer restarts: "hello kotlin"
    greeting.dispose()                                    // Printer goes back to pending
}
```

Run the interactive demo walkthrough:
```bash
./gradlew :cordis-demo:run
```

---

## Cordis Concepts → Kotlin

| Cordis JS | Swift | Kotlin (kotlin-cordis) |
|---|---|---|
| `ctx.plugin(fn, config)` | `await ctx.plugin(P.self, config)` | `ctx.plugin(PluginObject, config)` → `PluginHandle<C>` |
| `inject: [...]` | `static let inject = Inject(required:, optional:)` | `override val inject = Inject.of(required, optional)` |
| `ctx.provide('db')` | `try await ctx.provide(Key.self, value)` | `ctx.provide(Key, value)` |
| `ctx.db` | `extension Context { var db: ... { self[DBKey.self] } }` | `val Context.db: DB? get() = this[DBKey]` |
| `ctx.on` / `ctx.emit` | `ctx.on(E.self) { }` / `await ctx.emit(E())` | `ctx.on<E> { }` / `ctx.emit(E())` |
| `ctx.effect` | `try await ctx.effect { …; return { teardown } }` | `ctx.effect { …; return@effect { teardown } }` |
| `ctx.on('dispose')` | `ctx.onDispose { }` | `ctx.onDispose { }` |
| `ctx.isolate('db')` | `ctx.isolate(Key.self, label:)` | `ctx.isolate(Key, label)` |
| `ctx.zone('zone')` | `ctx.zone("tenant")` | `ctx.zone("tenant")` |
| `ctx.intercept` | `ctx.intercept(Key.self) { ... }` | `ctx.intercept(Key) { ... }` |
| `fork.update` / `fork.dispose` | `handle.update(config)` / `handle.dispose()` | `handle.update(config)` / `handle.dispose()` |

---

## Architecture Highlights

### Deterministic Reactive Mutations
All graph state changes (`provide`, `withdraw`, `plugin`, `update`, `dispose`) run on a serialized coroutine dispatcher (`Dispatchers.Default.limitedParallelism(1)` by default). Re-entrancy during dependency cascade reconciliations is handled with a non-blocking iterative loop.

### Time: LIFO Scope Teardowns
Any side effect registered inside `Plugin.apply` (event listeners, provided services, sub-plugins, custom disposables, timers) is tracked in the plugin's `Scope`. When unloaded or restarted, all teardowns run in reverse order (LIFO). If a teardown throws, remaining teardowns still execute, and errors are emitted via `TeardownFailed`.

### Space: Realms & Zones
- **Realms**: Isolate service instances per tenant (`ctx.isolate(DatabaseKey, label = "tenant-a")`).
- **Zones**: Hierarchical event routing independent of lifetimes (`ctx.zone("tenant")`).

### Declarative Loading
`cordis-loader` validates a `LoadPlan` before loading any plugin. If unknown keys, duplicate entry names, or undecodable configs exist, validation rejects the entire plan and loads nothing.

### Deterministic Testing
`cordis-testing` offers `ManualClock` for controlling virtual time without sleeping real wall-clock time, `settle { condition }` for bounded yielding, and `withTestApp { ctx, events -> ... }` which captures lifecycle and failure events in order and guarantees root disposal.

---

## Build & Test

```bash
# Build all KMP modules
./gradlew assemble

# Run tests across all platforms (JVM, macOS Native, iOS Simulator, JS)
./gradlew check

# Run the 6-scenario demo
./gradlew :cordis-demo:run
```
