plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("cordis.demo.MainKt")
}

dependencies {
    implementation(project(":cordis"))
    implementation(libs.kotlinx.coroutines.core)
}
