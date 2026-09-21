dependencies {
    implementation(project(":startup-model"))
    implementation(project(":startup-agent-protocol"))
    implementation(project(":parser-startup"))
    implementation(libs.aps.adb.core)
    implementation(libs.aps.host.toolchain)
    implementation(libs.aps.profiler.contracts)
    implementation(libs.kotlinx.coroutines.core)
}

dependencies {
    testImplementation(libs.kotlinx.coroutines.test)
}
