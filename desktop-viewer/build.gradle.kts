plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val defaultAppVersion = "0.3.2"
val appVersion = providers.gradleProperty("appVersion").getOrElse(defaultAppVersion)

allprojects {
    group = "dev.agentperf"
    version = appVersion
}

val simpleperfBuild = gradle.includedBuild("simpleperf-viewer")
val repositoryRoot = layout.projectDirectory.dir("..")
val firefoxProfilerScript = repositoryRoot.file("scripts/firefox-profiler.sh")

tasks.register<Exec>("firefoxProfilerInit") {
    group = "firefox profiler"
    description = "Initializes the pinned Firefox Profiler Git submodule."
    workingDir(repositoryRoot)
    commandLine(firefoxProfilerScript.asFile.absolutePath, "init")
}

tasks.register<Exec>("firefoxProfilerVerify") {
    group = "verification"
    description = "Verifies the Firefox Profiler revision and Node/Yarn toolchain."
    workingDir(repositoryRoot)
    commandLine(firefoxProfilerScript.asFile.absolutePath, "verify")
}

tasks.register<Exec>("firefoxProfilerInstall") {
    group = "firefox profiler"
    description = "Installs Firefox Profiler dependencies using the pinned yarn.lock."
    dependsOn("firefoxProfilerInit")
    workingDir(repositoryRoot)
    commandLine(firefoxProfilerScript.asFile.absolutePath, "install")
}

tasks.register<Exec>("firefoxProfilerBuild") {
    group = "firefox profiler"
    description = "Builds Firefox Profiler production static assets."
    dependsOn("firefoxProfilerInstall")
    workingDir(repositoryRoot)
    commandLine(firefoxProfilerScript.asFile.absolutePath, "build")
}

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
