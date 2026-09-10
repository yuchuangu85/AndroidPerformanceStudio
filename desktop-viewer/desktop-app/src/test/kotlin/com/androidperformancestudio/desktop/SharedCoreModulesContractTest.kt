package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharedCoreModulesContractTest {
    @Test
    fun `root build exposes the AI and source workspace libraries as composite builds`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        val settings = Files.readString(desktopViewer.resolve("settings.gradle.kts"))

        assertTrue(settings.contains("includeBuild(\"ai-core\")"))
        assertTrue(settings.contains("includeBuild(\"source-workspace\")"))
        assertTrue(
            Files.readString(desktopViewer.resolve("layout-inspector/presentation/build.gradle.kts"))
                .contains("com.androidperformancestudio:ai-core:0.1.0-SNAPSHOT"),
        )
    }

    @Test
    fun `shared logic libraries remain UI independent`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()

        listOf("ai-core", "source-workspace").forEach { module ->
            val buildScript = Files.readString(desktopViewer.resolve("$module/build.gradle.kts"))
            assertTrue(buildScript.contains("`java-library`"))
            assertFalse(buildScript.contains("org.jetbrains.compose"))
            assertFalse(buildScript.contains("ui-components"))
        }
    }

    @Test
    fun `root build exposes neutral profiler contracts through platform core`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        val rootSettings = Files.readString(desktopViewer.resolve("settings.gradle.kts"))
        val platformSettings = Files.readString(desktopViewer.resolve("platform-core/settings.gradle.kts"))
        val contractsBuild = Files.readString(desktopViewer.resolve("platform-core/profiler-contracts/build.gradle.kts"))
        val compatibilityBuild = Files.readString(desktopViewer.resolve("simpleperf-viewer/profile-model/build.gradle.kts"))

        assertTrue(rootSettings.contains("includeBuild(\"platform-core\")"))
        assertTrue(platformSettings.contains("\":profiler-contracts\""))
        assertTrue(contractsBuild.contains("`java-library`"))
        assertFalse(contractsBuild.contains("org.jetbrains.compose"))
        assertTrue(compatibilityBuild.contains("com.androidperformancestudio:profiler-contracts"))
    }

    @Test
    fun `host process execution has one neutral JVM implementation`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        val platformSettings = Files.readString(desktopViewer.resolve("platform-core/settings.gradle.kts"))
        val hostRunner =
            Files.readString(
                desktopViewer.resolve(
                    "platform-core/host-toolchain/src/main/kotlin/" +
                        "com/androidperformancestudio/platform/toolchain/HostProcessRunner.kt",
                ),
            )
        val adbClient =
            Files.readString(
                desktopViewer.resolve(
                    "platform-core/adb-core/src/main/kotlin/com/androidperformancestudio/platform/adb/AdbClient.kt",
                ),
            )
        val perfettoCapture =
            Files.readString(
                desktopViewer.resolve(
                    "perfetto-viewer/perfetto-capture/src/main/kotlin/" +
                        "com/androidperformancestudio/perfetto/capture/PerfettoCaptureSession.kt",
                ),
            )

        assertTrue(platformSettings.contains("\":host-toolchain\""))
        assertTrue(hostRunner.contains("class JvmHostProcessRunner"))
        assertTrue(hostRunner.contains("ProcessBuilder("))
        assertFalse(adbClient.contains("ProcessBuilder("))
        assertTrue(adbClient.contains("JvmHostProcessRunner"))
        listOf("platform-toolchain", "device-adb").forEach { legacyModule ->
            val legacyRoot = desktopViewer.resolve("simpleperf-viewer/$legacyModule")
            assertFalse(Files.exists(legacyRoot.resolve("build.gradle.kts")), "Legacy module build script must be removed: $legacyModule")
            assertFalse(Files.exists(legacyRoot.resolve("src")), "Legacy module sources must be removed: $legacyModule")
        }
        assertFalse(Files.exists(desktopViewer.resolve("perfetto-viewer/perfetto-trace-processor/build.gradle.kts")))
        val perfettoSettings = Files.readString(desktopViewer.resolve("platform-perfetto/settings.gradle.kts"))
        assertTrue(perfettoSettings.contains(":platform-perfetto"))
        assertTrue(perfettoCapture.contains("AdbClient"))
        assertFalse(perfettoCapture.contains("ProcessBuilder("))
        val legacyPackage =
            desktopViewer.resolve(
                "platform-core/host-toolchain/src/main/kotlin/com/androidperformancestudio/toolchain",
            )
        assertFalse(Files.exists(legacyPackage), "Legacy host-toolchain compatibility package must be removed")
        val legacyImports =
            Files.walk(desktopViewer).use { paths ->
                paths
                    .filter {
                        Files.isRegularFile(it) &&
                            it.toString().endsWith(".kt") &&
                            it.toString().contains("/src/main/")
                    }
                    .filter { Files.readString(it).contains("com.androidperformancestudio.toolchain") }
                    .map { desktopViewer.relativize(it).toString() }
                    .toList()
            }
        assertTrue(legacyImports.isEmpty(), "Legacy host-toolchain imports must be removed: $legacyImports")
    }

    @Test
    fun `frame startup and battery resolve shared infrastructure from platform core`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()

        listOf("frame-profiler", "startup-profiler", "battery-profiler").forEach { build ->
            val settings = Files.readString(desktopViewer.resolve("$build/settings.gradle.kts"))
            assertTrue(settings.contains("includeBuild(\"../platform-core\")"))
            assertFalse(settings.contains("includeBuild(\"../simpleperf-viewer\")"))
        }
        assertTrue(Files.readString(desktopViewer.resolve("frame-profiler/settings.gradle.kts")).contains("includeBuild(\"../platform-perfetto\")"))
        assertTrue(Files.readString(desktopViewer.resolve("startup-profiler/settings.gradle.kts")).contains("includeBuild(\"../platform-perfetto\")"))
    }

    @Test
    fun `remaining feature builds do not own shared process or Simpleperf infrastructure`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()

        listOf("network-profiler", "gpu-inspector-integration", "benchmark-regression").forEach { build ->
            val settings = Files.readString(desktopViewer.resolve("$build/settings.gradle.kts"))
            assertFalse(settings.contains("includeBuild(\"../simpleperf-viewer\")"))
        }
        listOf(
            "network-profiler/capture-network/src/main/kotlin/" +
                "com/androidperformancestudio/network/capture/NetworkAgentCapture.kt",
            "gpu-inspector-integration/agi-toolchain/src/main/kotlin/" +
                "com/androidperformancestudio/gpu/toolchain/AgiToolchain.kt",
        ).forEach { source ->
            assertFalse(Files.readString(desktopViewer.resolve(source)).contains("ProcessBuilder("))
        }
    }

    @Test
    fun `profiler business modules never construct adb processes directly`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        val allowed = desktopViewer.resolve("platform-core/host-toolchain/src/main/kotlin/com/androidperformancestudio/platform/toolchain/HostProcessRunner.kt")
        val offenders =
            Files.walk(desktopViewer).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") && it.toString().contains("/src/main/") }
                    .filter { it != allowed }
                    .filter {
                        val source = Files.readString(it)
                        source.contains("ProcessBuilder(") && source.contains("adb", ignoreCase = true)
                    }
                    .map { desktopViewer.relativize(it).toString() }
                    .toList()
            }
        assertTrue(offenders.isEmpty(), "ADB execution must be routed through platform-core: $offenders")
    }

    @Test
    fun `Trace Processor uses a pinned dynamic-port resolver without PATH fallback`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        val resolver =
            Files.readString(
                desktopViewer.resolve(
                    "platform-perfetto/platform-perfetto/src/main/kotlin/" +
                        "com/androidperformancestudio/platform/perfetto/TraceProcessorToolResolver.kt",
                ),
            )
        val context =
            Files.readString(
                desktopViewer.resolve(
                    "platform-perfetto/platform-perfetto/src/main/kotlin/" +
                        "com/androidperformancestudio/platform/perfetto/TraceAnalysisContext.kt",
                ),
            )
        assertTrue(resolver.contains("PINNED_TRACE_PROCESSOR_VERSION"))
        assertFalse(resolver.contains("System.getenv(\"PATH\")"))
        assertFalse(resolver.contains("Path.of(\"trace_processor_shell\")"))
        assertTrue(context.contains("ServerSocket(0,"))
        assertFalse(context.contains("9001"))
        assertFalse(context.contains("9002"))
    }

    @Test
    fun `packaging contract covers every supported Trace Processor host`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        val manifest = Files.readString(desktopViewer.resolve("platform-perfetto/trace-processor-manifest.json"))
        val packaging = Files.readString(desktopViewer.resolve("desktop-app/build.gradle.kts"))
        val releaseWorkflow = Files.readString(desktopViewer.resolve("../.github/workflows/release.yml"))
        val resolver =
            Files.readString(
                desktopViewer.resolve(
                    "platform-perfetto/platform-perfetto/src/main/kotlin/" +
                        "com/androidperformancestudio/platform/perfetto/TraceProcessorToolResolver.kt",
                ),
            )
        listOf("macos-x64", "macos-arm64", "linux-x64", "linux-arm64", "windows-x64").forEach { host ->
            assertTrue(manifest.contains("\"$host\""), "Manifest missing $host")
        }
        assertTrue(manifest.contains("\"version\": \"v57.2\""))
        assertTrue(packaging.contains("JsonSlurper().parse(manifestFile)"))
        assertFalse(packaging.contains("c0f61397901da47cbe1bb9a0843624f7c2038ac92176ce15e3736ce9aa0afef0"))
        assertFalse(resolver.contains("c0f61397901da47cbe1bb9a0843624f7c2038ac92176ce15e3736ce9aa0afef0"))
        assertTrue(resolver.contains("TRACE_PROCESSOR_CHECKSUM_MISMATCH"))
        assertTrue(packaging.contains("verifyPackagedTraceProcessor"))
        assertTrue(releaseWorkflow.contains("runner: ubuntu-24.04-arm"))
        assertTrue(releaseWorkflow.contains("runner: macos-15-intel"))
        assertTrue(releaseWorkflow.contains("runner: macos-15"))
        assertTrue(releaseWorkflow.contains("runs-on: windows-latest"))
        assertTrue(releaseWorkflow.contains("install-trace-processor.sh"))
    }

    @Test
    fun `features do not reverse-depend on the Simpleperf composite`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        val offenders =
            Files.walk(desktopViewer, 2).use { paths ->
                paths
                    .filter { it.fileName.toString() == "settings.gradle.kts" }
                    .filter { it.parent.fileName.toString() != "simpleperf-viewer" }
                    .filter { Files.readString(it).contains("includeBuild(\"../simpleperf-viewer\")") }
                    .map { desktopViewer.relativize(it).toString() }
                    .toList()
            }
        assertTrue(offenders.isEmpty(), "Shared infrastructure must not be sourced from Simpleperf: $offenders")
    }
}
