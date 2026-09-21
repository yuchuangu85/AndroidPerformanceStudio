plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

compose.resources {
    packageOfResClass = "com.androidperformancestudio.methodrecording.app.generated.resources"
}

dependencies {
    implementation(libs.aps.ui.components)
    implementation(project(":parser-art-trace"))
    implementation(project(":capture-method-trace"))
    implementation(project(":simpleperf-presentation"))
    implementation(project(":simpleperf-application"))
    implementation(project(":simpleperf-storage-sqlite"))
    implementation(libs.aps.profiler.contracts)
    implementation(project(":profile-analysis"))
    implementation(libs.aps.adb.core)
    implementation(libs.aps.host.toolchain)
    implementation(libs.compose.material3)
    implementation(libs.compose.components.resources)
    testImplementation(compose.desktop.currentOs)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test)
}
