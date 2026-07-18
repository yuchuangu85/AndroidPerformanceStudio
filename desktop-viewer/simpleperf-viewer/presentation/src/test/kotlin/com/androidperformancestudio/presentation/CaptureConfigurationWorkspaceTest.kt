package com.androidperformancestudio.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureConfigurationWorkspaceTest {
    @Test
    fun `device target opens capture configuration in settings dialog`() {
        val home = source("HomeScreen.kt")
        val workspace = source("DeviceTargetPage.kt")
        val configuration = source("CaptureConfigurationWorkspace.kt")

        assertTrue(home.contains("captureSettingsSection"))
        assertFalse(home.contains("CapturePage("))
        assertFalse(home.contains("ReportPage(reportState"))
        assertFalse(workspace.contains("TargetSummary(state, style)"))
        assertTrue(workspace.contains("ReportWorkspace("))
        assertFalse(workspace.contains("CaptureConfigurationWorkspace("))
        assertTrue(workspace.contains("CaptureSettingsDialog("))
        assertTrue(configuration.contains("DialogProperties(usePlatformDefaultWidth = false)"))
        assertTrue(workspace.contains("WorkspaceFooter(state, captureState, reportState, actions, style)"))
    }

    @Test
    fun `merged workspace starts data collection without a navigation step`() {
        val source = source("DeviceTargetPage.kt")

        assertTrue(source.contains("label = \"Get data\""))
        assertTrue(source.contains("onClick = actions.onStartCapture"))
        assertFalse(source.contains("Continue to Capture"))
        assertFalse(source.contains("Back to Device & Target"))
    }

    @Test
    fun `settings dialog includes capture flame graph engine and user guide sections`() {
        val source = source("CaptureConfigurationWorkspace.kt")

        assertTrue(source.contains("SAMPLING_TEMPLATE"))
        assertTrue(source.contains("CAPTURE_CONFIGURATION"))
        assertTrue(source.contains("ADVANCED_PARAMETERS"))
        assertTrue(source.contains("FLAME_GRAPH"))
        assertTrue(source.contains("SIMPLEPERF_ENGINE"))
        assertTrue(source.contains("USER_GUIDE"))
        assertTrue(source.contains("CaptureConfigurationPanel("))
        assertTrue(source.contains("AdvancedCaptureParameters("))
        assertTrue(source.contains("FlameGraphSettingsPanel("))
        assertTrue(source.contains("SimpleperfEngineSettingsPanel("))
        assertTrue(source.contains("UserGuideSettingsPanel("))
        assertTrue(source.contains("Open User Guide in Browser"))
    }

    @Test
    fun `capture configuration follows compact macOS styling`() {
        val source = source("CaptureConfigurationWorkspace.kt")

        assertTrue(source.contains("CaptureSettingsDialog("))
        assertTrue(source.contains("MacOsPanel("))
        assertTrue(source.contains("MacOsTextField("))
        assertTrue(source.contains("MacOsChoiceChip("))
        assertFalse(source.contains("MaterialTheme.typography.titleLarge"))
        assertFalse(source.contains("FilterChip("))
    }
}

private fun source(fileName: String): String =
    Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/presentation/$fileName"))
