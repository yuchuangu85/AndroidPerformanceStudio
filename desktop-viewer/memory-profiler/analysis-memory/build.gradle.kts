dependencies {
    implementation(project(":memory-model"))
    implementation(project(":parser-hprof"))
    api("com.androidperformancestudio:platform-perfetto:0.1.0-SNAPSHOT")
    implementation("com.squareup.leakcanary:shark:2.14")
    implementation("com.squareup.leakcanary:shark-hprof:2.14")
}
