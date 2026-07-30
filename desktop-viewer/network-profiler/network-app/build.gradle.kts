plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
dependencies {
    implementation("com.androidperformancestudio:ui-components:0.1.0-SNAPSHOT")
    implementation(project(":network-model"))
    implementation(project(":network-agent-protocol"))
    implementation(project(":capture-network"))
    implementation(project(":parser-har"))
    implementation(project(":analysis-network"))
    implementation(project(":network-storage-sqlite"))
    implementation(project(":network-export-adapters"))
    implementation(project(":network-presentation"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.compose.components:components-resources:1.11.1")
}
