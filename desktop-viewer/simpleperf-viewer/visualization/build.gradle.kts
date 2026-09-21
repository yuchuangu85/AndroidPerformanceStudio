plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(libs.aps.ui.components)
    implementation(project(":profile-analysis"))
    implementation(project(":profile-model"))
    implementation(libs.compose.foundation)
    testImplementation(compose.desktop.currentOs)
    testImplementation(libs.compose.ui.test)
    implementation(libs.compose.components.resources)
}
