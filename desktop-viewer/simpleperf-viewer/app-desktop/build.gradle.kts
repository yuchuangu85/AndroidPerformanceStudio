import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val firefoxProfilerDist = rootProject.layout.projectDirectory.dir("../../third_party/firefox-profiler/dist")
val firefoxProfilerAppResources = layout.buildDirectory.dir("generated/firefox-profiler-app-resources")
val prepareFirefoxProfilerAppResources =
    tasks.register<Sync>("prepareFirefoxProfilerAppResources") {
        inputs.file(firefoxProfilerDist.file("index.html"))
        from(firefoxProfilerDist)
        into(firefoxProfilerAppResources.map { resources -> resources.dir("common") })
    }

tasks
    .matching { task -> task.name == "prepareAppResources" }
    .configureEach { dependsOn(prepareFirefoxProfilerAppResources) }

dependencies {
    implementation(project(":analysis-rules"))
    implementation(project(":application"))
    implementation(project(":capture-simpleperf"))
    implementation(project(":device-adb"))
    implementation(project(":export-adapters"))
    implementation(project(":platform-toolchain"))
    implementation(project(":parser-simpleperf-proto"))
    implementation(project(":presentation"))
    implementation(project(":profile-analysis"))
    implementation(project(":profile-model"))
    implementation(project(":storage-sqlite"))
    implementation(compose.desktop.currentOs)
    testImplementation(project(":test-fixtures"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

compose.desktop {
    application {
        mainClass = "com.androidperformancestudio.desktop.MainKt"

        nativeDistributions {
            appResourcesRootDir.set(firefoxProfilerAppResources)
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            // The minimized jpackage runtime does not infer JDBC usage through the storage module.
            modules("java.sql")
            packageName = "Android Performance Studio"
            // jpackage on macOS rejects installer versions whose first component is zero.
            packageVersion = "1.0.0"
            description = "Cross-platform Android Simpleperf capture and CPU profile analysis."
            vendor = "Android Performance Studio"
        }
    }
}
