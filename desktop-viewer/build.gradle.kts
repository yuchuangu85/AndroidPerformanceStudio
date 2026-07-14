plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val defaultAppVersion = "0.2.4"
val appVersion = providers.gradleProperty("appVersion").getOrElse(defaultAppVersion)

allprojects {
    group = "dev.agentperf"
    version = appVersion
}

val simpleperfBuild = gradle.includedBuild("simpleperf-viewer")

tasks.register("simpleperfRun") {
    group = "application"
    description = "Runs the isolated Simpleperf CPU profiler desktop application."
    dependsOn(simpleperfBuild.task(":app-desktop:run"))
}

tasks.register("simpleperfCheck") {
    group = "verification"
    description = "Runs all checks in the isolated Simpleperf CPU profiler build."
    dependsOn(simpleperfBuild.task(":checkAll"))
}

tasks.register("simpleperfCreateDistributable") {
    group = "distribution"
    description = "Creates the Simpleperf CPU profiler application image for the current OS."
    dependsOn(simpleperfBuild.task(":app-desktop:createDistributable"))
}
