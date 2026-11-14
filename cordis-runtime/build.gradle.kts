plugins {
    id("cordis.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":cordis-loader"))
        }
        commonTest.dependencies {
            implementation(project(":cordis-testing"))
        }
    }
}
