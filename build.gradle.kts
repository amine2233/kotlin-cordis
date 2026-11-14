buildscript {
    // AGP must share the Kotlin Gradle plugin's classloader, so it goes on the root classpath, only when the module is built.
    if (findProject(":cordis-android") != null) {
        repositories {
            google {
                content {
                    includeGroupByRegex("androidx\\..*")
                    includeGroupByRegex("com\\.android(\\..*)?")
                    includeGroupByRegex("com\\.google\\.android(\\..*)?")
                    includeGroupByRegex("com\\.google\\.testing\\.platform")
                }
            }
            mavenCentral()
        }
        dependencies {
            classpath(libs.android.gradle.plugin)
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.binary.compatibility.validator)
}

apiValidation {
    ignoredProjects += "cordis-demo"

    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
}

// cordis-ktor is JVM-only, so it has no klib ABI to dump or check; its JVM API is still validated.
project(":cordis-ktor") {
    tasks.matching { it.name.startsWith("klibApi") }.configureEach { onlyIf { false } }
}

allprojects {
    group = "io.github.amine2233"
    version = (findProperty("version") as String?)?.takeIf { it.isNotBlank() } ?: "0.1.0-SNAPSHOT"

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
        compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

configure(subprojects.filter { it.name !in setOf("cordis-demo", "cordis-android") }) {
    apply(plugin = "maven-publish")

    extensions.configure<PublishingExtension> {
        publications.withType<MavenPublication>().configureEach {
            pom {
                name.set(project.name)
                url.set("https://github.com/amine2233/kotlin-cordis")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("amine2233")
                        name.set("Amine Bensalah")
                    }
                }
                scm { url.set("https://github.com/amine2233/kotlin-cordis") }
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/amine2233/kotlin-cordis")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}
