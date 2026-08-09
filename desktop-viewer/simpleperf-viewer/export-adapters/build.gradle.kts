dependencies {
    implementation("com.androidperformancestudio:host-toolchain:0.1.0-SNAPSHOT")
    implementation(project(":profile-model"))
    implementation(project(":simpleperf-storage-sqlite"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
