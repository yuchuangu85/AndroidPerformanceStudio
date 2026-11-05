plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(project(":screencast-core"))
    implementation(project(":screencast-presentation"))
    implementation(libs.aps.adb.core)
    implementation(libs.aps.ui.components)
    implementation(libs.kotlinx.coroutines.core)
    implementation(compose.desktop.currentOs)
}
