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
include("cordis-demo")
