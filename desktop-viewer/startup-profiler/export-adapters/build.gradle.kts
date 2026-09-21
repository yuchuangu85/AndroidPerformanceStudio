plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(project(":startup-model"))
    implementation(project(":analysis-startup"))
    implementation(libs.aps.profiler.contracts)
    implementation(libs.kotlinx.serialization.json)
}
