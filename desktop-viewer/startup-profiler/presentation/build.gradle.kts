plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation("com.androidperformancestudio:ui-components:0.1.0-SNAPSHOT")
    implementation(project(":startup-model"))
    implementation(project(":analysis-startup"))
    implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
    implementation("org.jetbrains.compose.components:components-resources:1.11.1")
}
