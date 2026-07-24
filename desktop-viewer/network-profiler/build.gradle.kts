import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.4.0" apply false
    kotlin("plugin.serialization") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
    id("com.android.library") version "9.2.0" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

allprojects {
    group = "com.androidperformancestudio.network"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    if (name != "root" && name != "android-agent-network") {
        apply(plugin = "org.jetbrains.kotlin.jvm")
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        apply(plugin = "io.gitlab.arturbosch.detekt")
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions { jvmTarget.set(JvmTarget.JVM_21); allWarningsAsErrors.set(true) }
        }
        extensions.configure<DetektExtension> {
            buildUponDefaultConfig = true
            allRules = false
            config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        }
        dependencies { add("testImplementation", kotlin("test")) }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
    }
}

tasks.register("checkAll") {
    group = "verification"
    description = "Runs every network-profiler check from composite-build callers."
    dependsOn(subprojects.map { project -> "${project.path}:check" })
}
