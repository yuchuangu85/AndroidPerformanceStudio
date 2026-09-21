plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
dependencies {
    implementation(libs.aps.ui.components)
    implementation(libs.aps.adb.core)
    implementation(libs.aps.host.toolchain)
    implementation(libs.aps.profiler.contracts)
    implementation(libs.kotlinx.coroutines.core)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.components.resources)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    testImplementation(libs.kotlinx.coroutines.test)
}
