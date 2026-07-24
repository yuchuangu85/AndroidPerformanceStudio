plugins { id("org.jetbrains.kotlin.plugin.serialization") }
dependencies {
    implementation(project(":gpu-integration-model"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
