# kotlin-cordis

Kotlin Multiplatform port of the Cordis principle (the "everything is a plugin" architecture
with spatiotemporal composability), ported from `swift-cordis`.

## Architecture & Internals
- Architectural decisions: `docs/ARCHITECTURE.md`
- Core principle: everything is a plugin composing across Time (scopes) and Space (realms), driven by Reactivity (forks).

## Build & Test Commands
- Full project check (all platforms): `./gradlew check`
- Fast JVM tests only: `./gradlew :cordis-kernel:jvmTest :cordis:jvmTest :cordis-testing:jvmTest :cordis-loader:jvmTest`
- Native macOS tests: `./gradlew :cordis-kernel:macosArm64Test :cordis:macosArm64Test :cordis-testing:macosArm64Test :cordis-loader:macosArm64Test`
- Interactive Demo: `./gradlew :cordis-demo:run`
- Public API check: `./gradlew apiCheck` compares the public API against the committed dumps in each module's `api/` directory (`*.api` for JVM, `*.klib.api` for native and JS). It runs as part of `./gradlew check`.
- Public API dump: `./gradlew apiDump` after any intentional public API change. Run it on macOS so every klib target is dumped, and commit the updated `api/` files in the same PR as the change.
- `cordis-demo` is excluded from binary-compatibility validation and has no API dump.

## CI
- CI (GitHub Actions via `kotlin-ci-shared`) runs `mise run lint` and `mise run test` (`./gradlew allTests`) on `ubuntu-latest` only, covering JVM, Linux native and JS.
- The macOS and iOS targets are not built or tested in CI. Verify them locally on a Mac with `./gradlew macosArm64Test` (also run by `factory/checks.sh`) and a build that compiles the Apple targets (e.g. `./gradlew build -x check`).

## Conventions
- Kotlin Multiplatform (JVM, macOS Arm64/X64, iOS Arm64/X64/Simulator, Linux Arm64/X64, JS/Node.js).
- Concurrency: `provide`/`update`/`replaceValue`/`plugin`/`effect`/`emit`/`dispose` and `PluginHandle.update`/`dispose` run on the graph's serialized single-threaded CoroutineDispatcher (`Context.root()`); `on`/`onDispose`/`launch`, `Disposable` and `Scope` bookkeeping are lock-guarded and callable from any thread; `zone`/`isolate` are not yet thread-safe (see `docs/ARCHITECTURE.md`).
- Modules:
  - `cordis-kernel`: Zero-dependency kernel (pure Kotlin + kotlinx-coroutines).
  - `cordis`: Public convenience API, `ServicePlugin`, timer effects (`after`, `every`).
  - `cordis-loader`: Declarative JSON loading with `kotlinx-serialization`.
  - `cordis-testing`: `ManualClock`, `settle`, `withTestApp`.
  - `cordis-demo`: 6-scenario walkthrough.
- Tests: Pure Kotlin test suite (`kotlin.test` + `kotlinx.coroutines.test.runTest`).
