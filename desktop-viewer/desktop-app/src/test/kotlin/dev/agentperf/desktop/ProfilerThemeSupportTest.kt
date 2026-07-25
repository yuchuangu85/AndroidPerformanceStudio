package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfilerThemeSupportTest {
    @Test
    fun `theme preference resolves light dark and system modes`() {
        assertFalse(ApplicationThemePreference.LIGHT.resolveDark(systemDark = true))
        assertTrue(ApplicationThemePreference.DARK.resolveDark(systemDark = false))
        assertFalse(ApplicationThemePreference.SYSTEM.resolveDark(systemDark = false))
        assertTrue(ApplicationThemePreference.SYSTEM.resolveDark(systemDark = true))
    }

    @Test
    fun `unified shell paints an adaptive background around every profiler`() {
        val shell = Files.readString(Path.of("src/main/kotlin/dev/agentperf/desktop/UnifiedDesktopApp.kt"))

        assertTrue(shell.contains("viewerMaterialColorScheme(darkTheme)"))
        assertTrue(shell.contains("compactDesktopTypography()"))
        assertTrue(shell.contains("compactDesktopShapes()"))
        assertTrue(shell.contains("LocalMinimumInteractiveComponentSize provides 32.dp"))
        assertTrue(shell.contains("color = MaterialTheme.colorScheme.background"))
        assertTrue(shell.contains("contentColor = MaterialTheme.colorScheme.onBackground"))
    }

    @Test
    fun `profiler workspaces retain the compact inspector toolbar boundary`() {
        profilerWorkspaceSources().forEach { source ->
            val content = Files.readString(source)
            if (source.fileName.toString() == "MemoryProfilerWorkspace.kt") {
                assertTrue(
                    content.contains("MEMORY_WORKSPACE_TOOLBAR_HEIGHT_DP = 29"),
                    "$source must align its toolbar height with Layout Inspector",
                )
                assertTrue(
                    content.contains("MEMORY_TOOLBAR_BUTTON_HEIGHT_DP = 22"),
                    "$source must align its button height with Layout Inspector",
                )
            } else {
                assertTrue(
                    content.contains("padding(horizontal = 8.dp, vertical = 4.dp)"),
                    "$source must use compact desktop toolbar spacing",
                )
            }
            assertTrue(
                content.contains("HorizontalDivider(color = MaterialTheme.colorScheme.outline)"),
                "$source must separate the toolbar from its inspector panes",
            )
        }
    }

    @Test
    fun `profiler presentation layers use theme roles instead of fixed light colors`() {
        profilerPresentationSources().forEach { source ->
            val content = Files.readString(source)
            assertTrue(
                content.contains("MaterialTheme.colorScheme"),
                "$source must consume the active Material color scheme",
            )
            assertFalse(
                FIXED_HEX_COLOR.containsMatchIn(content),
                "$source must not hard-code a light-only color",
            )
        }
    }

    private fun profilerPresentationSources(): List<Path> {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        return listOf(
            "memory-profiler/presentation/src/main/kotlin/com/androidperformancestudio/memory/presentation/MemoryProfilerScreen.kt",
            "frame-profiler/presentation/src/main/kotlin/com/androidperformancestudio/frame/presentation/FrameProfilerScreen.kt",
            "startup-profiler/presentation/src/main/kotlin/com/androidperformancestudio/startup/presentation/StartupProfilerScreen.kt",
            "battery-profiler/presentation/src/main/kotlin/com/androidperformancestudio/battery/presentation/BatteryProfilerScreen.kt",
            "network-profiler/presentation/src/main/kotlin/com/androidperformancestudio/network/presentation/NetworkProfilerScreen.kt",
            "gpu-inspector-integration/presentation/src/main/kotlin/com/androidperformancestudio/gpu/presentation/GpuIntegrationScreen.kt",
            "benchmark-regression/presentation/src/main/kotlin/com/androidperformancestudio/benchmark/presentation/BenchmarkRegressionScreen.kt",
        ).map(desktopViewer::resolve)
    }

    private fun profilerWorkspaceSources(): List<Path> {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        return listOf(
            "memory-profiler/memory-app/src/main/kotlin/com/androidperformancestudio/memory/app/MemoryProfilerWorkspace.kt",
            "frame-profiler/frame-app/src/main/kotlin/com/androidperformancestudio/frame/app/FrameProfilerWorkspace.kt",
            "startup-profiler/startup-app/src/main/kotlin/com/androidperformancestudio/startup/app/StartupProfilerWorkspace.kt",
            "battery-profiler/battery-app/src/main/kotlin/com/androidperformancestudio/battery/app/BatteryProfilerWorkspace.kt",
            "network-profiler/network-app/src/main/kotlin/com/androidperformancestudio/network/app/NetworkProfilerWorkspace.kt",
            "gpu-inspector-integration/gpu-integration-app/src/main/kotlin/com/androidperformancestudio/gpu/app/GpuIntegrationWorkspace.kt",
            "benchmark-regression/benchmark-app/src/main/kotlin/com/androidperformancestudio/benchmark/app/BenchmarkRegressionWorkspace.kt",
        ).map(desktopViewer::resolve)
    }

    private companion object {
        val FIXED_HEX_COLOR = Regex("Color\\(0x[0-9A-Fa-f]+")
    }
}
