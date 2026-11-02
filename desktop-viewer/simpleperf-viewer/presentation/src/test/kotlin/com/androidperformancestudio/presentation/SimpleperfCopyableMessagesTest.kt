@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.androidperformancestudio.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ui.viewerColors
import java.awt.datatransfer.DataFlavor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SimpleperfCopyableMessagesTest {
    @Test
    fun `marker failures retain full diagnostic text and expose a working copy action`() =
        runDesktopComposeUiTest(width = 420, height = 240) {
            val message = "MARKER_QUERY_FAILED: " + "Original diagnostic details. ".repeat(20)
            val clipboard = MemoryClipboard()
            setContent {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    MarkerPanelMessage(message, viewerColors(false))
                }
            }

            val action =
                onNodeWithText(message, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .config[SemanticsActions.CustomActions]
                    .single()
            runOnIdle { assertTrue(action.action()) }
            waitForIdle()
            assertEquals(message, clipboard.text())
        }

    @Test
    fun `diagnostic and empty state entry points use copyable text without changing ordinary data`() {
        assertCopyable("DeviceTargetPage.kt", "error?.let {")
        assertCopyable("DeviceTargetPage.kt", "localizedStringResource(SimpleperfViewerRes.sp_target_capability_selection_hint")
        assertCopyable("DeviceTargetPage.kt", "value,")
        assertCopyable("DeviceTargetPage.kt", "localizedStringResource( when (status)")
        assertCopyable("ReportPage.kt", "message,")
        assertCopyable("ReportPage.kt", "localizedStringResource(SimpleperfViewerRes.sp_diagnostics_no_findings_empty_state")
        assertCopyable("ReportPage.kt", "finding.conclusion,")
        assertCopyable(
            "FirefoxReportWorkspace.kt",
            "localizedStringResource(SimpleperfViewerRes.sp_diagnostics_sample_weight_duration_disclaimer",
        )
        assertCopyable("FirefoxReportDetails.kt", "localizedStringResource(prompt,")
        assertCopyable(
            "FirefoxReportDetails.kt",
            "localizedStringResource(SimpleperfViewerRes.sp_diagnostics_invalid_json_raw_fallback_message",
        )
        assertCopyable("FlameGraphEmptyState.kt", "content.message,")
        assertCopyable("FlameGraphEmptyState.kt", "details,")
        assertCopyable("FlameGraphDetailsPanel.kt", "line,")
        assertCopyable("MarkerChartPanel.kt", "message,")
        assertCopyable("StackChartPanel.kt", "message,")
        assertCopyable("CaptureSettingsSection.kt", "localizedStringResource(SimpleperfViewerRes.sp_capture_sampling_target_required_hint")
        assertCopyable("CpuProfilerWorkspace.kt", "support.orEmpty(),")

        val markerTable = source("MarkerTablePanel.kt")
        assertTrue(markerTable.contains("Text(marker.name,"))
        val flameEmpty = source("FlameGraphEmptyState.kt")
        assertTrue(flameEmpty.contains("Text(content.recoveryLabel,"))
    }

    private fun assertCopyable(
        file: String,
        argument: String,
    ) {
        val normalized = source(file).replace(Regex("\\s+"), " ")
        val call = Regex("\\bCopyableText\\(\\s*(?:text\\s*=\\s*)?" + Regex.escape(argument))
        assertTrue(call.containsMatchIn(normalized), "$file must expose full-message copying for $argument")
    }

    private fun source(file: String): String = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/presentation", file))

    private class MemoryClipboard : Clipboard {
        private var entry: ClipEntry? = null

        override suspend fun getClipEntry(): ClipEntry? = entry

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            entry = clipEntry
        }

        fun text(): String? = entry?.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor) as? String
    }
}
