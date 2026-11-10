import org.gradle.api.tasks.testing.Test

plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

tasks.withType<Test>().configureEach {
    providers.gradleProperty("apsHprofTestMaxHeap").orNull?.let { maxHeapSize = it }
    if (!providers.environmentVariable("APS_HPROF_FIXTURE").orNull.isNullOrBlank()) {
        testLogging.showStandardStreams = true
    }
}

dependencies {
    implementation(libs.aps.ui.components)
    implementation(project(":memory-model"))
    implementation(project(":leakcanary-agent-protocol"))
    implementation(project(":capture-memory"))
    implementation(project(":parser-hprof"))
    implementation(project(":analysis-memory"))
    implementation(project(":memory-storage-sqlite"))
    implementation(project(":memory-export-adapters"))
    implementation(project(":memory-presentation"))
    implementation(libs.aps.profiler.contracts)
    implementation(libs.aps.adb.core)
    implementation(libs.aps.host.toolchain)
    implementation(libs.kotlinx.coroutines.core)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.compose.ui.test)
    implementation(libs.compose.components.resources)
}
