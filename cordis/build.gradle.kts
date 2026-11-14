plugins {
    id("cordis.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":cordis-kernel"))
        }
        commonTest.dependencies {
            implementation(project(":cordis-testing"))
        }
    }
}
