plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
dependencies {
    implementation("com.androidperformancestudio:ui-components:0.1.0-SNAPSHOT")
    implementation(project(":battery-model"))
    implementation(project(":capture-battery"))
    implementation(project(":analysis-battery"))
    implementation(project(":historian-adapter"))
    implementation(project(":storage-sqlite"))
    implementation(project(":export-adapters"))
    implementation(project(":presentation"))
    implementation("com.androidperformancestudio:device-adb:0.1.0-SNAPSHOT")
    implementation("com.androidperformancestudio:platform-toolchain:0.1.0-SNAPSHOT")
    implementation("com.androidperformancestudio:profile-model:0.1.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
    implementation("org.jetbrains.compose.components:components-resources:1.11.1")
}
