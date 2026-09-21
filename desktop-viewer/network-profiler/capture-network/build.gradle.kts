dependencies {
    implementation(libs.aps.host.toolchain)
    implementation(project(":network-model"))
    implementation(project(":network-agent-protocol"))
    implementation(libs.kotlinx.coroutines.core)
}
