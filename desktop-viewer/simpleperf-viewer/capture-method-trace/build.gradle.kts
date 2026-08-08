dependencies {
    implementation(project(":platform-toolchain"))
    implementation(project(":profile-model"))
    implementation(project(":device-adb"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
