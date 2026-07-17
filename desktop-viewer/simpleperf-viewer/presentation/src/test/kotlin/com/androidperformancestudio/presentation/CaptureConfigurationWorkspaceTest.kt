package com.androidperformancestudio.presentation

import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureConfigurationWorkspaceTest {
    @Test
    fun `device target and capture configuration share one workspace`() {
        val home = source("HomeScreen.kt")
        val workspace = source("DeviceTargetPage.kt")

        assertTrue(home.contains("DeviceTargetPage(state, captureState, actions, darkTheme)"))
        assertFalse(home.contains("CapturePage("))
        assertTrue(workspace.contains("TargetSummary(state, style)"))
        assertTrue(workspace.contains("CaptureConfigurationWorkspace("))
        assertTrue(workspace.contains("WorkspaceFooter(state, captureState, actions, style)"))
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
    fun `capture configuration uses horizontal panels at normal desktop widths`() {
        assertEquals(CaptureConfigurationLayout.HORIZONTAL, captureConfigurationLayout(900.dp))
        assertEquals(CaptureConfigurationLayout.HORIZONTAL, captureConfigurationLayout(1200.dp))
        assertEquals(CaptureConfigurationLayout.STACKED, captureConfigurationLayout(899.dp))
    }

    @Test
    fun `capture configuration follows compact macOS styling`() {
        val source = source("CaptureConfigurationWorkspace.kt")

        assertTrue(source.contains("CaptureConfigurationWorkspace("))
        assertTrue(source.contains("MacOsPanel("))
        assertTrue(source.contains("MacOsTextField("))
        assertTrue(source.contains("MacOsChoiceChip("))
        assertFalse(source.contains("MaterialTheme.typography.titleLarge"))
        assertFalse(source.contains("FilterChip("))
    }
}

private fun source(fileName: String): String =
    Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/presentation/$fileName"))
