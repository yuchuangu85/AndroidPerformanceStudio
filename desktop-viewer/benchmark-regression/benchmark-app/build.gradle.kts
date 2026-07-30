plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
dependencies {
    implementation("com.androidperformancestudio:ui-components:0.1.0-SNAPSHOT")
    implementation(project(":benchmark-model"))
    implementation(project(":parser-benchmark-json"))
    implementation(project(":analysis-regression"))
    implementation(project(":benchmark-storage-sqlite"))
    implementation(project(":benchmark-export-adapters"))
    implementation(project(":benchmark-presentation"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.compose.components:components-resources:1.11.1")
}
