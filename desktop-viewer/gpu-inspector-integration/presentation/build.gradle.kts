plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
dependencies {
    implementation("com.androidperformancestudio:desktop-ui:0.1.0-SNAPSHOT")
    implementation(project(":gpu-integration-model"))
    implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
    implementation("org.jetbrains.compose.components:components-resources:1.11.1")
}
