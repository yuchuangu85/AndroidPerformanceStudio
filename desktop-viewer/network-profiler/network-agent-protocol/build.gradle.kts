plugins { id("org.jetbrains.kotlin.plugin.serialization") }
dependencies {
    implementation(project(":network-model"))
    implementation(libs.kotlinx.serialization.json)
}
