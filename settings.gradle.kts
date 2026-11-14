pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "kotlin-cordis"

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
}

include("cordis-kernel")
include("cordis")
include("cordis-loader")
include("cordis-testing")
include("cordis-runtime")
include("cordis-ktor")
include("cordis-demo")

// Opt-in: built only where an Android SDK is found, so machines and CI without one never resolve AGP.
if (androidSdkAvailable) {
    include("cordis-android")
}
