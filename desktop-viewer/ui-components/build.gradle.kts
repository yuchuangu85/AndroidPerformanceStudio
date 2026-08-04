import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

group = "com.androidperformancestudio"
version = "0.1.0-SNAPSHOT"

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
    api(compose.desktop.currentOs)
    // Fluent Design UI (theme, navigation, materials)
    api("io.github.compose-fluent:fluent:v0.1.0")
    api("io.github.compose-fluent:fluent-icons-extended:v0.1.0")

    // Material3 retained for base components (Text, Button, Card, DropdownMenu, etc.)
    // that Fluent does not provide. Components using LocalViewerColors remain theme-agnostic.
    api("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
    api("org.jetbrains.compose.components:components-resources:1.11.1")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:1.11.1")
}

compose.resources {
    publicResClass = true
}

tasks.test {
    useJUnitPlatform()
}
