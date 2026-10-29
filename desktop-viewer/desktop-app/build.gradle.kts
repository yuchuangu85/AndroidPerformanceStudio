import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties
import java.util.zip.ZipFile

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
                val expected = checkNotNull((artifacts[hostKey] as? Map<*, *>)?.get("sha256")) {
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
            val expected = checkNotNull((artifacts[hostKey] as? Map<*, *>)?.get("sha256")) {
                "No pinned Trace Processor checksum for $hostKey"
            }.toString()
            check(actual == expected) { "Pinned Trace Processor checksum mismatch for $hostKey" }
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
            val manifest = Properties().apply {
                root.resolve("manifest.properties").toFile().inputStream().use { load(it) }
            }
            val artifacts = linkedMapOf(
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
    dependsOn(verifyPackagedComposeAgentBundle)
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
