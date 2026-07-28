plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(project(":profile-analysis"))
    implementation(project(":profile-model"))
    implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
    testImplementation(compose.desktop.currentOs)
    testImplementation("org.jetbrains.compose.ui:ui-test:1.11.1")
    implementation("org.jetbrains.compose.components:components-resources:1.11.1")
}
