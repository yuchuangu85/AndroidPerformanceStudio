dependencies {
    implementation(project(":analysis-rules"))
    implementation(project(":capture-simpleperf"))
    implementation(project(":parser-simpleperf-proto"))
    implementation(project(":profile-model"))
    implementation(project(":profile-analysis"))
    implementation(project(":simpleperf-storage-sqlite"))
    implementation(libs.aps.host.toolchain)
    implementation(libs.aps.profiler.contracts)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.protobuf.java)
    testImplementation(libs.kotlinx.coroutines.test)
}
