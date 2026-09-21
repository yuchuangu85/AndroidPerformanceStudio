plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
dependencies {
    implementation(libs.aps.ui.components)
    implementation(project(":benchmark-model"))
    implementation(project(":parser-benchmark-json"))
    implementation(project(":analysis-regression"))
    implementation(project(":benchmark-storage-sqlite"))
    implementation(project(":benchmark-export-adapters"))
    implementation(project(":benchmark-presentation"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.compose.components.resources)
}
