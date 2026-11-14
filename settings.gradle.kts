pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "kotlin-cordis"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("cordis-kernel")
include("cordis")
include("cordis-loader")
include("cordis-testing")
include("cordis-runtime")
include("cordis-demo")
