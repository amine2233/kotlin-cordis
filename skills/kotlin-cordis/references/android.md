# Android / Compose

kotlin-cordis has no Android target; an Android module consumes the **JVM variant** (Gradle resolves it automatically from the KMP metadata). Add the GitHub Packages repo + `implementation("io.github.amine2233:cordis:<version>")`.

## One root per process

Own the graph in `Application`; plugins hold app-lifetime services (DB, network, auth). Activities/ViewModels only read.

```kotlin
// Cordis `Context` clashes with android.content.Context: import one under an alias (e.g. `import cordis.kernel.Context as CordisContext`).
class App : Application() {
    val cordis: Context = Context.root()

    override fun onCreate() {
        super.onCreate()
        runBlocking(Dispatchers.Default) {                 // brief: just registers plugins, no I/O in apply()
            cordis.plugin(Logging)
            cordis.plugin(DbPlugin, DbConfig(filesDir.resolve("app.db").path))
            cordis.plugin(SyncPlugin)                      // Inject.of(required = listOf(DbKey, AuthKey))
        }
    }
}
```
Prefer `lifecycleScope`-free startup: keep `apply` fast and launch I/O via `ctx.launch { }` so it never blocks `onCreate`.
`Application.onTerminate` is never called on devices, so do not rely on `dispose()` there — plugins that need teardown should use `ctx.onDispose` for process-safe cleanup (flush) and accept process death otherwise.

## Compose: services as state

```kotlin
@Composable
fun Home(cordis: Context) {
    val db by cordis.serviceFlow(DbKey).collectAsStateWithLifecycle(initialValue = cordis[DbKey])
    if (db == null) CircularProgressIndicator() else Content(db!!)       // db!! – smart-cast not allowed on delegate
}
```
Events as UI effects:
```kotlin
LaunchedEffect(Unit) {
    cordis.events<UserSignedOut>().collect { navController.navigate("login") }
}
```
Plugin status in a screen: `handle.fork.statusFlow.collectAsStateWithLifecycle()`.

## ViewModel

```kotlin
class SyncViewModel(private val cordis: Context) : ViewModel() {
    val syncService: StateFlow<SyncService?> = cordis.serviceFlow(SyncKey)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), cordis[SyncKey])
    fun syncNow() = viewModelScope.launch { cordis.require(SyncKey).run() }
}
```

## Per-screen / per-session plugins (Time axis)

Load a feature plugin when a session starts and `dispose()` its handle on logout; everything it registered
(services, listeners, jobs) is rolled back, and plugins that injected its services drop to `Pending` by themselves.
```kotlin
private var session: PluginHandle<UserId>? = null
suspend fun login(id: UserId) { session = cordis.plugin(SessionPlugin, id) }
suspend fun logout() { session?.dispose(); session = null }
```

## Pitfalls
- Do not capture an Activity/Fragment `Context` in a plugin (leak). Pass `applicationContext` through the plugin config or a service.
- `ctx[Key]` is safe on the main thread (lock-free); `provide`/`plugin`/`emit` are suspend and hop to the graph dispatcher.
- Use `collectAsStateWithLifecycle` (not `collectAsState`) so collection stops in the background.
