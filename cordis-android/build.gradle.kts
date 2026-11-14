import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cordis.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    explicitApi()
    jvmToolchain(17)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    api(project(":cordis-runtime"))
    api(libs.androidx.lifecycle.process)
    api(libs.androidx.lifecycle.runtime)
    api(libs.androidx.datastore.preferences)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":cordis-testing"))
}

// The gate only runs `jvmTest`, so alias it to this module's local unit tests.
tasks.register("jvmTest") {
    dependsOn("testDebugUnitTest")
}
