plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation("com.androidperformancestudio:ui-components:0.1.0-SNAPSHOT")
    implementation(project(":frame-model"))
    implementation(project(":capture-frame"))
    implementation(project(":parser-frame"))
    implementation(project(":analysis-frame"))
    implementation(project(":frame-storage-sqlite"))
    implementation(project(":frame-export-adapters"))
    implementation(project(":frame-presentation"))
    implementation("com.androidperformancestudio:adb-core:0.1.0-SNAPSHOT")
    implementation("com.androidperformancestudio:host-toolchain:0.1.0-SNAPSHOT")
    implementation("com.androidperformancestudio:profiler-contracts:0.1.0-SNAPSHOT")
    implementation("com.androidperformancestudio:platform-perfetto:0.1.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
    implementation("org.jetbrains.compose.components:components-resources:1.11.1")
}
