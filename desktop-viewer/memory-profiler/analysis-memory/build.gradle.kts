dependencies {
    implementation(project(":memory-model"))
    implementation(project(":parser-hprof"))
    api(libs.aps.platform.perfetto)
    implementation(libs.leakcanary.shark)
    implementation(libs.leakcanary.shark.hprof)
}
