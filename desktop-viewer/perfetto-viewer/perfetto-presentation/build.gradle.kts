plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(project(":perfetto-model"))
    implementation(project(":perfetto-capture"))
    implementation(project(":perfetto-trace-processor"))
    implementation(project(":perfetto-ui-server"))
    implementation(project(":perfetto-analysis"))
    implementation(project(":perfetto-storage"))
    implementation("com.androidperformancestudio:device-adb:0.1.0-SNAPSHOT")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
