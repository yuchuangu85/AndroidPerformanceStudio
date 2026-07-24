plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
dependencies {
    implementation(project(":gpu-integration-model"))
    implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
}
