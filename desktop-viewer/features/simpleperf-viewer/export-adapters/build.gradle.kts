dependencies {
    implementation(project(":platform-toolchain"))
    implementation(project(":profile-model"))
    implementation(project(":storage-sqlite"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
