plugins { id("org.jetbrains.kotlin.plugin.serialization") }
dependencies {
    implementation(project(":network-model"))
    implementation(project(":analysis-network"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
