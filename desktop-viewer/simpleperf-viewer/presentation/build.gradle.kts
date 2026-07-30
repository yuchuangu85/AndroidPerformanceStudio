plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

compose.resources {
    packageOfResClass = "com.androidperformancestudio.simpleperf.presentation.generated.resources"
}

dependencies {
    implementation("com.androidperformancestudio:ui-components:0.1.0-SNAPSHOT")
    implementation(project(":analysis-rules"))
    api(project(":simpleperf-application"))
    api(project(":capture-simpleperf"))
    implementation(project(":profile-analysis"))
    implementation(project(":profile-model"))
    implementation(project(":simpleperf-storage-sqlite"))
    implementation(project(":visualization"))
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    testImplementation(compose.desktop.currentOs)
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:1.11.1")
    testImplementation("org.jetbrains.compose.ui:ui-test:1.11.1")
    implementation("org.jetbrains.compose.components:components-resources:1.11.1")
}
