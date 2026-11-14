buildscript {
    // AGP must share the Kotlin Gradle plugin's classloader, so it goes on the root classpath, only when an SDK is found.
    if (gradle.extra["androidSdkAvailable"] == true) {
        repositories {
            google {
                content {
                    includeGroupByRegex("androidx\\..*")
                    includeGroupByRegex("com\\.android(\\..*)?")
                    includeGroupByRegex("com\\.google\\.android(\\..*)?")
                    includeGroupByRegex("com\\.google\\.testing\\.platform")
                }
            }
            mavenCentral()
        }
        dependencies {
            classpath(libs.android.gradle.plugin)
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
}
