plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(project(":startup-model"))
    implementation(project(":analysis-startup"))
    implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
}
