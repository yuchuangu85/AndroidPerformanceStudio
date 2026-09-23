dependencies {
    implementation(libs.aps.adb.core)
    implementation(project(":network-model"))
    implementation(project(":network-agent-protocol"))
    implementation(libs.kotlinx.coroutines.core)
}
