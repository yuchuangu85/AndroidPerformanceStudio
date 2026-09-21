plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

compose.resources {
    packageOfResClass = "com.androidperformancestudio.memory.presentation.generated.resources"
}

dependencies {
    implementation(libs.aps.ui.components)
    implementation(project(":memory-model"))
    implementation(libs.compose.material3)
    testImplementation(compose.desktop.currentOs)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test)
    implementation(libs.compose.components.resources)
}
