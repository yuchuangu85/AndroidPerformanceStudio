plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

compose.resources {
    packageOfResClass = "com.androidperformancestudio.gpu.presentation.generated.resources"
}

dependencies {
    implementation(libs.aps.ui.components)
    implementation(project(":gpu-integration-model"))
    implementation(libs.compose.material3)
    implementation(libs.compose.components.resources)
}
