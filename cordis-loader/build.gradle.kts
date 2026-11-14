plugins {
    id("cordis.kmp-library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":cordis"))
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(project(":cordis-testing"))
        }
    }
}
