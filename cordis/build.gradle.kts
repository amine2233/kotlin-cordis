plugins {
    id("cordis.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":cordis-kernel"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":cordis-testing"))
        }
    }
}
