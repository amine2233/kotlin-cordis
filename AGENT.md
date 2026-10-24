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

## Conventions
- Kotlin Multiplatform (JVM, macOS Arm64/X64, iOS Arm64/X64/Simulator, Linux Arm64/X64, JS/Node.js).
- Concurrency: All graph mutations run on a serialized single-threaded CoroutineDispatcher (`Context.root()`).
- Modules:
  - `cordis-kernel`: Zero-dependency kernel (pure Kotlin + kotlinx-coroutines).
  - `cordis`: Public convenience API, `ServicePlugin`, timer effects (`after`, `every`).
  - `cordis-loader`: Declarative JSON loading with `kotlinx-serialization`.
  - `cordis-testing`: `ManualClock`, `settle`, `withTestApp`.
  - `cordis-demo`: 6-scenario walkthrough.
- Tests: Pure Kotlin test suite (`kotlin.test` + `kotlinx.coroutines.test.runTest`).
