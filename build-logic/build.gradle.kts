plugins {
    `kotlin-dsl`
}

// Kept out of `src/` so the convention plugin is not mistaken for library sources.
sourceSets {
    main {
        kotlin.setSrcDirs(listOf("convention"))
    }
}

dependencies {
    // compileOnly: the root build loads the Kotlin Gradle plugin, so it exists in one classloader only.
    compileOnly(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = "cordis.kmp-library"
            implementationClass = "KmpLibraryConventionPlugin"
        }
    }
}
