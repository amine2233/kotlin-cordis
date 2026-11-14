# kotlin-cordis

Kotlin Multiplatform port of the Cordis principle (the "everything is a plugin" architecture
with spatiotemporal composability), ported from `swift-cordis`.

## Architecture & Internals
- Architectural decisions: `docs/ARCHITECTURE.md`
- Core principle: everything is a plugin composing across Time (scopes) and Space (realms), driven by Reactivity (forks).

## Build & Test Commands
- Full project check (the tests of every target the host can execute): `./gradlew check`
- Fast JVM tests only: `./gradlew :cordis-kernel:jvmTest :cordis:jvmTest :cordis-testing:jvmTest :cordis-loader:jvmTest :cordis-runtime:jvmTest :cordis-ktor:jvmTest`
- Native macOS tests: `./gradlew :cordis-kernel:macosArm64Test :cordis:macosArm64Test :cordis-testing:macosArm64Test :cordis-loader:macosArm64Test :cordis-runtime:macosArm64Test`
- Interactive Demo: `./gradlew :cordis-demo:run`
- Public API check: `./gradlew apiCheck` compares the public API against the committed dumps in each module's `api/` directory (`*.api` for JVM, `*.klib.api` for native and JS). It runs as part of `./gradlew check`.
- Public API dump: `./gradlew apiDump` after any intentional public API change. Run it on macOS so every klib target is dumped, and commit the updated `api/` files in the same PR as the change.
- `cordis-demo` is excluded from binary-compatibility validation and has no API dump.
- Android module: `cordis-android` is included only when an Android SDK is found, through `sdk.dir` in the root `local.properties` (git-ignored) or the `ANDROID_HOME` / `ANDROID_SDK_ROOT` environment variable. Its tests: `./gradlew :cordis-android:testDebugUnitTest`. When the module is included, its `jvmTest` task depends on `testDebugUnitTest`, so the gate's `jvmTest` runs them too.

## CI
- CI (GitHub Actions via `kotlin-ci-shared`) runs `mise run lint` and `mise run test` (`./gradlew allTests`) on `ubuntu-latest` only, covering JVM, Linux native and JS.
- A second CI job, `samples-ktor`, runs `./gradlew -p samples :ktor:check` on `ubuntu-latest`: only the Ktor sample's tasks, so it works with or without the runner's Android SDK. A third job, `samples-android`, sets up an Android SDK (`android-actions/setup-android`) and runs `./gradlew -p samples :android:assembleDebug :android:testDebugUnitTest`. The rest of the samples build (`shared`) is not run in CI.
- The macOS and iOS targets are not built or tested in CI. Verify them locally on a Mac with `./gradlew macosArm64Test` (also run by `factory/checks.sh`) and a build that compiles the Apple targets (e.g. `./gradlew build -x check`).

## Conventions
- Kotlin Multiplatform (JVM, macOS Arm64/X64, iOS Arm64/X64/Simulator, Linux Arm64/X64, JS/Node.js).
- Build logic: the shared KMP targets, JVM 17 toolchain/`jvmTarget` and `commonTest` dependencies of the five library modules live in the `cordis.kmp-library` convention plugin, `build-logic/convention/KmpLibraryConventionPlugin.kt` (an included build). Add or change a target there, not in the module build files. `cordis-demo` is a plain Kotlin/JVM app that pins JVM 17 itself. `cordis-ktor` does not apply `cordis.kmp-library`: its own build file declares only the `jvm()` target (Ktor server is JVM-only here) with the same explicit API and JVM 17 settings, and the root build skips its klib API tasks. `cordis-android` is opt-in (built only with an Android SDK) and uses AGP + Kotlin Android (`com.android.library`, `org.jetbrains.kotlin.android`) instead of `cordis.kmp-library`, with explicit API mode and JVM 17. AGP goes on the root build classpath (same classloader as KGP) and Google's Maven repository is added, filtered to Android groups, only when the module is included. It is not published, and its `apiDump` / `apiCheck` need an SDK.
- Visibility: the library modules build in strict explicit API mode (`explicitApi()`, set in the `cordis.kmp-library` convention plugin), so every public declaration needs an explicit `public` modifier and, where required, an explicit type. Kernel internals (`Registry`, `Context.registry`, the `Fork` constructor) are `internal`. `@InternalCordisApi` (`cordis.kernel`) is the opt-in marker for anything that has to stay visible across modules but is not public API.
- Concurrency: `provide`/`update`/`replaceValue`/`contribute`/`plugin`/`effect`/`emit`/`dispose` and `PluginHandle.update`/`dispose` run on the graph's serialized single-threaded CoroutineDispatcher (`Context.root()`); `on`/`onDispose`/`launch`/`contributions`, the contribution `Disposable`, `Disposable` and `Scope` bookkeeping are lock-guarded and callable from any thread; `zone`/`isolate` are not yet thread-safe (see `docs/ARCHITECTURE.md`). Plugin lifecycle operations (`plugin`/`provide`/`dispose`, `PluginHandle.update`/`dispose`) run in a graph-owned job: cancelling the caller only stops its wait, and nested calls run inline.
- Modules:
  - `cordis-kernel`: Zero-dependency kernel (pure Kotlin + kotlinx-coroutines).
  - `cordis`: Public convenience API, `ServicePlugin`, timer effects (`after`, `every`).
  - `cordis-loader`: Declarative JSON loading with `kotlinx-serialization`.
  - `cordis-testing`: `ManualClock`, `settle`, `withTestApp`.
  - `cordis-runtime`: `PluginCatalog`, `EnabledPluginsStore`, `PluginRuntimeDefault`: enable/disable catalog plugins by key with observable `PluginState`. `start`/`setEnabled`/`stop` are serialised by one `Mutex` and callable from any thread; `state` is readable from any thread.
  - `cordis-ktor`: Ktor 3 server integration, JVM only: `install(Cordis)` owns the root context and an optional runtime, `Route.requireService` gates a handler on a service slot, `Route.cordisAdmin` mounts unauthenticated JSON admin routes.
  - `cordis-android`: opt-in Android library (needs an Android SDK, not published): `AppForegroundPlugin` / `installAppForeground()` provide `AppForegroundKey` while a `LifecycleOwner` (by default `ProcessLifecycleOwner`) is at least `STARTED`; `DataStoreEnabledPluginsStore` is an `EnabledPluginsStore` on a Preferences `DataStore`.
  - `cordis-demo`: 6-scenario walkthrough.
- Samples: `samples/` is a separate Gradle build (not part of the root build; CI runs its `ktor` module in the `samples-ktor` job and its `android` module in the `samples-android` job) that consumes the library through `includeBuild("..")`; its `shared` module holds the sample feature plugins and `sampleCatalog()`, its `ktor` module is a Ktor server running that catalog with gated feature routes, the admin routes, an SSE stream and an admin page (`./gradlew -p samples :ktor:run`, then `http://127.0.0.1:8080/admin`), and its `android` module, included only when an Android SDK is found, is a Compose app with a drawer built from `NavEntries`, a Settings screen to toggle the plugins (persisted in DataStore) and Notes/Weather screens (`./gradlew -p samples :android:assembleDebug`, `:android:installDebug`, `:android:testDebugUnitTest`), see `samples/README.md`. Its tests run with `./gradlew -p samples check`, which `factory/checks.sh` runs too.
- Tests: Pure Kotlin test suite (`kotlin.test` + `kotlinx.coroutines.test.runTest`). Every root a test creates must be disposed, even when the test fails: kernel tests create roots through `withRoot` (`cordis-kernel/src/commonTest/kotlin/cordis/kernel/TestRoot.kt`), other modules' tests through `withTestApp`.
