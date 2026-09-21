dependencies {
    implementation(libs.aps.host.toolchain)
    implementation(project(":profile-model"))
    implementation(project(":profile-analysis"))
    implementation(project(":simpleperf-storage-sqlite"))
    testImplementation(libs.kotlinx.coroutines.test)
}
