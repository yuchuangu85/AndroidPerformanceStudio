import org.gradle.api.tasks.testing.Test

plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val rustHprofEngineDirectory = rootProject.layout.projectDirectory.dir("hprof-engine-rust")
val rustHprofEngineTargetDirectory = rustHprofEngineDirectory.dir("target")
val rustHprofEngineExecutable =
    if (System.getProperty("os.name").lowercase().contains("win")) "aps-hprof-engine.exe" else "aps-hprof-engine"
val rustHprofEngineBinary = rustHprofEngineTargetDirectory.file("release/$rustHprofEngineExecutable")
val rustCompilerDetails =
    providers
        .exec {
            commandLine("rustc", "-vV")
        }.standardOutput.asText

val buildRustHprofEngine =
    tasks.register<Exec>("buildRustHprofEngine") {
        group = "build"
        description = "Builds the native Rust HPROF sidecar with Cargo (release, locked, offline)."
        workingDir(rustHprofEngineDirectory)
        inputs.file(rustHprofEngineDirectory.file("Cargo.toml"))
        inputs.file(rustHprofEngineDirectory.file("Cargo.lock"))
        inputs.dir(rustHprofEngineDirectory.dir("src"))
        outputs.file(rustHprofEngineBinary)
        // Cargo owns incremental compilation, including compiler and configuration changes.
        outputs.upToDateWhen { false }

        val path = providers.environmentVariable("PATH").orElse("")
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val compilerDetails = rustCompilerDetails
        val targetDirectory = rustHprofEngineTargetDirectory.asFile
        val outputBinary = rustHprofEngineBinary.asFile
        val binaryName = rustHprofEngineExecutable
        doFirst {
            val executableSuffix = if (windows) ".exe" else ""
            val toolDirectories = path.get().split(File.pathSeparator).filter(String::isNotBlank)
            val cargo = toolDirectories.map { File(it, "cargo$executableSuffix") }.firstOrNull { it.isFile && it.canExecute() }
            check(cargo != null) {
                "Cargo is required for buildRustHprofEngine. Install Rust 1.85.1 or newer and put cargo and rustc on PATH."
            }
            check(toolDirectories.any { File(it, "rustc$executableSuffix").let { tool -> tool.isFile && tool.canExecute() } }) {
                "rustc is required for buildRustHprofEngine. Install Rust 1.85.1 or newer and put rustc on PATH."
            }
            val nativeTarget =
                compilerDetails
                    .get()
                    .lineSequence()
                    .firstOrNull { it.startsWith("host: ") }
                    ?.removePrefix("host: ")
            check(!nativeTarget.isNullOrBlank()) { "Unable to determine the native Rust target from rustc -vV." }
            // An explicit native target prevents CARGO_BUILD_TARGET or Cargo config from selecting another architecture.
            commandLine(
                cargo.absolutePath,
                "build",
                "--release",
                "--locked",
                "--offline",
                "--bin",
                "aps-hprof-engine",
                "--target",
                nativeTarget,
                "--target-dir",
                targetDirectory.absolutePath,
            )
        }
        doLast {
            val nativeTarget =
                compilerDetails
                    .get()
                    .lineSequence()
                    .first { it.startsWith("host: ") }
                    .removePrefix("host: ")
            val compiledBinary = File(targetDirectory, "$nativeTarget/release/$binaryName")
            check(compiledBinary.isFile && compiledBinary.length() > 0) {
                "Cargo did not produce the native Rust HPROF sidecar: $compiledBinary"
            }
            check(outputBinary.parentFile.isDirectory || outputBinary.parentFile.mkdirs()) {
                "Unable to create Rust HPROF output directory: ${outputBinary.parentFile}"
            }
            compiledBinary.copyTo(outputBinary, overwrite = true)
            check(windows || outputBinary.canExecute() || outputBinary.setExecutable(true, false)) {
                "Unable to make the Rust HPROF sidecar executable: $outputBinary"
            }
        }
    }

tasks.withType<Test>().configureEach {
    dependsOn(buildRustHprofEngine)
    inputs.file(rustHprofEngineBinary).withPathSensitivity(PathSensitivity.NONE)
    systemProperty("aps.hprofEngine.path", rustHprofEngineBinary.asFile.absolutePath)
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
    implementation(libs.compose.components.resources)
}
