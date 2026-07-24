plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
dependencies {
    implementation(project(":benchmark-model"))
    implementation(project(":parser-benchmark-json"))
    implementation(project(":analysis-regression"))
    implementation(project(":storage-sqlite"))
    implementation(project(":export-adapters"))
    implementation(project(":presentation"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
