# Cordis samples

Example code built on the library. `samples/` is its own Gradle build (`cordis-samples`), not part of the
root build:

- The library build and the Linux CI must never need an Android SDK, while the samples target Android too.
  Keeping them in a separate build means the root `settings.gradle.kts` never references them.
- The samples consume the library through `includeBuild("..")`: the `io.github.amine2233:cordis*`
  dependencies are substituted with the root projects, so a change in the library is picked up
  without publishing anything.
- The build has no Gradle wrapper of its own; run it with the root wrapper (`./gradlew -p samples ...`).

## Android target

The samples build looks for an Android SDK the same way the root build does: `sdk.dir` in
`samples/local.properties` (git-ignored), `ANDROID_HOME` or `ANDROID_SDK_ROOT`. Only when one is found does it
put the Android Gradle plugin on its classpath, add Google's Maven repository and give `shared` an Android
target. Without an SDK, `shared` builds and tests on the JVM alone.

## `shared`

`samples/shared` (package `cordis.samples.shared`) holds the features the sample apps share:

| Catalog key | Plugin | Provides | Requires |
| --- | --- | --- | --- |
| `logger` (locked) | `LoggerPlugin` | `LoggerKey`: records log lines | - |
| `database` | `DatabasePlugin` | `DatabaseKey`: in-memory key/value store | - |
| `notes` | `NotesPlugin` | `NotesKey`: add and list notes, stored in the database | `DatabaseKey` |
| `weather` | `WeatherPlugin` | `WeatherKey`: a `StateFlow` reading, refreshed every `WeatherConfig.interval` | - |
| `analytics` | `AnalyticsPlugin` | `AnalyticsKey`: tracks events and logs each one | `LoggerKey` |

- `NavEntries` is a contribution point: each plugin contributes one `NavEntry` whose `id` is its catalog key.
  The entry is withdrawn when the plugin stops, so the list always matches the running features.
- `SampleKeys` holds the five `PluginKey`s.
- `sampleCatalog(clock)` is the `PluginCatalog` of the five plugins, in the order above, all enabled by
  default. `clock` drives the weather timer; tests pass a `ManualClock`.

## Using it from an app

The Android and Ktor sample apps are meant to:

1. build a `PluginRuntimeDefault(context, sampleCatalog(), store)` and `start()` it;
2. render `context.contributions(NavEntries)` as their navigation, and the runtime's `state` as a
   settings screen where every feature but the locked `logger` can be turned off.

## Tests

From the repo root:

```sh
./gradlew -p samples check
```

`factory/checks.sh` runs the same command.
