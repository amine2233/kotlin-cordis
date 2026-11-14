import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val androidSdkAvailable = gradle.extra["androidSdkAvailable"] == true

// AGP is only on the classpath when an SDK is found, so its extension is configured without its types.
if (androidSdkAvailable) {
    apply(plugin = "com.android.library")
    extensions.getByName("android").withGroovyBuilder {
        setProperty("namespace", "cordis.samples.shared")
        setProperty("compileSdk", 36)
        "defaultConfig" {
            setProperty("minSdk", 23)
        }
        "compileOptions" {
            setProperty("sourceCompatibility", JavaVersion.VERSION_17)
            setProperty("targetCompatibility", JavaVersion.VERSION_17)
        }
    }
}

kotlin {
    jvmToolchain(17)

    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        testRuns.named("test") {
            executionTask.configure {
                useJUnitPlatform()
            }
        }
    }

    if (androidSdkAvailable) {
        androidTarget {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("io.github.amine2233:cordis-runtime")
            implementation("io.github.amine2233:cordis")
        }
        commonTest.dependencies {
            implementation("io.github.amine2233:cordis-testing")
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
