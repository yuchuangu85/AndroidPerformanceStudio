plugins {
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val defaultAppVersion = "0.7.2"
val appVersion = providers.gradleProperty("appVersion").getOrElse(defaultAppVersion)

allprojects {
    group = "com.androidperformancestudio"
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

val nativeEngines = mapOf(
    "Cpu" to "simpleperf-viewer/native-engine-rust",
    "Har" to "network-profiler/native-engine-rust",
    "Hprof" to "memory-profiler/hprof-engine-rust",
)
val rustHost = providers.exec { commandLine("rustc", "-vV") }.standardOutput.asText.map { output ->
    output.lineSequence().first { it.startsWith("host: ") }.removePrefix("host: ")
}
val nativeChecks = nativeEngines.map { (name, directory) ->
    val engineDirectory = layout.projectDirectory.dir(directory)
    val nativeTargetDirectory = engineDirectory.dir("target").asFile
    val compilerHost = rustHost
    val format = tasks.register<Exec>("formatRust${name}EngineCheck") {
        group = "verification"
        workingDir(engineDirectory)
        commandLine("cargo", "fmt", "--", "--check")
    }
    val test = tasks.register<Exec>("testRust${name}Engine") {
        group = "verification"
        dependsOn(format)
        workingDir(engineDirectory)
        doFirst {
            commandLine("cargo", "test", "--locked", "--offline", "--target", compilerHost.get(), "--target-dir", nativeTargetDirectory)
        }
    }
    val clippy = tasks.register<Exec>("clippyRust${name}Engine") {
        group = "verification"
        dependsOn(test)
        workingDir(engineDirectory)
        doFirst {
            commandLine(
                "cargo", "clippy", "--locked", "--offline", "--target", compilerHost.get(),
                "--target-dir", nativeTargetDirectory, "--all-targets", "--", "-D", "warnings",
            )
        }
    }
    tasks.register<Exec>("buildRust${name}Engine") {
        group = "build"
        workingDir(engineDirectory)
        doFirst {
            commandLine(
                "cargo", "build", "--release", "--locked", "--offline", "--target", compilerHost.get(),
                "--target-dir", nativeTargetDirectory,
            )
        }
    }
    clippy
}
tasks.register("rustEnginesCheck") {
    group = "verification"
    description = "Formats, tests and lints all three native engines; does not enable experimental CPU/HAR paths."
    dependsOn(nativeChecks)
}


val paritySimpleperfBuild = gradle.includedBuild("simpleperf-viewer")
val parityNetworkBuild = gradle.includedBuild("network-profiler")
val parityMemoryBuild = gradle.includedBuild("memory-profiler")
val parityPlatformBuild = gradle.includedBuild("platform-core")

tasks.register("rustEngineParityCheck") {
    group = "verification"
    description = "Runs CPU, HAR and HPROF new-vs-legacy parity tests with the Rust engines."
    dependsOn(
        parityPlatformBuild.task(":host-toolchain:test"),
        paritySimpleperfBuild.task(":simpleperf-application:test"),
        parityNetworkBuild.task(":parser-har:test"),
        parityNetworkBuild.task(":analysis-network:test"),
        parityNetworkBuild.task(":network-app:test"),
        parityMemoryBuild.task(":memory-app:test"),
    )
    doFirst {
        if (System.getenv("APS_SIMPLEPERF_ENGINE_TEST").isNullOrBlank() ||
            System.getenv("APS_HAR_ENGINE_TEST").isNullOrBlank() ||
            System.getProperty("aps.hprofEngine.path").isNullOrBlank()
        ) logger.lifecycle("Rust parity: native CPU/HAR/HPROF inputs are not all configured; opt-in cases may skip.")
    }
}
