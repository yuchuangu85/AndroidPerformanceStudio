package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnifiedDesktopShellTest {
    private val sourceRoot = Path.of("src/main/kotlin/dev/agentperf/desktop")

    @Test
    fun `main opens the unified shell`() {
        val main = Files.readString(sourceRoot.resolve("Main.kt"))
        assertTrue(main.contains("SettingsRequest(SettingsPage.GENERAL, nextSettingsRequestId)"))
        assertTrue(main.contains("UnifiedDesktopApp(settingsRequest = settingsRequest)"))
    }

    @Test
    fun `shell exposes all feature destinations`() {
        val shell = Files.readString(sourceRoot.resolve("UnifiedDesktopApp.kt"))
        val home = Files.readString(sourceRoot.resolve("AppHomePage.kt"))
        val simpleperfRoute =
            shell.substringAfter("AppDestination.SIMPLEPERF ->")
                .substringBefore("AppDestination.PERFETTO ->")
        val perfettoRoute =
            shell.substringAfter("AppDestination.PERFETTO ->")
                .substringBefore("AppDestination.MEMORY_PROFILER ->")

        assertTrue(shell.contains("DesktopViewerApp("))
        assertTrue(shell.contains("onNavigateHome = { navigator.open(AppDestination.HOME) }"))
        assertTrue(shell.contains("ApplicationUiSettingsStore.desktop()"))
        assertTrue(shell.contains("UnifiedSettingsDialog("))
        assertTrue(shell.contains("LaunchedEffect(settingsRequest?.requestId)"))
        assertTrue(shell.contains("openSettings(SettingsPage.LAYOUT_INSPECTOR)"))
        assertTrue(shell.contains("openSettings(SettingsPage.SIMPLEPERF)"))
        assertFalse(shell.contains("GlobalSettingsBar("))
        assertTrue(shell.contains("SimpleperfWorkspace("))
        assertTrue(simpleperfRoute.contains("onNavigateHome = { navigator.open(AppDestination.HOME) }"))
        assertTrue(shell.contains("PerfettoWorkspace("))
        assertTrue(perfettoRoute.contains("onNavigateHome = { navigator.open(AppDestination.HOME) }"))
        assertTrue(shell.contains("MemoryProfilerWorkspace("))
        assertTrue(shell.contains("onOpenUserGuide"))
        assertTrue(shell.contains("commonThemePreference = applicationSettings.theme.storageValue"))
        assertTrue(shell.contains("commonLanguagePreference = applicationSettings.language.storageValue"))
        assertFalse(shell.contains("返回主页"))
        assertFalse(shell.contains("RetainedFeatureLayer"))

        assertTrue(shell.contains("BatteryProfilerWorkspace("))
        assertTrue(shell.contains("onOpenMemoryProfiler"))
        assertTrue(shell.contains("onOpenFrameProfiler"))
        assertTrue(shell.contains("onOpenStartupProfiler"))
        assertTrue(shell.contains("onOpenBatteryProfiler"))
        assertTrue(shell.contains("onOpenNetworkProfiler"))
        assertTrue(shell.contains("onOpenGpuInspector"))
        assertTrue(shell.contains("onOpenBenchmarkRegression"))
        assertTrue(shell.contains("NetworkProfilerWorkspace("))
        assertTrue(shell.contains("GpuIntegrationWorkspace("))
        assertTrue(shell.contains("BenchmarkRegressionWorkspace("))
        assertTrue(shell.contains("navigator.openPerfettoTrace"))

        assertFalse(home.contains("AppSettingsControls"))
        assertTrue(home.contains("Layout Inspector"))
        assertTrue(home.contains("CPU Profiler"))
        assertTrue(home.contains("Trace Analyzer"))
        assertTrue(home.contains("Memory Profiler"))
        assertTrue(home.contains("Heap dump capture, object statistics, and class histogram analysis."))
        assertTrue(home.contains("Frame Profiler"))
        assertTrue(home.contains("Startup Profiler"))
        assertTrue(home.contains("Battery Profiler"))
        assertTrue(home.contains("Network Profiler"))
        assertTrue(home.contains("GPU Inspector"))
        assertTrue(home.contains("Benchmark Regression"))
        assertFalse(shell.contains("ComingSoonPage("))
    }

    @Test
    fun `memory profiler implementation modules are available at runtime`() {
        listOf(
            "dev.agentperf.memory.export.MemoryExportAdapters",
            "dev.agentperf.memory.storage.SqliteMemorySessionStore",
            "com.androidperformancestudio.memory.presentation.MemoryProfilerState",
        ).forEach { className ->
            assertDoesNotThrow(
                { Class.forName(className) },
                "Missing Memory Profiler runtime dependency: $className",
            )
        }
    }

    @Test
    fun `battery profiler workspace is available at runtime`() {
        assertDoesNotThrow(
            { Class.forName("com.androidperformancestudio.battery.app.BatteryProfilerWorkspaceKt") },
            "Missing Battery Profiler runtime dependency",
        )
    }

    @Test
    fun `ecosystem profiler workspaces are available at runtime`() {
        listOf(
            "com.androidperformancestudio.network.app.NetworkProfilerWorkspaceKt",
            "com.androidperformancestudio.gpu.app.GpuIntegrationWorkspaceKt",
            "com.androidperformancestudio.benchmark.app.BenchmarkRegressionWorkspaceKt",
        ).forEach { className ->
            assertDoesNotThrow(
                { Class.forName(className) },
                "Missing ecosystem profiler runtime dependency: $className",
            )
        }
    }
}
