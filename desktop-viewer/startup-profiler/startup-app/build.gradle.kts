plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(libs.aps.ui.components)
    implementation(project(":startup-model"))
    implementation(project(":capture-startup"))
    implementation(project(":analysis-startup"))
    implementation(project(":startup-storage-sqlite"))
    implementation(project(":startup-export-adapters"))
    implementation(project(":startup-presentation"))
    implementation(libs.aps.adb.core)
    implementation(libs.aps.host.toolchain)
    implementation(libs.aps.profiler.contracts)
    implementation(libs.aps.platform.perfetto)
    implementation(libs.kotlinx.coroutines.core)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.components.resources)
}
