# Setting up Android Studio with mise

`mise.toml` pins the JDK (`java = "temurin-17"`) and every module builds with `jvmToolchain(17)`.
In a terminal, mise puts that JDK on `PATH` and `JAVA_HOME`, so `./gradlew` finds it. Android Studio is
usually launched from the desktop, doesn't get those variables, and Gradle doesn't search mise's install
directory. Sync then fails with:

```
Cannot find a Java installation on your machine (...) matching: {languageVersion=17, ...}.
Toolchain download repositories have not been configured.
```

## 1. Install the tools

```bash
mise install
mise where java   # e.g. ~/.local/share/mise/installs/java/temurin-17.0.21+9
```

## 2. Point Gradle at the mise JDK

Add this to your **user-level** `~/.gradle/gradle.properties`, not to the repo's `gradle.properties`, because the path
is different on each machine:

```properties
org.gradle.java.installations.paths=/home/<you>/.local/share/mise/installs/java/temurin-17
```

Use the `temurin-17` symlink instead of the full version directory, so the setting survives 17.x updates
through mise. On macOS the path is `~/.local/share/mise/installs/java/temurin-17` too, unless you set
`MISE_DATA_DIR`.

Check that Gradle detects it:

```bash
./gradlew javaToolchains
# + Eclipse Temurin JDK 17 ... Detected by: Gradle property 'org.gradle.java.installations.paths'
```

## 3. Android SDK

`cordis-android` is included only when an Android SDK is found. Android Studio writes
`sdk.dir` to `local.properties` (gitignored) on first open. For terminal builds, `ANDROID_HOME` works too.

## 4. Gradle JDK in Android Studio (optional)

Android Studio runs Gradle itself on its bundled JBR, which is fine: step 2 only governs the toolchain used to
compile. To run Gradle on the same JDK as the terminal, open **Settings → Build, Execution, Deployment → Build
Tools → Gradle → Gradle JDK** and add the mise JDK directory from step 1.

## 5. Sync

**File → Sync Project with Gradle Files**. If the error persists, stop stale daemons with `./gradlew --stop` and
sync again.

## Linux: native tests fail with `libcrypt.so.1`

Unrelated to Android Studio, but it breaks `mise run build` on recent Arch-based distributions: the Kotlin/Native
`linuxX64` test binaries need `libcrypt.so.1`, and Gradle reports this as "no tests discovered". Install the
compat library:

```bash
sudo pacman -S libxcrypt-compat   # Arch / Omarchy
```
