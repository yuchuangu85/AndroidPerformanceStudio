plugins { id("org.jetbrains.kotlin.plugin.serialization") }
dependencies {
    implementation(project(":benchmark-model"))
    implementation(libs.kotlinx.serialization.json)
}
