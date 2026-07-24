plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(project(":memory-model"))
    implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
    testImplementation(compose.desktop.currentOs)
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:1.11.1")
    testImplementation("org.jetbrains.compose.ui:ui-test:1.11.1")
}
