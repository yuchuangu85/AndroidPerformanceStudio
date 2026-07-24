plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
dependencies {
    implementation(project(":gpu-integration-model"))
    implementation(project(":agi-toolchain"))
    implementation(project(":agi-artifact-index"))
    implementation(project(":presentation"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
