plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(libs.aps.ui.components)
    implementation(project(":perfetto-model"))
    implementation(project(":perfetto-capture"))
    implementation(project(":perfetto-ui-server"))
    implementation(project(":perfetto-analysis"))
    implementation(project(":perfetto-storage"))
    implementation(project(":perfetto-export"))
    implementation(project(":perfetto-presentation"))
    implementation(libs.aps.adb.core)
    implementation(libs.aps.platform.perfetto)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.compose.components.resources)
}
