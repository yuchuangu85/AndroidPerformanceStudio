plugins { id("org.jetbrains.kotlin.plugin.serialization") }
dependencies {
    implementation(project(":network-model"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(project(":analysis-network"))
}
