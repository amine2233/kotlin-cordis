pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "cordis-samples"

val localProperties =
    java.util.Properties().apply {
        val file = file("local.properties")
        if (file.isFile) file.inputStream().use { load(it) }
    }
val androidSdkAvailable =
    listOfNotNull(
        localProperties.getProperty("sdk.dir"),
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
    ).any { it.isNotBlank() && file(it).isDirectory }

gradle.extra["androidSdkAvailable"] = androidSdkAvailable

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        if (androidSdkAvailable) {
            google {
                content {
                    includeGroupByRegex("androidx\\..*")
                    includeGroupByRegex("com\\.android(\\..*)?")
                    includeGroupByRegex("com\\.google\\.android(\\..*)?")
                }
            }
        }
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

includeBuild("..")

include("shared")
include("ktor")
if (androidSdkAvailable) {
    include("android")
}
