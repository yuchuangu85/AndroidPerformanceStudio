plugins {
    kotlin("plugin.serialization")
}

dependencies {
    api("com.androidperformancestudio:profiler-contracts:0.1.0-SNAPSHOT")
    implementation("com.androidperformancestudio:adb-core:0.1.0-SNAPSHOT")
    implementation("com.androidperformancestudio:host-toolchain:0.1.0-SNAPSHOT")
    implementation("com.androidperformancestudio:platform-perfetto:0.1.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(project(":winscope-test-fixtures"))
}
