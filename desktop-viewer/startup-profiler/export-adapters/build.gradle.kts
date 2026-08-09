plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(project(":startup-model"))
    implementation(project(":analysis-startup"))
    implementation("com.androidperformancestudio:profiler-contracts:0.1.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
