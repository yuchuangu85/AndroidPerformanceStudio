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
        val simpleperfAdapter =
            Files.readString(
                desktopViewer.resolve(
                    "platform-core/host-toolchain/src/main/kotlin/" +
                        "com/androidperformancestudio/toolchain/ProcessRunner.kt",
                ),
            )
        val perfettoTraceProcessorBuild =
            Files.readString(desktopViewer.resolve("perfetto-viewer/perfetto-trace-processor/build.gradle.kts"))
        val perfettoTraceProcessor =
            Files.readString(
                desktopViewer.resolve(
                    "perfetto-viewer/perfetto-trace-processor/src/main/kotlin/" +
                        "com/androidperformancestudio/perfetto/traceprocessor/TraceProcessorSession.kt",
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
        assertFalse(simpleperfAdapter.contains("ProcessBuilder("))
        assertTrue(adbClient.contains("JvmHostProcessRunner"))
        assertTrue(simpleperfAdapter.contains("JvmHostProcessRunner"))
        assertTrue(perfettoTraceProcessorBuild.contains("com.androidperformancestudio:platform-toolchain"))
        assertFalse(perfettoTraceProcessor.contains("ProcessBuilder("))
        assertTrue(perfettoTraceProcessor.contains("processRunner.launch("))
        assertTrue(perfettoCapture.contains("JvmProcessRunner().run(request)"))
    }

    @Test
    fun `frame startup and battery resolve shared infrastructure from platform core`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()

        listOf("frame-profiler", "startup-profiler", "battery-profiler").forEach { build ->
            val settings = Files.readString(desktopViewer.resolve("$build/settings.gradle.kts"))
            assertTrue(settings.contains("includeBuild(\"../platform-core\")"))
            assertFalse(settings.contains("includeBuild(\"../simpleperf-viewer\")"))
        }
    }
}
