package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnifiedDesktopShellTest {
    private val sourceRoot = Path.of("src/main/kotlin/com/androidperformancestudio/desktop")

    @Test
    fun `main opens the unified shell`() {
        val main = Files.readString(sourceRoot.resolve("Main.kt"))
        assertTrue(main.contains("SettingsRequest(SettingsPage.GENERAL, nextSettingsRequestId)"))
        assertTrue(main.contains("DesktopAppMainPage(windowTitle, settingsRequest = settingsRequest)"))
    }

    @Test
    fun `development run loads desktop classes from source set outputs`() {
        val buildScript = Files.readString(Path.of("build.gradle.kts"))

        assertTrue(buildScript.contains("tasks.withType<JavaExec>().configureEach"))
        assertTrue(buildScript.contains("if (name == \"run\")"))
        assertTrue(buildScript.contains("val developmentRuntimeClasspath = sourceSets[\"main\"].runtimeClasspath"))
        assertTrue(buildScript.contains("classpath = developmentRuntimeClasspath"))
    }

    @Test
    fun `shell exposes all feature destinations`() {
        val shell = Files.readString(sourceRoot.resolve("DesktopAppMainPage.kt"))
        val home = Files.readString(sourceRoot.resolve("AppHomePage.kt"))
        val simpleperfRoute =
            shell.substringAfter("AppDestination.SIMPLEPERF ->")
                .substringBefore("AppDestination.PERFETTO ->")
        val perfettoRoute =
            shell.substringAfter("AppDestination.PERFETTO ->")
                .substringBefore("AppDestination.WINSCOPE ->")
        val winscopeRoute =
            shell.substringAfter("AppDestination.WINSCOPE ->")
                .substringBefore("AppDestination.MEMORY_PROFILER ->")

        assertTrue(shell.contains("LayoutInspectorMainPage("))
        assertTrue(shell.contains("onNavigateHome = { navigator.open(AppDestination.HOME) }"))
        assertTrue(shell.contains("ApplicationUiSettingsStore.desktop()"))
        assertTrue(shell.contains("DesktopAppSettingsDialog("))
        assertTrue(shell.contains("LaunchedEffect(settingsRequest?.requestId)"))
        assertTrue(shell.contains("openSettings(SettingsPage.LAYOUT_INSPECTOR)"))
        assertTrue(shell.contains("openSettings(SettingsPage.SIMPLEPERF)"))
        assertFalse(shell.contains("GlobalSettingsBar("))
        assertTrue(shell.contains("SimpleperfMainPage("))
        assertTrue(simpleperfRoute.contains("onNavigateHome = { navigator.open(AppDestination.HOME) }"))
        assertTrue(shell.contains("PerfettoMainPage("))
        assertTrue(perfettoRoute.contains("onNavigateHome = { navigator.open(AppDestination.HOME) }"))
        assertTrue(shell.contains("WinscopeMainPage("))
        assertTrue(winscopeRoute.contains("onNavigateHome = { navigator.open(AppDestination.HOME) }"))
        assertTrue(shell.contains("MemoryProfilerMainPage("))
        assertTrue(shell.contains("onOpenUserGuide"))
        assertTrue(shell.contains("commonThemePreference = applicationSettings.theme.storageValue"))
        assertTrue(shell.contains("commonLanguagePreference = applicationSettings.language.storageValue"))
        assertFalse(shell.contains("返回主页"))
        assertFalse(shell.contains("RetainedFeatureLayer"))

        assertTrue(shell.contains("BatteryProfilerMainPage("))
        assertTrue(shell.contains("onOpenMemoryProfiler"))
        assertTrue(shell.contains("onOpenFrameProfiler"))
        assertTrue(shell.contains("onOpenStartupProfiler"))
        assertTrue(shell.contains("onOpenBatteryProfiler"))
        assertTrue(shell.contains("onOpenNetworkProfiler"))
        assertTrue(shell.contains("onOpenGpuInspector"))
        assertTrue(shell.contains("onOpenBenchmarkRegression"))
        assertTrue(shell.contains("NetworkProfilerMainPage("))
        assertTrue(shell.contains("GpuIntegrationMainPage("))
        assertTrue(shell.contains("BenchmarkRegressionMainPage("))
        assertTrue(shell.contains("navigator.openPerfettoTrace"))

        assertFalse(home.contains("AppSettingsControls"))
        listOf(
            "Res.string.layout_inspector",
            "Res.string.cpu_profiler",
            "Res.string.trace_analyzer",
            "Res.string.winscope",
            "Res.string.memory_profiler",
            "Res.string.heap_dump_capture_object_statistics_and_class_histogram_analysis",
            "Res.string.frame_profiler",
            "Res.string.startup_profiler",
            "Res.string.battery_profiler",
            "Res.string.network_profiler",
        ).forEach { resource -> assertTrue(home.contains(resource), "Missing home resource reference: $resource") }
        listOf(
            "Res.string.gpu_inspector",
            "Res.string.benchmark_regression",
            "Res.string.view_live_telemetry",
            "Res.string.cpu_method_recording",
            "Res.string.java_kotlin_allocations",
        ).forEach { resource -> assertFalse(home.contains(resource), "Hidden home resource reference: $resource") }
        assertFalse(shell.contains("ComingSoonPage("))
    }

    @Test
    fun `memory profiler implementation modules are available at runtime`() {
        listOf(
            "com.androidperformancestudio.memory.export.MemoryExportAdapters",
            "com.androidperformancestudio.memory.storage.SqliteMemorySessionStore",
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
            { Class.forName("com.androidperformancestudio.battery.app.BatteryProfilerMainPageKt") },
            "Missing Battery Profiler runtime dependency",
        )
    }

    @Test
    fun `winscope import lives in the file menu`() {
        val source =
            Files.readString(
                Path.of("../winscope/winscope-app/src/main/kotlin/com/androidperformancestudio/winscope/app/WinscopeMainPage.kt"),
            )
        val header = source.substringAfter("HeaderToolbar(language = language").substringBefore("error?.let")

        assertTrue(source.contains("ActiveWindowMenuBar {"))
        assertTrue(source.contains("Menu(s(language, \"File\", \"文件\"))"))
        assertTrue(source.contains("onClick = ::chooseImportFile"))
        assertFalse(header.contains("chooseOpenFile("))
    }

    @Test
    fun `winscope file menu includes export and recent sessions`() {
        val source =
            Files.readString(
                Path.of("../winscope/winscope-app/src/main/kotlin/com/androidperformancestudio/winscope/app/WinscopeMainPage.kt"),
            )
        val fileMenu = source.substringAfter("ActiveWindowMenuBar {").substringBefore("Column(Modifier.fillMaxSize()")
        val header = source.substringAfter("HeaderToolbar(language = language").substringBefore("error?.let")

        assertTrue(fileMenu.contains("Menu(s(language, \"Export\", \"导出\"))"))
        assertTrue(fileMenu.contains("onClick = ::exportSession"))
        assertTrue(fileMenu.contains("Menu(s(language, \"Open Recent\", \"最近打开\"))"))
        assertTrue(fileMenu.contains("recentSessions.forEach"))
        assertFalse(header.contains("Export ZIP"))
    }

    @Test
    fun `winscope uses the shared macOS text button`() {
        val source =
            Files.readString(
                Path.of("../winscope/winscope-app/src/main/kotlin/com/androidperformancestudio/winscope/app/WinscopeMainPage.kt"),
            )

        assertTrue(source.contains("import com.androidperformancestudio.ui.button.MacOSTextButton"))
        assertTrue(source.contains("MacOSTextButton("))
        assertFalse(Regex("\\b(Button|OutlinedButton|TextButton)\\(").containsMatchIn(source))
    }

    @Test
    fun `winscope uses the CPU profiler inline text field`() {
        val source =
            Files.readString(
                Path.of("../winscope/winscope-app/src/main/kotlin/com/androidperformancestudio/winscope/app/WinscopeMainPage.kt"),
            )

        assertTrue(source.contains("MacOSInlineTextField("))
        assertFalse(source.contains("OutlinedTextField("))
    }

    @Test
    fun `winscope device controls live in the header toolbar`() {
        val source =
            Files.readString(
                Path.of("../winscope/winscope-app/src/main/kotlin/com/androidperformancestudio/winscope/app/WinscopeMainPage.kt"),
            )
        val header = source.substringAfter("HeaderToolbar(language = language").substringBefore("error?.let")
        val capturePanel = source.substringAfter("private fun CapturePanel(").substringBefore("private fun ViewerWorkspace(")

        assertTrue(header.contains("DropdownSelector("))
        assertTrue(header.contains("onClick = ::refreshDevices"))
        assertTrue(header.contains("onClick = ::toggleCapture"))
        assertTrue(header.contains("onClick = ::takeSnapshot"))
        assertFalse(capturePanel.contains("DropdownSelector("))
        assertFalse(capturePanel.contains("onRefresh"))
        assertFalse(capturePanel.contains("onStart"))
        assertFalse(capturePanel.contains("onSnapshot"))
    }

    @Test
    fun `ecosystem profiler workspaces are available at runtime`() {
        listOf(
            "com.androidperformancestudio.network.app.NetworkProfilerMainPageKt",
            "com.androidperformancestudio.gpu.app.GpuIntegrationMainPageKt",
            "com.androidperformancestudio.benchmark.app.BenchmarkRegressionMainPageKt",
            "com.androidperformancestudio.winscope.app.WinscopeMainPageKt",
        ).forEach { className ->
            assertDoesNotThrow(
                { Class.forName(className) },
                "Missing ecosystem profiler runtime dependency: $className",
            )
        }
    }
}
