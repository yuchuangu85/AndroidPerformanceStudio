import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.tasks.testing.Test
import org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
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
        tasks.withType<BaseKtLintCheckTask>().configureEach {
            exclude { element -> element.file.path.contains("/build/generated/") }
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
