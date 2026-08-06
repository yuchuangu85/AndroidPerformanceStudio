package com.androidperformancestudio.desktop

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
        val shell = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt"))

        assertTrue(shell.contains("ViewerTheme("))
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
            assertTrue(
                content.contains("ProfilerMacOsToolbar {"),
                "$source must use the shared compact profiler toolbar",
            )
            assertTrue(
                OUTLINE_DIVIDER.containsMatchIn(content),
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

    @Test
    fun `every feature uses the CPU profiler home button style`() {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        val sharedButton =
            Files.readString(
                desktopViewer.resolve(
                    "ui-components/src/main/kotlin/" +
                        "com/androidperformancestudio/ui/button/HomeButton.kt",
                ),
            )
        val cpuProfiler =
            Files.readString(
                desktopViewer.resolve(
                    "simpleperf-viewer/presentation/src/main/kotlin/" +
                        "com/androidperformancestudio/presentation/DeviceTargetPage.kt",
                ),
            )
        val viewerTheme =
            Files.readString(
                desktopViewer.resolve(
                    "ui-components/src/main/kotlin/" +
                        "com/androidperformancestudio/ui/ViewerTheme.kt",
                ),
            )
        val settingsButton =
            Files.readString(
                desktopViewer.resolve(
                    "ui-components/src/main/kotlin/" +
                        "com/androidperformancestudio/ui/button/SettingButton.kt",
                ),
            )
        val perfetto =
            Files.readString(
                desktopViewer.resolve(
                    "perfetto-viewer/perfetto-app/src/main/kotlin/" +
                        "com/androidperformancestudio/perfetto/app/PerfettoMainPage.kt",
                ),
            )

        assertTrue(settingsButton.contains(".width(28.dp)"))
        assertTrue(settingsButton.contains(".height(ViewerDimensions.buttonHeight)"))
        assertTrue(settingsButton.contains("RoundedCornerShape(ViewerDimensions.controlRadius)"))
        assertTrue(viewerTheme.contains("buttonHeight = 28.dp"))
        assertTrue(viewerTheme.contains("controlRadius = 6.dp"))
        assertTrue(cpuProfiler.contains(".background(style.panel)"))
        assertTrue(cpuProfiler.contains(".border("))
        assertSharedHomeButtonStyle(sharedButton)
        assertCpuHomeButtonStyle(perfetto, "Perfetto")

        profilerHomeButtonConsumers().forEach { source ->
            assertTrue(
                Files.readString(source).contains("ProfilerHomeButton("),
                "$source must use the shared CPU Profiler home button style",
            )
        }
    }

    private fun assertSharedHomeButtonStyle(source: String) {
        assertTrue(source.contains(".width(28.dp)"))
        assertTrue(source.contains(".height(ViewerDimensions.buttonHeight)"))
        assertTrue(source.contains("RoundedCornerShape(ViewerDimensions.controlRadius)"))
        assertTrue(source.contains(".background("))
        assertTrue(source.contains(".border("))
    }

    private fun assertCpuHomeButtonStyle(
        source: String,
        owner: String,
    ) {
        assertTrue(source.contains(".width(28.dp)"), "$owner home button must be 28dp wide")
        assertTrue(source.contains(".height(28.dp)"), "$owner home button must be 28dp high")
        assertTrue(source.contains("RoundedCornerShape(6.dp)"), "$owner home button must use a 6dp radius")
        assertTrue(source.contains(".background("), "$owner home button must have a panel background")
        assertTrue(source.contains(".border("), "$owner home button must have a visible border")
    }

    private fun profilerHomeButtonConsumers(): List<Path> {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        return listOf(
            "layout-inspector/presentation/src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt",
            "memory-profiler/memory-app/src/main/kotlin/com/androidperformancestudio/memory/app/MemoryProfilerMainPage.kt",
            "frame-profiler/frame-app/src/main/kotlin/com/androidperformancestudio/frame/app/FrameProfilerMainPage.kt",
            "startup-profiler/startup-app/src/main/kotlin/com/androidperformancestudio/startup/app/StartupProfilerMainPage.kt",
            "battery-profiler/battery-app/src/main/kotlin/com/androidperformancestudio/battery/app/BatteryProfilerMainPage.kt",
            "network-profiler/network-app/src/main/kotlin/com/androidperformancestudio/network/app/NetworkProfilerMainPage.kt",
            "gpu-inspector-integration/gpu-integration-app/src/main/kotlin/com/androidperformancestudio/gpu/app/GpuIntegrationMainPage.kt",
            "benchmark-regression/benchmark-app/src/main/kotlin/com/androidperformancestudio/benchmark/app/BenchmarkRegressionMainPage.kt",
        ).map(desktopViewer::resolve)
    }

    private fun profilerPresentationSources(): List<Path> {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        return listOf(
            "memory-profiler/presentation/src/main/kotlin/com/androidperformancestudio/memory/presentation/MemoryProfilerScreen.kt",
            "frame-profiler/presentation/src/main/kotlin/com/androidperformancestudio/frame/presentation/FrameProfilerScreen.kt",
            "startup-profiler/presentation/src/main/kotlin/com/androidperformancestudio/startup/presentation/StartupProfilerScreen.kt",
            "battery-profiler/battery-app/src/main/kotlin/com/androidperformancestudio/battery/presentation/BatteryProfilerScreen.kt",
            "network-profiler/presentation/src/main/kotlin/com/androidperformancestudio/network/presentation/NetworkProfilerScreen.kt",
            "gpu-inspector-integration/presentation/src/main/kotlin/com/androidperformancestudio/gpu/presentation/GpuIntegrationScreen.kt",
            "benchmark-regression/presentation/src/main/kotlin/com/androidperformancestudio/benchmark/presentation/BenchmarkRegressionScreen.kt",
        ).map(desktopViewer::resolve)
    }

    private fun profilerWorkspaceSources(): List<Path> {
        val desktopViewer = Path.of("..").toAbsolutePath().normalize()
        return listOf(
            "memory-profiler/memory-app/src/main/kotlin/com/androidperformancestudio/memory/app/MemoryProfilerMainPage.kt",
            "frame-profiler/frame-app/src/main/kotlin/com/androidperformancestudio/frame/app/FrameProfilerMainPage.kt",
            "startup-profiler/startup-app/src/main/kotlin/com/androidperformancestudio/startup/app/StartupProfilerMainPage.kt",
            "battery-profiler/battery-app/src/main/kotlin/com/androidperformancestudio/battery/app/BatteryProfilerMainPage.kt",
            "network-profiler/network-app/src/main/kotlin/com/androidperformancestudio/network/app/NetworkProfilerMainPage.kt",
            "gpu-inspector-integration/gpu-integration-app/src/main/kotlin/com/androidperformancestudio/gpu/app/GpuIntegrationMainPage.kt",
            "benchmark-regression/benchmark-app/src/main/kotlin/com/androidperformancestudio/benchmark/app/BenchmarkRegressionMainPage.kt",
        ).map(desktopViewer::resolve)
    }

    private companion object {
        val FIXED_HEX_COLOR = Regex("Color\\(0x[0-9A-Fa-f]+")
        val OUTLINE_DIVIDER =
            Regex(
                """HorizontalDivider\(\s*(?:thickness = 1\.dp,\s*)?color = MaterialTheme\.colorScheme\.outline,?\s*\)""",
            )
    }
}
