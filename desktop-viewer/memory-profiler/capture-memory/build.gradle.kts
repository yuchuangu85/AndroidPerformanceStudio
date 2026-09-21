dependencies {
    implementation(project(":memory-model"))
    implementation(project(":leakcanary-agent-protocol"))
    implementation(libs.aps.profiler.contracts)
    implementation(libs.aps.adb.core)
    implementation(libs.aps.host.toolchain)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
