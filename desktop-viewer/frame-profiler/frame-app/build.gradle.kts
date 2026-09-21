plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(libs.aps.ui.components)
    implementation(project(":frame-model"))
    implementation(project(":capture-frame"))
    implementation(project(":parser-frame"))
    implementation(project(":analysis-frame"))
    implementation(project(":frame-storage-sqlite"))
    implementation(project(":frame-export-adapters"))
    implementation(project(":frame-presentation"))
    implementation(libs.aps.adb.core)
    implementation(libs.aps.host.toolchain)
    implementation(libs.aps.profiler.contracts)
    implementation(libs.aps.platform.perfetto)
    implementation(libs.kotlinx.coroutines.core)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.components.resources)
}
