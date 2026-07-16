plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(project(":analysis-rules"))
    implementation(project(":application"))
    implementation(project(":capture-simpleperf"))
    implementation(project(":profile-analysis"))
    implementation(project(":profile-model"))
    implementation(project(":storage-sqlite"))
    implementation(project(":visualization"))
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    testImplementation(compose.desktop.currentOs)
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:1.11.1")
    testImplementation("org.jetbrains.compose.ui:ui-test:1.11.1")
}
