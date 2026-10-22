package com.androidperformancestudio.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CpuWorkspaceStyleTest {
    @Test
    fun `capture and report omit the decorative workspace banner below the toolbar`() {
        val source = source("DeviceTargetPage.kt")
        assertFalse(source.contains("StudioWorkspaceHeader("))
        assertTrue(source.indexOf("HeaderToolbar(") < source.indexOf("ReportWorkspace("))
        assertTrue(source.indexOf("ReportWorkspace(") < source.indexOf("WorkspaceFooter("))
        assertTrue(source("ReportPage.kt").contains("StudioTokens.workspacePadding"))
    }

    @Test
    fun `report panes and capture settings do not repeat the workspace heading`() {
        listOf("HomeScreen.kt", "ReportPage.kt", "FirefoxReportWorkspace.kt", "CaptureSettingsSection.kt").forEach { file ->
            assertFalse(source(file).contains("StudioWorkspaceHeader("), file)
        }
        val source = source("DeviceTargetPage.kt")
        assertTrue(source.contains("onNavigateHome = onNavigateHome"))
        assertTrue(source.contains("ToolbarCaptureActions(state, captureState, actions, style"))
        assertTrue(source.contains("flameTooltipMode = flameTooltipMode"))
        assertTrue(source.contains("onRunAiAnalysis = onRunAiAnalysis"))
        // The report content sits directly on the workspace instead of inside a page-level frame.
        val reportWorkspace =
            source("ReportPage.kt")
                .substringAfter("internal fun ReportWorkspace(")
                .substringBefore("private fun ReportResultPane(")
        assertFalse(reportWorkspace.contains(".border("))
        assertFalse(source("FirefoxReportWorkspace.kt").contains(".border("))
        val reportStatus =
            source("ReportPage.kt")
                .substringAfter("private fun ReportStatus(")
                .substringBefore("internal fun ReportSelectedPanel(")
        assertFalse(reportStatus.contains("StudioCard("), "Pending/error status must not re-frame the content area")
    }

    private fun source(file: String): String = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/presentation", file))
}
