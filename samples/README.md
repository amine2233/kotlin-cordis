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
put the Android Gradle plugin on its classpath, add Google's Maven repository, give `shared` an Android
target and include the `android` app module. Without an SDK, the build has only `shared` and `ktor`, and
`shared` builds and tests on the JVM alone.

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

## `ktor`

`samples/ktor` (package `cordis.samples.ktor`) is a Ktor 3 server (CIO engine) that runs a `PluginRuntimeDefault`
over `sampleCatalog()` through `install(Cordis)`. All of its setup is `Application.sampleModule(storeFile)`.

Run it from the repo root, then open <http://127.0.0.1:8080/admin>:

```sh
./gradlew -p samples :ktor:run
```

| Route | What it does |
| --- | --- |
| `GET /notes` | `200` with a JSON array of the notes; `503` while `notes` or `database` is off |
| `POST /notes` | `{"text": "..."}` adds a note: `201`; `400` for a malformed body or blank text; `503` while `notes` is off |
| `GET /weather` | `200` with `{"update": ..., "temperatureCelsius": ...}`; `503` while `weather` is off |
| `GET /admin/plugins` | every plugin's state, in catalog order (`cordisAdmin`) |
| `PUT /admin/plugins/{key}` | `{"enabled": <boolean>}` toggles a plugin: `400` / `404` / `409` (locked `logger`) / `503` |
| `GET /admin/plugins/events` | server-sent events: the current plugin list on connect, then one event per state change |
| `GET /admin` | a static HTML page that shows the plugins from the event stream, with a toggle per plugin |

The toggles are saved in a JSON file mapping plugin key to boolean, e.g. `{"weather":false}`, so they survive a
restart. It is `enabled-plugins.json` in the working directory (`samples/ktor/enabled-plugins.json` under
`:ktor:run`, git-ignored), or the path in the `CORDIS_SAMPLE_STORE` environment variable when it is set.

> **Local use only.** The admin routes and page have no authentication, authorization or CSRF protection: anyone
> who can reach the server can turn features off. The server binds to `127.0.0.1` for that reason; do not expose it.

Its tests alone:

```sh
./gradlew -p samples :ktor:check
```

## `android`

`samples/android` (package and application id `cordis.samples.android`) is a single-activity Jetpack Compose
(Material 3) app, "Cordis Sample". It needs an Android SDK: the module is only part of the build when one is
found (see [Android target](#android-target)).

- `SampleApplication` owns the root `Context` and a `PluginRuntimeDefault` over `sampleCatalog()`, and starts it
  on launch. The toggles are saved by `DataStoreEnabledPluginsStore` in a Preferences DataStore, so they survive
  a restart.
- The navigation drawer lists `context.contributions(NavEntries)` live, followed by Settings, which is also the
  first screen and where the app falls back when the selected feature is turned off.
- Settings lists every catalog plugin with its description, its status (Active, Waiting for the missing
  services, Starting, Failed or Disabled) and a switch to turn it on or off. The locked `logger` switch is
  disabled.
- Notes lists the notes and adds new ones; Weather shows the current temperature in °C and the update number.
  `logger`, `database` and `analytics` open a shared info screen with the plugin's catalog description.

Build it, install it on a running emulator or connected device, and run its JVM unit tests:

```sh
./gradlew -p samples :android:assembleDebug
./gradlew -p samples :android:installDebug
./gradlew -p samples :android:testDebugUnitTest
```

## Tests

From the repo root:

```sh
./gradlew -p samples check
```

`factory/checks.sh` runs the same command.
