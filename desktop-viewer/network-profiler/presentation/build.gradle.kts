plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

compose.resources {
    packageOfResClass = "com.androidperformancestudio.network.presentation.generated.resources"
}

dependencies {
    implementation(libs.aps.profiler.contracts)
    implementation(libs.aps.ui.components)
    implementation(project(":network-model"))
    implementation(project(":analysis-network"))
    implementation(libs.compose.material3)
    implementation(libs.compose.components.resources)
}
