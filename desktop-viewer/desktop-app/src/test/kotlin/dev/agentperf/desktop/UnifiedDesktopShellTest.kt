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
    fun `shell exposes both root feature directories`() {
        val shell = Files.readString(sourceRoot.resolve("UnifiedDesktopApp.kt"))
        val home = Files.readString(sourceRoot.resolve("AppHomePage.kt"))

        assertTrue(shell.contains("DesktopViewerApp("))
        assertTrue(shell.contains("ApplicationUiSettingsStore.desktop()"))
        assertTrue(shell.contains("ApplicationSettingsDialog("))
        assertTrue(shell.contains("LaunchedEffect(settingsRequest)"))
        assertFalse(shell.contains("GlobalSettingsBar("))
        assertTrue(shell.contains("SimpleperfWorkspace("))
        assertTrue(shell.contains("onOpenUserGuide"))
        assertTrue(shell.contains("commonThemePreference = applicationSettings.theme.storageValue"))
        assertTrue(shell.contains("commonLanguagePreference = applicationSettings.language.storageValue"))
        assertFalse(shell.contains("返回主页"))
        assertFalse(shell.contains("RetainedFeatureLayer"))
        assertFalse(home.contains("AppSettingsControls"))
        assertTrue(home.contains("Layout Inspector"))
        assertTrue(home.contains("Simpleperf CPU Profiler"))
        assertTrue(home.contains("Perfetto Trace Analyzer"))
        assertTrue(shell.contains("onOpenPerfetto"))
    }
}
