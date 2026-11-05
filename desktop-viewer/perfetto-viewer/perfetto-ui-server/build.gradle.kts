dependencies {
    implementation(libs.aps.ui.components)
    implementation(project(":perfetto-model"))
    implementation(libs.aps.host.toolchain)
    implementation(libs.aps.platform.perfetto)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
