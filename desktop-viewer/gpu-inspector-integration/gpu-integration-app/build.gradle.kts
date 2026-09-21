plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
dependencies {
    implementation(libs.aps.ui.components)
    implementation(project(":gpu-integration-model"))
    implementation(project(":agi-toolchain"))
    implementation(project(":agi-artifact-index"))
    implementation(project(":gpu-integration-presentation"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.compose.components.resources)
}
