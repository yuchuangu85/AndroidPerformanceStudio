plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

compose.resources {
    packageOfResClass = "com.androidperformancestudio.simpleperf.presentation.generated.resources"
}

dependencies {
    implementation(libs.aps.ui.components)
    implementation(project(":analysis-rules"))
    api(project(":simpleperf-application"))
    api(project(":capture-simpleperf"))
    implementation(project(":profile-analysis"))
    implementation(project(":profile-model"))
    implementation(project(":simpleperf-storage-sqlite"))
    implementation(project(":visualization"))
    implementation(libs.compose.material3)
    testImplementation(compose.desktop.currentOs)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test)
    implementation(libs.compose.components.resources)
}
