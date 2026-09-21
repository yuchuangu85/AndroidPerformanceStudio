import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

compose.resources {
    packageOfResClass = "com.androidperformancestudio.presentation.generated.resources"
    publicResClass = true
}

dependencies {
    implementation(project(":compose-inspection-host"))
    implementation(libs.aps.ai.core)
    implementation(libs.aps.ui.components)
    implementation(libs.aps.host.toolchain)
    implementation(project(":adb-gateway"))
    implementation(project(":application"))
    implementation(project(":compose-inspection-model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.components.resources)
    implementation(libs.compose.material3)
    testImplementation(project(":test-fixtures"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
