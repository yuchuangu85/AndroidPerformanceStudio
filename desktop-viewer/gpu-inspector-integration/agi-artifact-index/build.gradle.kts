plugins { id("org.jetbrains.kotlin.plugin.serialization") }
dependencies {
    implementation(project(":gpu-integration-model"))
    implementation(libs.aps.profiler.contracts)
    implementation(libs.kotlinx.serialization.json)
}
