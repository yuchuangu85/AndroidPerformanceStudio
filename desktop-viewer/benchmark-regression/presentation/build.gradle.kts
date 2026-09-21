plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

compose.resources {
    packageOfResClass = "com.androidperformancestudio.benchmark.presentation.generated.resources"
}

dependencies {
    implementation(libs.aps.ui.components)
    implementation(project(":benchmark-model"))
    implementation(project(":analysis-regression"))
    implementation(libs.compose.material3)
    implementation(libs.compose.components.resources)
}
