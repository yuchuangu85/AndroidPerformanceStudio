plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(project(":profile-model"))
    implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
}
