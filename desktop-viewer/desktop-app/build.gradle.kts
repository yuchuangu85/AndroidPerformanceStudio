import groovy.json.JsonSlurper
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.RandomAccessFile
import java.io.Closeable
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

val appVersion = project.version.toString()
val desktopMaxHeap = providers.gradleProperty("apsDesktopMaxHeap").orElse("4g")
val firefoxProfilerDist = rootProject.layout.projectDirectory.dir("../third_party/firefox-profiler/dist")
val perfettoUiDist = rootProject.layout.projectDirectory.dir("../third_party/perfetto/out/ui/dist")
val perfettoTools = rootProject.layout.projectDirectory.dir("../build/perfetto-tools")
val adbTools = rootProject.layout.projectDirectory.dir("../build/adb-tools")
val adbToolsPreparationScript = rootProject.layout.projectDirectory.file("../scripts/prepare-adb-tools.py")
val userDocumentationEnglish = rootProject.layout.projectDirectory.dir("../docs-user")
val userDocumentationChinese = rootProject.layout.projectDirectory.dir("../docs-user-zh")
val composeAgentBundle =
    rootProject.layout.projectDirectory.dir("layout-inspector/compose-agent-build/build/compose-agent-bundle")
val profilerAppResources = layout.buildDirectory.dir("generated/profiler-app-resources")
val traceProcessorManifestFile = rootProject.layout.projectDirectory.file("platform-perfetto/trace-processor-manifest.json")
val prepareProfilerAppResources =
    tasks.register<Sync>("prepareProfilerAppResources") {
        exclude("**/.DS_Store")
        inputs.file(firefoxProfilerDist.file("index.html"))
        inputs.file(perfettoUiDist.file("index.html"))
        inputs.file(traceProcessorManifestFile)
        inputs.dir(adbTools)
        inputs.dir(userDocumentationEnglish)
        inputs.dir(userDocumentationChinese)
        from(firefoxProfilerDist)
        from(perfettoUiDist) {
            into("perfetto-ui")
        }
        from(perfettoTools) {
            into("perfetto-tools")
        }
        from(adbTools) {
            into("adb-tools")
        }
        from(userDocumentationEnglish) {
            into("docs-user")
        }
        from(userDocumentationChinese) {
            into("docs-user-zh")
        }
        from(composeAgentBundle) {
            into("compose-agent-bundle")
        }
        into(profilerAppResources.map { resources -> resources.dir("common") })
    }

tasks
    .matching { task -> task.name == "prepareAppResources" }
    .configureEach { dependsOn(prepareProfilerAppResources) }

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

if (targetOs == "linux") {
    val launcherName = "aps-script-sandbox"
    val launcherSource = rootProject.layout.projectDirectory.file(
        "ai-platform/ai-platform-script-runtime/src/main/native/linux/$launcherName.c",
    )
    val launcherPrebuilt = providers.gradleProperty("apsScriptSandboxLauncherBinary")
    val nativeLauncherTarget = targetArch == hostArch
    val launcherBuilt = layout.buildDirectory.file("native/script-sandbox/$launcherName")
    val launcherBinary = launcherPrebuilt.orNull?.let { rootProject.file(it) } ?: launcherBuilt.get().asFile
    val launcherResources = layout.buildDirectory.dir("generated/script-sandbox")
    val buildLauncher = tasks.register<Exec>("buildScriptSandboxLauncher") {
        group = "distribution"
        inputs.file(launcherSource)
        outputs.file(launcherBuilt)
        onlyIf { !launcherPrebuilt.isPresent && nativeLauncherTarget }
        doFirst { launcherBuilt.get().asFile.parentFile.mkdirs() }
        commandLine(
            "cc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2", "-fPIE", "-pie",
            "-Wl,-z,relro,-z,now", launcherSource.asFile.absolutePath, "-o", launcherBuilt.get().asFile.absolutePath,
        )
    }
    val prepareLauncher = tasks.register("prepareScriptSandboxLauncher") {
        group = "distribution"
        if (!launcherPrebuilt.isPresent && nativeLauncherTarget) dependsOn(buildLauncher)
        inputs.file(launcherBinary)
        inputs.property("targetArch", targetArch)
        outputs.dir(launcherResources)
        val binary = launcherBinary
        val output = launcherResources.get().asFile
        val architecture = targetArch
        doLast {
            check(binary.isFile && binary.length() in 64L..1_048_576L) { "Linux sandbox launcher is missing or invalid; cross-architecture packaging requires -PapsScriptSandboxLauncherBinary." }
            val bytes = binary.inputStream().use { it.readNBytes(64) }
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val expectedMachine = if (architecture == "x64") 62 else 183
            check(header.getInt(0) == 0x464c457f && bytes[4].toInt() == 2 && bytes[5].toInt() == 1 &&
                (header.getShort(18).toInt() and 0xffff) == expectedMachine
            ) { "Linux sandbox launcher architecture does not match $architecture." }
            check(output.isDirectory || output.mkdirs())
            val staged = binary.copyTo(File(output, launcherName), overwrite = true)
            check(staged.canExecute() || staged.setExecutable(true, false))
            val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(staged.readBytes()))
            File(output, "$launcherName.sha256").writeText("$digest\n")
        }
    }
    prepareProfilerAppResources.configure {
        dependsOn(prepareLauncher)
        from(launcherResources) { into("script-sandbox") }
    }
}
if (targetOs != "windows") {
    tasks
        .matching { task -> task.name == "createDistributable" || task.name == "createReleaseDistributable" }
        .configureEach {
            inputs.file(traceProcessorManifestFile)
            // Task actions may capture values, but not the enclosing Gradle script.
            val manifestFile = traceProcessorManifestFile.asFile
            val hostKey = "$targetOs-$targetArch"
            doLast {
                val packagedBinaries =
                    outputs.files.asFileTree
                        .matching { include("**/resources/perfetto-tools/trace_processor_shell") }
                        .files
                check(packagedBinaries.isNotEmpty()) { "Packaged Trace Processor is missing from the distributable" }
                val manifest = JsonSlurper().parse(manifestFile) as Map<*, *>
                val artifacts = checkNotNull(manifest["artifacts"]) as Map<*, *>
                val expected =
                    checkNotNull((artifacts[hostKey] as? Map<*, *>)?.get("sha256")) {
                        "No pinned Trace Processor checksum for $hostKey"
                    }.toString()
                packagedBinaries.forEach { binary ->
                    check(binary.canExecute() || binary.setExecutable(true, false)) {
                        "Unable to make the packaged Trace Processor executable: $binary"
                    }
                    val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary.readBytes()))
                    check(actual == expected) {
                        "Packaged Trace Processor checksum mismatch for $hostKey: $binary"
                    }
                }
            }
        }
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
            val expected =
                checkNotNull((artifacts[hostKey] as? Map<*, *>)?.get("sha256")) {
                    "No pinned Trace Processor checksum for $hostKey"
                }.toString()
            check(actual == expected) { "Pinned Trace Processor checksum mismatch for $hostKey" }
        }
    }

val verifyPackagedAdbTools =
    tasks.register<Exec>("verifyPackagedAdbTools") {
        group = "verification"
        description = "Verifies the target ADB executable, native companion libraries, license notices and checksums."
        inputs.dir(adbTools)
        inputs.file(adbToolsPreparationScript)
        commandLine(
            "python3",
            adbToolsPreparationScript.asFile.absolutePath,
            "--verify-only",
            "--output-dir",
            adbTools.asFile.absolutePath,
            "--host",
            "$targetOs-$targetArch",
        )
    }

tasks
    .matching { task -> task.name == "createDistributable" || task.name == "createReleaseDistributable" }
    .configureEach {
        val binaryName = if (targetOs == "windows") "adb.exe" else "adb"
        val windows = targetOs == "windows"
        doLast {
            val packagedBinaries =
                outputs.files.asFileTree
                    .matching { include("**/resources/adb-tools/$binaryName") }
                    .files
            check(packagedBinaries.isNotEmpty()) { "Packaged ADB is missing from the distributable" }
            packagedBinaries.forEach { binary ->
                check(windows || binary.canExecute() || binary.setExecutable(true, false)) {
                    "Unable to make the packaged ADB executable: $binary"
                }
                val manifest = Properties().apply { File(binary.parentFile, "manifest.properties").inputStream().use(::load) }
                val count = checkNotNull(manifest.getProperty("file.count")?.toIntOrNull())
                repeat(count) { index ->
                    val relative = checkNotNull(manifest.getProperty("file.$index.path"))
                    val file = File(binary.parentFile, relative)
                    check(file.isFile) { "ADB companion file is missing from the distributable: $relative" }
                    val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
                    check(actual == manifest.getProperty("file.$index.sha256")) {
                        "Packaged ADB checksum mismatch: $relative"
                    }
                }
            }
        }
    }

// The Android agent bundle is host-independent. Release CI builds it once on Linux x64 and
// downloads it for each desktop target; packaging must never silently omit inspection support.
val verifyPackagedComposeAgentBundle =
    tasks.register("verifyPackagedComposeAgentBundle") {
        inputs.dir(composeAgentBundle)
        val bundleDirectory = composeAgentBundle.asFile
        doLast {
            check(bundleDirectory.isDirectory) {
                "Compose agent bundle is missing. Build :layout-inspector:compose-agent-build:verifyComposeAgentBundle first."
            }
            val root = bundleDirectory.toPath().toRealPath()
            val manifest =
                Properties().apply {
                    root
                        .resolve("manifest.properties")
                        .toFile()
                        .inputStream()
                        .use { load(it) }
                }
            val artifacts =
                linkedMapOf(
                    "service.sha256" to "lib_ui_inspector_service.jar",
                    "payload.sha256" to "lib_ui_inspector_payload.jar",
                    "view.sha256" to "view-inspector.jar",
                )
            listOf("armeabi-v7a", "arm64-v8a", "x86_64").forEach { abi ->
                artifacts["agent.$abi.sha256"] = "agent/$abi/lib_ui_inspector_agent.so"
            }
            artifacts.forEach { (key, relative) ->
                val path = root.resolve(relative).toRealPath()
                val file = path.toFile()
                check(path.startsWith(root) && file.isFile && file.length() > 0) {
                    "Missing or invalid Compose agent bundle file: $relative"
                }
                val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
                check(actual == manifest.getProperty(key)) { "Compose agent checksum mismatch: $relative" }
                if (relative.endsWith(".jar")) {
                    ZipFile(file).use { zip ->
                        check(zip.getEntry("classes.dex") != null) { "Compose agent jar is not dexed: $relative" }
                    }
                }
            }
        }
    }

prepareProfilerAppResources.configure {
    dependsOn(verifyPackagedTraceProcessor)
    dependsOn(verifyPackagedAdbTools)
    dependsOn(verifyPackagedComposeAgentBundle)
}

require(targetArch == "x64" || targetArch == "arm64") {
    "Unsupported target.arch=$targetArch. Compose Desktop supports x64 and arm64 desktop runtimes; Windows x86 is not supported."
}
require(targetArch == hostArch || targetJavaHome != null) {
    "Cross-architecture packaging requires -Ptarget.javaHome to point to a $targetArch JDK."
}

dependencies {
    implementation("com.androidperformancestudio:ai-platform-presentation:0.1.0-SNAPSHOT")
    implementation("com.androidperformancestudio:ai-platform-app:0.1.0-SNAPSHOT")
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
    implementation(libs.aps.memory.model)
    implementation(libs.aps.frame.app)
    implementation(libs.aps.startup.app)
    implementation(libs.aps.battery.app)
    implementation(libs.aps.network.app)
    implementation(libs.aps.gpu.integration.app)
    implementation(libs.aps.benchmark.app)
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
    implementation(libs.jbr.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.aps.platform.perfetto)
    testImplementation(libs.aps.profiler.contracts)
    testImplementation(libs.aps.analysis.memory)
    testImplementation(libs.aps.analysis.frame)
    testImplementation(libs.aps.analysis.startup)
    testImplementation(libs.aps.startup.model)
    testImplementation("com.androidperformancestudio:profile-analysis:0.1.0-SNAPSHOT")
    testImplementation("com.androidperformancestudio:simpleperf-storage-sqlite:0.1.0-SNAPSHOT")
    testImplementation("com.androidperformancestudio:analysis-rules:0.1.0-SNAPSHOT")
    testRuntimeOnly(libs.junit.platform.launcher)
}

val liveConfiguredAcceptance = providers.environmentVariable("APS_RUN_LIVE_CONFIGURED_PROVIDER").map { it == "true" }.orElse(false)
tasks.test {
    useJUnitPlatform()
    if (liveConfiguredAcceptance.get()) {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        maxParallelForks = 1
    }
}

compose.desktop {
    application {
        mainClass = "com.androidperformancestudio.desktop.MainKt"
        jvmArgs("-Dapple.awt.application.name=AndroidPerfermanceStudio")
        jvmArgs("-Dagentperf.version=$appVersion")
        // Large HPROF graphs need headroom beyond parsing for class and reference drill-down.
        // Keep a finite cap so oversized imports produce a recoverable Java OOME.
        jvmArgs("-Xmx${desktopMaxHeap.get()}")

        if (targetJavaHome != null) {
            javaHome = targetJavaHome
        }

        nativeDistributions {
            appResourcesRootDir.set(profilerAppResources)
            // The minimized jpackage runtime cannot infer reflective/com.sun HTTP usage, so list the
            // required JDK modules explicitly: java.net.http for AI transport, jdk.httpserver for the
            // PerfettoUiServer, java.sql for SQLite, java.management for coroutine CPU metering.
            modules("java.net.http")
            modules("jdk.httpserver")
            modules("java.sql")
            modules("java.management")
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

class DevelopmentRuntimeSnapshot(originalClasspath: List<File>) : Closeable {
    val directory: File = Files.createTempDirectory("aps-desktop-runtime-").toFile()
    val entries: List<File>

    init {
        try {
            entries = originalClasspath.mapIndexedNotNull { index, source ->
                // Missing output directories are normal when a source set has no Java sources.
                if (!source.exists()) {
                    check(!source.extension.equals("jar", ignoreCase = true)) { "Missing runtime JAR: $source" }
                    return@mapIndexedNotNull null
                }
                val target = File(directory, "$index/${source.name}")
                check(target.parentFile.mkdirs()) { "Cannot create runtime snapshot directory: $target" }
                if (source.isDirectory) {
                    source.copyRecursively(target, overwrite = false)
                } else {
                    source.copyTo(target, overwrite = false)
                    if (target.extension.equals("jar", ignoreCase = true)) {
                        ZipFile(target).use { zip ->
                            zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                                zip.getInputStream(entry).use { it.copyTo(OutputStream.nullOutputStream()) }
                            }
                        }
                    }
                }
                target
            }
        } catch (error: Exception) {
            close()
            throw GradleException("Cannot snapshot the desktop runtime classpath. Retry after other builds finish.", error)
        }
    }

    override fun close() {
        if (!directory.deleteRecursively()) {
            System.err.println("[desktop-run] Could not remove runtime snapshot: $directory")
        }
    }
}

abstract class DevelopmentRuntimeSnapshots : BuildService<BuildServiceParameters.None>, AutoCloseable {
    private val snapshots = CopyOnWriteArrayList<DevelopmentRuntimeSnapshot>()

    fun capture(classpath: List<File>): DevelopmentRuntimeSnapshot =
        DevelopmentRuntimeSnapshot(classpath).also { snapshots.add(it) }

    override fun close() {
        snapshots.forEach { it.close() }
    }
}

val developmentRuntimeSnapshots =
    gradle.sharedServices.registerIfAbsent("desktopDevelopmentRuntimeSnapshots", DevelopmentRuntimeSnapshots::class) {}

// All runtime entries, including included-build resources, must stay unchanged for this process.
// Source-set outputs alone still reference dependency JARs that other Gradle builds can overwrite.
tasks.withType<JavaExec>().configureEach {
    if (name == "run") {
        val developmentRuntimeClasspath = sourceSets["main"].runtimeClasspath
        dependsOn(developmentRuntimeClasspath)
        val isolatedRuntimeClasspath = objects.fileCollection()
        val snapshots = developmentRuntimeSnapshots
        usesService(snapshots)
        doFirst {
            val snapshot = snapshots.get().capture(developmentRuntimeClasspath.files.toList())
            isolatedRuntimeClasspath.setFrom(snapshot.entries)
            classpath = isolatedRuntimeClasspath
            logger.lifecycle("[desktop-run] Isolated runtime classpath: ${snapshot.directory}")
        }
    }
}

tasks.register("verifyDevelopmentRuntimeIsolation") {
    group = "verification"
    description = "Checks that desktop runtime resources survive original classpath files being rebuilt."
    doLast {
        val fixture = Files.createTempDirectory("aps-runtime-isolation-test-").toFile()
        try {
            fun writeJar(file: File, value: String) {
                file.parentFile.mkdirs()
                ZipOutputStream(file.outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("composeResources/values/strings.main.cvr"))
                    zip.write(value.toByteArray())
                    zip.closeEntry()
                }
            }
            fun readResource(file: File): String = ZipFile(file).use { zip ->
                zip.getInputStream(zip.getEntry("composeResources/values/strings.main.cvr")).bufferedReader().use { it.readText() }
            }
            val firstJar = File(fixture, "first/presentation.jar")
            val secondJar = File(fixture, "second/presentation.jar")
            val classes = File(fixture, "classes").apply { mkdirs() }
            val classFile = File(classes, "Main.class").apply { writeText("original-class") }
            writeJar(firstJar, "original-string")
            writeJar(secondJar, "other-module")
            val classpath = listOf(firstJar, secondJar, classes, File(fixture, "missing-output"))
            DevelopmentRuntimeSnapshot(classpath).use { first ->
                check(first.entries.size == 3) { "Missing optional output directories must not remain live classpath entries" }
                check(first.entries[0] != first.entries[1]) { "Same-named JARs must remain distinct" }
                ZipFile(first.entries[0]).use { opened ->
                    writeJar(firstJar, "rebuilt-string-with-a-different-length")
                    classFile.writeText("rebuilt-class")
                    check(opened.getInputStream(opened.entries().nextElement()).bufferedReader().use { it.readText() } == "original-string")
                }
                check(readResource(first.entries[0]) == "original-string")
                check(readResource(first.entries[1]) == "other-module")
                check(File(first.entries[2], "Main.class").readText() == "original-class")
                DevelopmentRuntimeSnapshot(classpath).use { second ->
                    check(second.directory != first.directory)
                    check(readResource(second.entries[0]) == "rebuilt-string-with-a-different-length")
                    check(readResource(first.entries[0]) == "original-string")
                }
                check(first.directory.isDirectory) { "Another snapshot must not remove this runtime" }
            }
            check(firstJar.isFile && secondJar.isFile && classFile.readText() == "rebuilt-class")
            val missingJar = File(fixture, "missing.jar")
            check(runCatching { DevelopmentRuntimeSnapshot(listOf(missingJar)).use {} }.isFailure)
            val invalidJar = File(fixture, "invalid.jar").apply { writeText("not a zip archive") }
            check(runCatching { DevelopmentRuntimeSnapshot(listOf(invalidJar)).use {} }.isFailure)
            val invalidLocalHeader = File(fixture, "invalid-local-header.jar")
            writeJar(invalidLocalHeader, "corrupted-local-entry")
            RandomAccessFile(invalidLocalHeader, "rw").use { it.writeInt(0) }
            ZipFile(invalidLocalHeader).use { check(it.size() == 1) }
            check(runCatching { readResource(invalidLocalHeader) }.exceptionOrNull() is java.util.zip.ZipException)
            check(runCatching { DevelopmentRuntimeSnapshot(listOf(invalidLocalHeader)).use {} }.isFailure) {
                "Snapshot validation must read JAR entries, not only the central directory"
            }
            logger.lifecycle("Development runtime isolation verified (JAR rewrite, directory rewrite, order, and independent launches).")
        } finally {
            fixture.deleteRecursively()
        }
    }
}

tasks.named("check") {
    dependsOn("verifyDevelopmentRuntimeIsolation")
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
                        customRules.readText() + "\n",
                )
            }
        }
    }
}
