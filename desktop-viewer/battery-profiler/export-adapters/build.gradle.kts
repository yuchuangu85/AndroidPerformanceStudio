plugins { id("org.jetbrains.kotlin.plugin.serialization") }
dependencies {
    implementation(project(":battery-model"))
    implementation(project(":analysis-battery"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
