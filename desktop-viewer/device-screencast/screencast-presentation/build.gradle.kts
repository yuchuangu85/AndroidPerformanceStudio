plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(project(":screencast-core"))
    implementation(libs.aps.ui.components)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    // Provides Dispatchers.Main on the JVM (Compose Desktop has no default).
    implementation(libs.kotlinx.coroutines.swing)
    implementation(compose.desktop.currentOs)
}
