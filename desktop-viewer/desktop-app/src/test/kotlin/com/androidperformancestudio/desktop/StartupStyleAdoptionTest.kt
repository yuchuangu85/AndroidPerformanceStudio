package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartupStyleAdoptionTest {
    private val sources =
        mapOf(
            AppDestination.HOME to sourcePath("desktop-app", "desktop/AppHomePage.kt"),
            AppDestination.SOURCE_WORKSPACES to sourcePath("desktop-app", "desktop/SourceWorkspacesPage.kt"),
            AppDestination.SETTINGS to sourcePath("desktop-app", "desktop/DesktopAppSettingsPage.kt"),
            AppDestination.BATTERY_PROFILER to
                sourcePath("battery-profiler/battery-app", "battery/presentation/BatteryProfilerScreen.kt"),
            AppDestination.NETWORK_PROFILER to
                sourcePath("network-profiler/presentation", "network/presentation/NetworkProfilerScreen.kt"),
            AppDestination.LAYOUT_INSPECTOR to
                sourcePath("layout-inspector/presentation", "desktop/LayoutInspectorMainPage.kt"),
            AppDestination.SIMPLEPERF to
                sourcePath("simpleperf-viewer/presentation", "presentation/DeviceTargetPage.kt"),
            AppDestination.METHOD_RECORDING to
                sourcePath("simpleperf-viewer/method-recording-app", "methodrecording/app/MethodRecordingScreen.kt"),
            AppDestination.PERFETTO to
                sourcePath("perfetto-viewer/perfetto-presentation", "perfetto/presentation/TraceAnalyzerWorkspace.kt"),
            AppDestination.MEMORY_PROFILER to
                sourcePath("memory-profiler/presentation", "memory/presentation/MemoryProfilerScreen.kt"),
            AppDestination.FRAME_PROFILER to
                sourcePath("frame-profiler/presentation", "frame/presentation/FrameProfilerScreen.kt"),
            AppDestination.STARTUP_PROFILER to
                sourcePath("startup-profiler/presentation", "startup/presentation/StartupWorkspaceControls.kt"),
            AppDestination.GPU_INSPECTOR to
                sourcePath("gpu-inspector-integration/presentation", "gpu/presentation/GpuAnalysisHubScreen.kt"),
            AppDestination.BENCHMARK_REGRESSION to
                sourcePath("benchmark-regression/presentation", "benchmark/presentation/BenchmarkReportsScreen.kt"),
        )

    @Test
    fun `every desktop destination omits the decorative workspace banner`() {
        assertEquals(AppDestination.entries.toSet(), sources.keys)
        sources.forEach { (destination, file) ->
            val source = Files.readString(file)
            assertFalse(source.contains("StudioWorkspaceHeader("), "$destination: $file")
            assertFalse(source.contains("startupWorkspaceColors("), "$destination must inherit the shared workspace palette")
            assertFalse(source.contains("ViewerTheme("), "$destination must inherit the desktop theme")
        }
    }

    @Test
    fun `Battery and Network pages do not duplicate a workspace heading`() {
        listOf(
            sourcePath("battery-profiler/battery-app", "battery/presentation/BatteryProfilerScreen.kt"),
            sourcePath("network-profiler/presentation", "network/presentation/NetworkProfilerScreen.kt"),
        ).forEach { file ->
            val source = Files.readString(file)
            assertFalse(source.contains("StudioWorkspaceHeader("), file.toString())
            assertTrue(source.contains("LocalViewerColors.current"), file.toString())
        }
    }

    private fun sourcePath(
        module: String,
        file: String,
    ): Path = Path.of("..", module, "src/main/kotlin/com/androidperformancestudio", file)
}
