dependencies {
    implementation(project(":analysis-rules"))
    implementation(project(":capture-simpleperf"))
    implementation(project(":parser-simpleperf-proto"))
    implementation(project(":profile-model"))
    implementation(project(":storage-sqlite"))
    implementation(project(":platform-toolchain"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("com.google.protobuf:protobuf-java:4.35.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
