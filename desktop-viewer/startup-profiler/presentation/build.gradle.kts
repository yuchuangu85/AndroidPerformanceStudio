plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

compose.resources {
    packageOfResClass = "com.androidperformancestudio.startup.presentation.generated.resources"
}

dependencies {
    implementation(libs.aps.ui.components)
    implementation(project(":startup-model"))
    implementation(project(":analysis-startup"))
    implementation(libs.compose.material3)
    implementation(libs.compose.components.resources)
}
