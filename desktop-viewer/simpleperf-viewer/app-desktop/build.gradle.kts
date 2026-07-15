import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

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
}

compose.desktop {
    application {
        mainClass = "com.androidperformancestudio.desktop.MainKt"

        nativeDistributions {
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
