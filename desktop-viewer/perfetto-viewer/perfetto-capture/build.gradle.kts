dependencies {
    implementation(project(":perfetto-model"))
    implementation(libs.aps.adb.core)
    implementation(libs.aps.platform.perfetto)
    implementation(libs.kotlinx.coroutines.core)
}
