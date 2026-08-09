dependencies {
    implementation(project(":startup-model"))
    implementation(project(":startup-agent-protocol"))
    implementation(project(":parser-startup"))
    implementation("com.androidperformancestudio:host-toolchain:0.1.0-SNAPSHOT")
    implementation("com.androidperformancestudio:profiler-contracts:0.1.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

dependencies {
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
