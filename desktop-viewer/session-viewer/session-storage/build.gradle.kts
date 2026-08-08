plugins {
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":session-model"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
