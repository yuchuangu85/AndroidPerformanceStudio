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
                .substringBefore("AppDestination.MEMORY_PROFILER ->")

        assertTrue(shell.contains("LayoutInspectorMainPage("))
        assertTrue(shell.contains("onNavigateHome = { navigator.open(AppDestination.HOME) }"))
        assertTrue(shell.contains("ApplicationUiSettingsStore.desktop()"))
        assertTrue(shell.contains("DesktopAppSettingsPage("))
        assertTrue(shell.contains("AppDestination.SETTINGS ->"))
        assertTrue(shell.contains("LaunchedEffect(settingsRequest?.requestId)"))
        assertTrue(shell.contains("openSettings(SettingsPage.LAYOUT_INSPECTOR)"))
        assertTrue(shell.contains("openSettings(SettingsPage.SIMPLEPERF)"))
        assertFalse(shell.contains("GlobalSettingsBar("))
        assertTrue(shell.contains("SimpleperfMainPage("))
        assertTrue(simpleperfRoute.contains("onNavigateHome = { navigator.open(AppDestination.HOME) }"))
        assertTrue(shell.contains("PerfettoMainPage("))
        assertTrue(perfettoRoute.contains("onNavigateHome = { navigator.open(AppDestination.HOME) }"))
        assertTrue(shell.contains("MemoryProfilerMainPage("))
        assertTrue(shell.contains("onOpenUserGuide"))
        assertTrue(shell.contains("val viewerThemeContext = rememberViewerThemeContext()"))
        assertTrue(shell.contains("commonLanguagePreference = applicationSettings.language.storageValue"))
        assertFalse(shell.contains("返回主页"))
        assertFalse(shell.contains("RetainedFeatureLayer"))

        assertTrue(shell.contains("BatteryNetworkMainPage("))
        assertTrue(shell.contains("ReverseNetMainPage("))
        assertTrue(shell.contains("onOpenMemoryProfiler"))
        assertTrue(shell.contains("onOpenFrameProfiler"))
        assertTrue(shell.contains("onOpenStartupProfiler"))
        assertTrue(shell.contains("onOpenBatteryNetwork"))
        assertTrue(shell.contains("onOpenReverseNet"))
        assertTrue(shell.contains("onOpenGpuInspector"))
        assertTrue(shell.contains("onOpenBenchmarkRegression"))
        assertTrue(shell.contains("GpuIntegrationMainPage("))
        assertTrue(shell.contains("BenchmarkRegressionMainPage("))
        assertTrue(shell.contains("navigator.openPerfettoTrace"))

        assertFalse(home.contains("AppSettingsControls"))
        // The home grid is controller-driven: module titles resolve via the shared
        // destination title resource, and descriptions via the module description resolver.
        assertTrue(home.contains("homeLayout.moduleOrder"))
        assertTrue(home.contains("destination.titleResource"))
        assertTrue(home.contains("moduleDescriptionResource"))
        listOf(
            "Res.string.inspect_android_view_hierarchies_screenshots_bounds_and_properties",
            "Res.string.simpleperf_based_cpu_sampling_flame_graphs_call_tree_analysis",
            "Res.string.perfetto_system_level_trace_capture_with_scheduling_binder_and_graphic",
            "Res.string.heap_dump_capture_object_statistics_and_class_histogram_analysis",
            "Res.string.capture_online_or_import_gfxinfo_framestats_to_analyze_frame_timing",
            "Res.string.cold_warm_startup_breakdown_and_baseline_profile_support",
            "Res.string.battery_network_description",
            "Res.string.discover_and_launch_android_gpu_inspector_then_index_and_verify",
            "Res.string.compare_androidx_benchmark_baselines_and_current_results_with_ci_regre",
            "Res.string.source_home_description",
            "Res.string.reverse_net_home_description",
        ).forEach { resource -> assertTrue(home.contains(resource), "Missing home resource reference: $resource") }
        listOf(
            "Res.string.view_live_telemetry",
            "Res.string.cpu_method_recording",
            "Res.string.java_kotlin_allocations",
        ).forEach { resource -> assertFalse(home.contains(resource), "Hidden home resource reference: $resource") }
        assertFalse(shell.contains("ComingSoonPage("))
    }

    @Test
    fun `inactive retained destinations block pointer input`() {
        val shell = Files.readString(sourceRoot.resolve("DesktopAppMainPage.kt"))

        assertTrue(shell.contains(".blockPointerInputWhenInactive(active)"))
        assertTrue(shell.contains("awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }"))
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
    fun `ecosystem profiler workspaces are available at runtime`() {
        listOf(
            "com.androidperformancestudio.network.app.NetworkProfilerMainPageKt",
            "com.androidperformancestudio.gpu.app.GpuIntegrationMainPageKt",
            "com.androidperformancestudio.benchmark.app.BenchmarkRegressionMainPageKt",
        ).forEach { className ->
            assertDoesNotThrow(
                { Class.forName(className) },
                "Missing ecosystem profiler runtime dependency: $className",
            )
        }
    }
}
