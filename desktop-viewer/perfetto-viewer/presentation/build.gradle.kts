plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation("com.androidperformancestudio:desktop-ui:0.1.0-SNAPSHOT")
    implementation(project(":perfetto-model"))
    implementation(project(":capture-perfetto"))
    implementation(project(":trace-processor-bridge"))
    implementation(project(":perfetto-ui-server"))
    implementation(project(":trace-analysis"))
    implementation(project(":storage-perfetto"))
    implementation(project(":device-adb"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.compose.components:components-resources:1.11.1")
}
