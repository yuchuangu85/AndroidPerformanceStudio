plugins { id("org.jetbrains.kotlin.plugin.serialization") }
dependencies {
    implementation(project(":benchmark-model"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
