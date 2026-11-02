import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":ai-platform-runtime"))
    api(project(":ai-platform-providers"))
    api(project(":ai-platform-registry"))
    api(project(":ai-platform-storage"))
    api(project(":ai-platform-script-runtime"))
    implementation(project(":ai-platform-provider-codex"))
    implementation(project(":ai-platform-provider-claude"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
