plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

compose.resources {
    packageOfResClass = "com.androidperformancestudio.methodrecording.app.generated.resources"
}

dependencies {
    implementation("com.androidperformancestudio:ui-components:0.1.0-SNAPSHOT")
    implementation(project(":parser-art-trace"))
    implementation(project(":capture-method-trace"))
    implementation(project(":simpleperf-presentation"))
    implementation(project(":simpleperf-application"))
    implementation(project(":simpleperf-storage-sqlite"))
    implementation(project(":profile-model"))
    implementation(project(":profile-analysis"))
    implementation(project(":device-adb"))
    implementation(project(":platform-toolchain"))
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.components:components-resources:1.11.1")
    testImplementation(compose.desktop.currentOs)
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:1.11.1")
    testImplementation("org.jetbrains.compose.ui:ui-test:1.11.1")
}
