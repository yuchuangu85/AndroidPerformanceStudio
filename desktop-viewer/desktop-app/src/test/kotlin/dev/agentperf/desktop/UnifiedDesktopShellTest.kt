package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnifiedDesktopShellTest {
    private val sourceRoot = Path.of("src/main/kotlin/dev/agentperf/desktop")

    @Test
    fun `main opens the unified shell`() {
        val main = Files.readString(sourceRoot.resolve("Main.kt"))
        assertTrue(main.contains("UnifiedDesktopApp(settingsRequest = settingsRequest)"))
    }

    @Test
    fun `shell exposes all feature destinations`() {
        val shell = Files.readString(sourceRoot.resolve("UnifiedDesktopApp.kt"))
        val home = Files.readString(sourceRoot.resolve("AppHomePage.kt"))

        assertTrue(shell.contains("DesktopViewerApp("))
        assertTrue(shell.contains("ApplicationUiSettingsStore.desktop()"))
        assertTrue(shell.contains("ApplicationSettingsDialog("))
        assertTrue(shell.contains("LaunchedEffect(settingsRequest)"))
        assertFalse(shell.contains("GlobalSettingsBar("))
        assertTrue(shell.contains("SimpleperfWorkspace("))
        assertTrue(shell.contains("PerfettoWorkspace("))
        assertTrue(shell.contains("onOpenUserGuide"))
        assertTrue(shell.contains("commonThemePreference = applicationSettings.theme.storageValue"))
        assertTrue(shell.contains("commonLanguagePreference = applicationSettings.language.storageValue"))
        assertFalse(shell.contains("返回主页"))
        assertFalse(shell.contains("RetainedFeatureLayer"))

        assertTrue(shell.contains("ComingSoonPage("))
        assertTrue(shell.contains("onOpenMemoryProfiler"))
        assertTrue(shell.contains("onOpenFrameProfiler"))
        assertTrue(shell.contains("onOpenStartupProfiler"))
        assertTrue(shell.contains("onOpenBatteryProfiler"))

        assertFalse(home.contains("AppSettingsControls"))
        assertTrue(home.contains("Layout Inspector"))
        assertTrue(home.contains("CPU Profiler"))
        assertTrue(home.contains("Trace Analyzer"))
        assertTrue(home.contains("Memory Profiler"))
        assertTrue(home.contains("Frame Profiler"))
        assertTrue(home.contains("Startup Profiler"))
        assertTrue(home.contains("Battery Profiler"))
        assertTrue(home.contains("Network Profiler"))
        assertTrue(shell.contains("ComingSoonPage("))
    }
}
