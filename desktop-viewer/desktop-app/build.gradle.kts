import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import groovy.json.JsonSlurper
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

val appVersion = project.version.toString()
val firefoxProfilerDist = rootProject.layout.projectDirectory.dir("../third_party/firefox-profiler/dist")
val perfettoUiDist = rootProject.layout.projectDirectory.dir("../third_party/perfetto/out/ui/dist")
val perfettoTools = rootProject.layout.projectDirectory.dir("../build/perfetto-tools")
val userDocumentationEnglish = rootProject.layout.projectDirectory.dir("../docs-user")
val userDocumentationChinese = rootProject.layout.projectDirectory.dir("../docs-user-zh")
val profilerAppResources = layout.buildDirectory.dir("generated/profiler-app-resources")
val traceProcessorManifestFile = rootProject.layout.projectDirectory.file("platform-perfetto/trace-processor-manifest.json")
val prepareProfilerAppResources =
    tasks.register<Sync>("prepareProfilerAppResources") {
        exclude("**/.DS_Store")
        inputs.file(firefoxProfilerDist.file("index.html"))
        inputs.file(perfettoUiDist.file("index.html"))
        inputs.dir(userDocumentationEnglish)
        inputs.dir(userDocumentationChinese)
        from(firefoxProfilerDist)
        from(perfettoUiDist) {
            into("perfetto-ui")
        }
        from(perfettoTools) {
            into("perfetto-tools")
        }
        from(userDocumentationEnglish) {
            into("docs-user")
        }
        from(userDocumentationChinese) {
            into("docs-user-zh")
        }
        into(profilerAppResources.map { resources -> resources.dir("common") })
    }

tasks
    .matching { task -> task.name == "prepareAppResources" }
    .configureEach { dependsOn(prepareProfilerAppResources) }

if (!System.getProperty("os.name").lowercase().contains("win")) {
    // The :desktop-app:run dev runtime reads the Trace Processor straight out of the assembled
    // app-resources dir, with no intervening jpackage copy. Ensure it is executable there so the
    // resolver does not reject it in dev mode. (The published .deb/.rpm is fixed separately below.)
    prepareProfilerAppResources.configure {
        val resourcesPath = profilerAppResources.get().asFile.resolve("common/perfetto-tools/trace_processor_shell").absolutePath
        doLast {
            val binary = File(resourcesPath)
            check(binary.isFile) { "Packaged Trace Processor is missing from the assembled app resources: $binary" }
            if (!binary.canExecute()) {
                check(binary.setExecutable(true, false)) { "Unable to make the packaged Trace Processor executable: $binary" }
            }
            check(binary.canExecute()) { "Packaged Trace Processor is not executable after assembly: $binary" }
        }
    }

    // The Compose packaging plugin copies resources with Kotlin File.copyTo, which strips the POSIX
    // executable bit, and each packaging task (packageDeb/packageRpm/createDistributable/
    // createReleaseDistributable) runs jpackage in a single call that builds the app-image and the
    // package together — there is no Gradle hook between image creation and packaging, so the
    // published payload ships the binary at 0644. Repack each produced archive after jpackage
    // finishes: extract, chmod the Trace Processor, repack. CI builds via `packageDeb`/`packageRpm`,
    // so the hook must cover those (not just createDistributable).
    //
    // The doLast action is kept self-contained (only uses the task's own logger/outputs, no script-
    // scope captures) so it is compatible with Gradle's configuration cache.
    val packageTasks = setOf(
        "packageDeb", "packageRpm",
        "createDistributable", "createReleaseDistributable",
    )
    tasks
        .matching { task -> task.name in packageTasks }
        .configureEach {
            val taskOutputs = outputs.files
            doLast {
                val task = this
                fun runCmd(vararg args: String): Int =
                    ProcessBuilder(args.toList())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                        .waitFor()

                // Each packaging task declares a destination directory; the produced .deb/.rpm lives
                // there. Scan the output tree for archives.
                val outDirs = taskOutputs.files.filter { it.isDirectory }
                val archives = if (outDirs.isNotEmpty()) {
                    outDirs.flatMap { dir -> dir.walkTopDown().filter { f -> f.isFile && (f.extension == "deb" || f.extension == "rpm") }.toList() }
                } else {
                    taskOutputs.files.filter { it.isFile && (it.extension == "deb" || it.extension == "rpm") }
                }
                val binaryName = "trace_processor_shell"
                archives.forEach { archive ->
                    if (archive.extension == "deb") {
                        if (runCmd("dpkg-deb", "--version") != 0) {
                            task.logger.warn("dpkg-deb not found; skipping Trace Processor exec-bit repack of $archive (runtime self-heal will cover it).")
                            return@forEach
                        }
                        val workdir = Files.createTempDirectory("deb-repack")
                        try {
                            val root = workdir.resolve("root")
                            check(runCmd("dpkg-deb", "-x", archive.absolutePath, root.toString()) == 0) { "dpkg-deb -x failed for $archive" }
                            check(runCmd("dpkg-deb", "-e", archive.absolutePath, root.resolve("DEBIAN").toString()) == 0) { "dpkg-deb -e failed for $archive" }

                            val binaries = File(root.toString()).walkTopDown().filter { it.name == binaryName && it.isFile }.toList()
                            check(binaries.isNotEmpty()) { "Trace Processor $binaryName not found in .deb payload: $archive" }
                            binaries.forEach { binary ->
                                if (!binary.canExecute()) {
                                    check(binary.setExecutable(true, false)) { "Unable to make Trace Processor executable in .deb: $binary" }
                                }
                            }

                            val repacked = workdir.resolve("repacked.deb")
                            check(runCmd("dpkg-deb", "--build", root.toString(), repacked.toString()) == 0) { "dpkg-deb --build failed for $archive" }
                            check(archive.delete()) { "Unable to replace original .deb: $archive" }
                            check(repacked.toFile().copyTo(archive, overwrite = true) == archive) { "Unable to write repacked .deb: $archive" }

                            val verifyRoot = workdir.resolve("verify")
                            check(runCmd("dpkg-deb", "-x", archive.absolutePath, verifyRoot.toString()) == 0) { "verify dpkg-deb -x failed for $archive" }
                            val verified = File(verifyRoot.toString()).walkTopDown().filter { it.name == binaryName && it.isFile }.toList()
                            check(verified.all { it.canExecute() }) { "Repacked .deb Trace Processor is not executable: $archive" }
                            task.logger.lifecycle("Repacked $archive: Trace Processor marked executable.")
                        } finally {
                            workdir.toFile().deleteRecursively()
                        }
                    } else if (archive.extension == "rpm") {
                        // No robust in-place rpm mode fix without rpmrebuild; rpm installs rely on the
                        // resolver's runtime self-heal.
                        task.logger.warn("RPM exec-bit repack not implemented for $archive; rpm installs rely on runtime self-heal. (.deb is repacked.)")
                    }
                }
            }
        }
}

fun macOsPackageVersion(version: String): String {
    val numericComponents = version.split(".")
    val firstPositiveIndex =
        numericComponents.indexOfFirst { component -> component.toIntOrNull()?.let { it > 0 } == true }
    return when (firstPositiveIndex) {
        -1 -> "1"
        0 -> version
        else -> numericComponents.drop(firstPositiveIndex).joinToString(".")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val hostOs = System.getProperty("os.name").lowercase()
val hostArch =
    when (System.getProperty("os.arch").lowercase()) {
        "x86_64", "amd64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> error("Unsupported desktop host architecture: ${System.getProperty("os.arch")}")
    }
val targetArch = project.findProperty("target.arch")?.toString()?.lowercase() ?: hostArch
val targetJavaHome = project.findProperty("target.javaHome")?.toString()
val targetOs =
    when {
        hostOs.contains("mac") -> "macos"
        hostOs.contains("win") -> "windows"
        hostOs.contains("linux") -> "linux"
        else -> error("Unsupported desktop host operating system: $hostOs")
    }

val verifyPackagedTraceProcessor =
    tasks.register("verifyPackagedTraceProcessor") {
        val binaryName = if (targetOs == "windows") "trace_processor_shell.exe" else "trace_processor_shell"
        val binary = perfettoTools.file(binaryName)
        val hostKey = "$targetOs-$targetArch"
        inputs.file(binary)
        inputs.file(traceProcessorManifestFile)
        val manifestFile = traceProcessorManifestFile.asFile
        doLast {
            val manifest = JsonSlurper().parse(manifestFile) as Map<*, *>
            val version = checkNotNull(manifest["version"]) as String
            val artifacts = checkNotNull(manifest["artifacts"]) as Map<*, *>
            val file = binary.asFile
            check(file.isFile) {
                "Pinned Trace Processor $version is missing: $file. Run scripts/install-trace-processor.sh $hostKey."
            }
            val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
            val expected = checkNotNull((artifacts[hostKey] as? Map<*, *>)?.get("sha256")) {
                "No pinned Trace Processor checksum for $hostKey"
            }.toString()
            check(actual == expected) { "Pinned Trace Processor checksum mismatch for $hostKey" }
        }
    }

prepareProfilerAppResources.configure {
    dependsOn(verifyPackagedTraceProcessor)
}

require(targetArch == "x64" || targetArch == "arm64") {
    "Unsupported target.arch=$targetArch. Compose Desktop supports x64 and arm64 desktop runtimes; Windows x86 is not supported."
}
require(targetArch == hostArch || targetJavaHome != null) {
    "Cross-architecture packaging requires -Ptarget.javaHome to point to a $targetArch JDK."
}

dependencies {
    implementation(libs.aps.ai.core)
    implementation(libs.aps.source.workspace)
    implementation(libs.aps.ui.components)
    implementation(libs.aps.host.toolchain)
    implementation(libs.aps.adb.core)
    implementation(libs.aps.presentation)
    implementation(libs.aps.analysis.engine)
    implementation(libs.aps.app.desktop)
    implementation(libs.aps.method.recording.app)
    implementation(libs.aps.perfetto.app)
    implementation(libs.aps.memory.app)
    implementation(libs.aps.frame.app)
    implementation(libs.aps.startup.app)
    implementation(libs.aps.battery.app)
    implementation(libs.aps.network.app)
    implementation(libs.aps.gpu.integration.app)
    implementation(libs.aps.benchmark.app)
    implementation(libs.aps.screencast.app)
    when {
        hostOs.contains("mac") && targetArch == "x64" -> implementation(compose.desktop.macos_x64)
        hostOs.contains("mac") && targetArch == "arm64" -> implementation(compose.desktop.macos_arm64)
        hostOs.contains("win") && targetArch == "x64" -> implementation(compose.desktop.windows_x64)
        hostOs.contains("linux") && targetArch == "x64" -> implementation(compose.desktop.linux_x64)
        hostOs.contains("linux") && targetArch == "arm64" -> implementation(compose.desktop.linux_arm64)
        else -> error("Unsupported Compose Desktop target: $hostOs-$targetArch")
    }
    implementation(libs.compose.components.resources)
    implementation(libs.compose.material3)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.aps.platform.perfetto)
    testImplementation(libs.aps.profiler.contracts)
    testImplementation(libs.aps.analysis.memory)
    testImplementation(libs.aps.analysis.frame)
    testImplementation(libs.aps.analysis.startup)
    testImplementation(libs.aps.startup.model)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.androidperformancestudio.desktop.MainKt"
        jvmArgs("-Dapple.awt.application.name=AndroidPerfermanceStudio")
        jvmArgs("-Dagentperf.version=$appVersion")
        // Bound HPROF parsing so oversized imports fail with a recoverable Java OOME
        // instead of letting the operating system terminate the desktop process.
        jvmArgs("-Xmx4g")

        if (targetJavaHome != null) {
            javaHome = targetJavaHome
        }

        nativeDistributions {
            appResourcesRootDir.set(profilerAppResources)
            // The minimized jpackage runtime cannot infer reflective/com.sun HTTP usage, so list the
            // required JDK modules explicitly: java.net.http for AI transport, jdk.httpserver for the
            // PerfettoUiServer, java.sql for the SQLite session stores.
            modules("java.net.http")
            modules("jdk.httpserver")
            modules("java.sql")
            targetFormats(
                TargetFormat.Dmg,
                TargetFormat.Pkg,
                TargetFormat.Msi,
                TargetFormat.Exe,
                TargetFormat.Deb,
                TargetFormat.Rpm,
            )
            packageName = "AndroidPerfermanceStudio"
            packageVersion = appVersion
            windows {
                iconFile.set(project.file("src/main/package/windows/app-icon.ico"))
                shortcut = true
            }
            linux {
                iconFile.set(project.file("src/main/package/linux/app-icon.png"))
                shortcut = true
            }
            macOS {
                iconFile.set(project.file("src/main/package/macos/app-icon.icns"))
                // jpackage requires a positive first component for macOS app images.
                val macVersion = macOsPackageVersion(appVersion)
                packageVersion = macVersion
                packageBuildVersion = macVersion
            }
        }
    }
}

// Keep the development process independent from the application JAR. Gradle may rebuild that JAR
// while an existing app instance is still running; loading a settings screen afterwards must not
// fail because the class loader is holding an obsolete JAR handle.
tasks.withType<JavaExec>().configureEach {
    if (name == "run") {
        val developmentRuntimeClasspath = sourceSets["main"].runtimeClasspath
        doFirst {
            classpath = developmentRuntimeClasspath
        }
    }
}

// Suppress known ProGuard warnings from transitive dependency version mismatches.
// These references are guarded at runtime (reflection / optional deps) and never
// actually invoked, but ProGuard treats missing members as hard errors.
tasks.matching { it.name == "proguardReleaseJars" }.configureEach {
    val projectDir = project.projectDir
    doFirst {
        val customRules = project.file("proguard-rules.pro")
        if (customRules.exists()) {
            val rootConfig = project.file("build/compose/tmp/proguardReleaseJars/root-config.pro")
            if (rootConfig.exists()) {
                rootConfig.appendText(
                    "\n# Custom ProGuard rules - suppress known dependency warnings\n" +
                        customRules.readText() + "\n"
                )
            }
        }
    }
}
