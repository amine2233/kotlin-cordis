import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val androidSdkAvailable = gradle.extra["androidSdkAvailable"] == true

// AGP is only on the classpath when an SDK is found, so its KMP Android target is configured without its types.
if (androidSdkAvailable) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
    (kotlin as ExtensionAware).extensions.getByName("android").withGroovyBuilder {
        setProperty("namespace", "cordis.samples.shared")
        setProperty("compileSdk", 36)
        setProperty("minSdk", 23)
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
