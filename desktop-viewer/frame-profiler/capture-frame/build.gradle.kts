dependencies {
    implementation(project(":frame-agent-protocol"))
    implementation(project(":frame-model"))
    implementation(project(":parser-frame"))
    implementation(libs.aps.adb.core)
    implementation(libs.aps.profiler.contracts)
    implementation(libs.kotlinx.coroutines.core)
}
