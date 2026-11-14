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
- **JavaScript**: Node.js

---

## Modules

| Module | Purpose | Platforms | Published |
|---|---|---|---|
| `cordis-kernel` | `Scope`, `Fork`, `Context`, `ServiceKey`, `Inject`, events, zones, intercept layers, snapshots. Zero sugar. | All KMP targets | Yes |
| `cordis` | Re-exports the kernel and adds `ServicePlugin` and timer effects (`ctx.after`, `ctx.every`). | All KMP targets | Yes |
| `cordis-loader` | Declarative JSON load-plan loader (`LoadPlan`, `PluginFactoryRegistry`, `Context.load`) with `kotlinx.serialization`. | All KMP targets | Yes |
| `cordis-testing` | Test support module (`ManualClock`, `settle(until = ...)`, `withTestApp`, `RecordedEvents`). | All KMP targets | Yes |
| `cordis-runtime` | Enables and disables catalog plugins by key at runtime (`pluginCatalog { }`, `EnabledPluginsStore`, `PluginRuntimeDefault`) with an observable `StateFlow<List<PluginState>>`. | All KMP targets | Yes |
| `cordis-ktor` | Ktor 3 server integration (`install(Cordis)`, `call.cordis`, `Route.requireService`, `Route.cordisAdmin`). | JVM | Yes |
| `cordis-android` | Android integration, opt-in (`AppForegroundPlugin` / `installAppForeground()`, `AppForegroundKey`, `DataStoreEnabledPluginsStore`). | Android | No |
| `cordis-demo` | Executable walkthrough showing the 6 core scenarios. | JVM app | No |

"All KMP targets" are the platforms listed above.

---

## Install

The published modules are on GitHub Packages. Add its Maven repository to your build (`dependencyResolutionManagement.repositories` in `settings.gradle.kts`, or `repositories` in `build.gradle.kts`):

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/amine2233/kotlin-cordis")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
        }
    }
}
```

GitHub Packages requires authentication even for public packages: use a personal access token with the `read:packages` scope, set as `gpr.user` / `gpr.key` in `~/.gradle/gradle.properties` or as `GITHUB_ACTOR` / `GITHUB_TOKEN`.

```kotlin
dependencies {
    implementation("io.github.amine2233:cordis:<version>")          // pulls in cordis-kernel
    implementation("io.github.amine2233:cordis-loader:<version>")
    implementation("io.github.amine2233:cordis-runtime:<version>")
    implementation("io.github.amine2233:cordis-ktor:<version>")     // JVM only
    testImplementation("io.github.amine2233:cordis-testing:<version>")
}
```

Replace `<version>` with a release from the [GitHub Releases](https://github.com/amine2233/kotlin-cordis/releases) page. `cordis-android` is not published: consume it as a project dependency (`implementation(project(":cordis-android"))`) in a build that has an Android SDK.

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
Any side effect registered inside `Plugin.apply` (event listeners, provided services, sub-plugins, custom disposables, timers) is tracked in the plugin's `Scope`. When unloaded or restarted, all teardowns run in reverse order (LIFO). If a teardown throws, remaining teardowns still execute, and errors are emitted via `TeardownFailed`. A throwing event listener does not stop the other listeners or reach the emitter; it is reported as `ListenerFailed`. A throwing `ctx.launch` or timer body is reported as `TaskFailed`. Neither changes the plugin's status.

### Space: Realms & Zones
- **Realms**: Isolate service instances per tenant (`ctx.isolate(DatabaseKey, label = "tenant-a")`).
- **Zones**: Hierarchical event routing independent of lifetimes (`ctx.zone("tenant")`).

### Declarative Loading
`cordis-loader` resolves each `LoadPlan` entry's `PluginKey` through a `PluginFactoryRegistry` and validates the whole plan before loading any plugin. If unknown keys, duplicate entry names, or undecodable configs exist, the plan loads nothing: entries with errors are reported `LoadOutcome.Invalid` (with their `LoaderError`s) and the rest `LoadOutcome.NotLoaded`. A valid plan reports `LoadOutcome.Loaded` with the plugin's `PluginHandle`. Plans are `@Serializable` and can be parsed with `LoadPlan.fromJson(text)`.

### Runtime Toggles
`cordis-runtime` turns a keyed `PluginCatalog` on and off while the app runs. The store keeps only the user's overrides of the catalog defaults, locked entries cannot be disabled, and `state` follows `snapshotFlow()`, so a dependent plugin shows as `Pending` with its `missing` services while its provider is off. `start`, `setEnabled` and `stop` are serialised by a mutex and can be called from any thread; refusals throw `PluginRuntimeException` (`UnknownPlugin`, `LockedPlugin`, `NotStarted`).

```kotlin
val catalog = pluginCatalog {
    plugin(PluginKey("db"), DatabasePlugin, DatabaseConfig(url), PluginMetadata("Database", "Storage", locked = true))
    plugin(PluginKey("search"), SearchPlugin, PluginMetadata("Search", "Full-text search"))
}
val runtime = PluginRuntimeDefault(ctx, catalog, InMemoryEnabledPluginsStore())
runtime.start()
runtime.setEnabled(PluginKey("search"), false)
runtime.state.collect { states -> render(states) }
```

### Ktor Server
`cordis-ktor` (JVM only) owns a root `Context` and an optional `PluginRuntime` for a Ktor 3 `Application`: `install(Cordis)` starts the runtime before it returns, and `ApplicationStopped` stops it and disposes the context. `requireService` answers 503 (or `whenMissing`) while a service slot is empty and runs the handler with the service once it is filled. `cordisAdmin` mounts `GET plugins` and `PUT plugins/{key}` as JSON, with no content negotiation needed.

```kotlin
fun Application.module() {
    install(Cordis) { runtime = { ctx -> PluginRuntimeDefault(ctx, catalog, InMemoryEnabledPluginsStore()) } }
    routing {
        route("/search", HttpMethod.Get) {
            requireService(SearchKey) { search -> call.respondText(search.query(call.parameters["q"].orEmpty())) }
        }
        authenticate("admin") {                          // the admin routes have no auth of their own
            route("/admin") { cordisAdmin(checkNotNull(application.cordisRuntime)) }
        }
    }
}
```

### Android
`cordis-android` is included in the build only when an Android SDK is found (`sdk.dir` in `local.properties`, or `ANDROID_HOME`). `installAppForeground()` provides `AppForegroundKey` while `ProcessLifecycleOwner` (or the `LifecycleOwner` you pass) is at least `STARTED`, so plugins that require it pause in the background and resume in the foreground. `DataStoreEnabledPluginsStore` persists `PluginRuntimeDefault` overrides in a Preferences `DataStore`, touching only its own `cordis.enabled.*` keys. The module is not published yet: depend on it as a project dependency.

```kotlin
cordis.installAppForeground()
val runtime = PluginRuntimeDefault(cordis, catalog, DataStoreEnabledPluginsStore(context.dataStore))
```

### Deterministic Testing
`cordis-testing` offers `ManualClock` for controlling virtual time without sleeping real wall-clock time, `settle { condition }` for bounded yielding, and `withTestApp { ctx, events -> ... }` which captures lifecycle and failure events in order and guarantees root disposal. Inside `runTest`, `withTestApp(StandardTestDispatcher(testScheduler)) { ctx, events -> ... }` runs the graph on the test scheduler, so `advanceUntilIdle()` settles it deterministically.

---

## Usage Reference

Full cheat sheet: [`skills/kotlin-cordis/SKILL.md`](skills/kotlin-cordis/SKILL.md).
Worked examples: [simple / medium / hard](skills/kotlin-cordis/references/examples.md).
Platform recipes: [Android / Compose](skills/kotlin-cordis/references/android.md) ·
[Ktor](skills/kotlin-cordis/references/ktor.md) · [CLI](skills/kotlin-cordis/references/cli.md).
Runnable samples: [`samples/README.md`](samples/README.md).
- Ktor: a server running a sample plugin catalog with routes gated by `requireService`, the `cordisAdmin` routes, an SSE stream and an admin page (`./gradlew -p samples :ktor:run`, then `http://127.0.0.1:8080/admin`).
- Android: a Compose app whose drawer is built from the `NavEntries` contribution point, with a Settings screen that toggles the plugins, persisted in DataStore.

```kotlin
val app = Context.root()

// Lambda plugin: no object needed for one-off features
app.plugin("metrics", Inject.of(required = listOf(LoggerKey))) { ctx ->
    ctx.on<UserSignedUp> { ctx.require(LoggerKey).info("New registration: ${it.name}") }
    ctx.launch { /* cancelled and joined when the plugin unloads, restarts or fails */ }
}

// Flows: UI binding (e.g. Compose: collectAsState)
val signups: Flow<UserSignedUp> = app.events<UserSignedUp>()
val db: Flow<Database?> = app.serviceFlow(DatabaseKey)   // current value, then each provide/withdraw
val graph: StateFlow<RuntimeSnapshot> = app.snapshotFlow() // live snapshot(): plugins (key, status, missing) and slots
// events() and serviceFlow() complete normally when their context is disposed; snapshotFlow() never completes.

// Await a plugin that may still be waiting for dependencies
val status = app.plugin(Repo).awaitActive()              // Active | Failed | Disposed

// Type-safe load plan (cordis-loader)
val plan = loadPlan {
    plugin(PluginKey("database"), config = DbConfig("postgres://..."), serializer = DbConfig.serializer())
    plugin(PluginKey("analytics"), name = "custom-analytics")
}
val outcomes = app.load(plan, registry)                  // PluginFactoryRegistry; Loaded(handle) | Invalid | NotLoaded
```

`ctx[Key]` is lock-free (copy-on-write registry), so it is safe to call from any thread.

### Claude / skills.sh

The repo is a Claude Code plugin and ships an agent skill: `/plugin marketplace add amine2233/kotlin-cordis` or
`npx skills add amine2233/kotlin-cordis`. Details: [`docs/CLAUDE-INTEGRATION.md`](docs/CLAUDE-INTEGRATION.md).

---

## Build & Test

```bash
# Build all KMP modules
./gradlew assemble

# Run the tests of every target the host can execute (Apple targets only on macOS, Linux native only on Linux)
./gradlew check

# Run the 6-scenario demo
./gradlew :cordis-demo:run
```

## CI/CD

CI and releases come from [kotlin-ci-shared](https://github.com/amine2233/kotlin-ci-shared).
All logic lives in `mise.toml`:

- `mise run test` (`./gradlew allTests`) / `mise run lint` — what the `CI` workflow runs on pull requests.
  CI runs on Linux (`ubuntu-latest`) only, covering JVM, Linux native and JS; the macOS/iOS targets
  are verified locally on a Mac (`./gradlew macosArm64Test` and a build that compiles the Apple targets).
- `mise run build_documentations` (`./gradlew dokkaGenerate`) writes the aggregated Dokka API docs of the six
  published modules to `build/dokka/html/`.
- `mise run test-coverage` (`./gradlew allTests koverHtmlReport koverXmlReport`) runs all tests and writes the
  aggregated Kover coverage reports (JVM tests only) to `build/reports/kover/`. CI's Test job runs `test-coverage`
  on Linux in place of `test`; it only reports (no thresholds) and uploads the report as the `reports` artifact.
- **Actions → Release → Run workflow** runs semantic-release (or automatically
  on every push to `main` when the repo variable `AUTO_RELEASE=true` is set): it tags
  `vX.Y.Z`, updates `CHANGELOG.md`, publishes `io.github.amine2233:cordis-kernel`, `cordis`,
  `cordis-loader`, `cordis-testing`, `cordis-runtime` and `cordis-ktor` to GitHub Packages and creates the GitHub release
  with the jars attached. `cordis-android` is not published.
- `mise run release --dry-run` previews the next version locally.
