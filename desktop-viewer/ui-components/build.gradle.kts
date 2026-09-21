import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.detekt)
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
    api(libs.compose.fluent)
    api(libs.compose.fluent.icons.extended)

    // Material3 retained for base components (Text, Button, Card, DropdownMenu, etc.)
    // that Fluent does not provide. Components using LocalViewerColors remain theme-agnostic.
    api(libs.compose.material3)
    api(libs.compose.components.resources)
    testImplementation(kotlin("test"))
    testImplementation(libs.compose.ui.test.junit4)
}

compose.resources {
    publicResClass = true
}

tasks.test {
    useJUnitPlatform()
}
