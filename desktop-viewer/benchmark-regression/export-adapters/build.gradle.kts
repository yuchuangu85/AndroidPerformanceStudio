plugins { id("org.jetbrains.kotlin.plugin.serialization") }
dependencies {
    implementation(project(":benchmark-model"))
    implementation(project(":analysis-regression"))
    implementation(libs.kotlinx.serialization.json)
}
