plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("cordis.demo.MainKt")
}

dependencies {
    implementation(project(":cordis"))
    implementation(libs.kotlinx.coroutines.core)
}
