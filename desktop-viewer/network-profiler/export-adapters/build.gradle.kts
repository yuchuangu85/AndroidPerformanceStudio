plugins { id("org.jetbrains.kotlin.plugin.serialization") }
dependencies {
    implementation(project(":network-model"))
    implementation(project(":analysis-network"))
    implementation(libs.kotlinx.serialization.json)
}
