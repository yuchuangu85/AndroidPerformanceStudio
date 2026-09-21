plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
dependencies {
    implementation(libs.aps.ui.components)
    implementation(libs.aps.profiler.contracts)
    implementation(project(":network-model"))
    implementation(project(":network-agent-protocol"))
    implementation(project(":capture-network"))
    implementation(project(":parser-har"))
    implementation(project(":analysis-network"))
    implementation(project(":network-storage-sqlite"))
    implementation(project(":network-export-adapters"))
    implementation(project(":network-presentation"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.compose.components.resources)
}
