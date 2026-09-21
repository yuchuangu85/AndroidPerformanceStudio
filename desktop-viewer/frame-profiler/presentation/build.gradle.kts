plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

compose.resources {
    packageOfResClass = "com.androidperformancestudio.frame.presentation.generated.resources"
}

dependencies {
    implementation(libs.aps.ui.components)
    implementation(project(":frame-model"))
    implementation(project(":analysis-frame"))
    implementation(libs.compose.material3)
    implementation(libs.compose.components.resources)
}
