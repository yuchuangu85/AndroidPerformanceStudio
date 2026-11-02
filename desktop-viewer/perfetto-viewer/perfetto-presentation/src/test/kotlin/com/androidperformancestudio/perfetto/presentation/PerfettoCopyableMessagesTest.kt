@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.androidperformancestudio.perfetto.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ui.ViewerTheme
import java.awt.datatransfer.DataFlavor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PerfettoCopyableMessagesTest {
    @Test
    fun `trace workspace failures expose the complete original reason and a working copy action`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            val message = "TRACE_OPEN_FAILED: " + "Original failure detail. ".repeat(30)
            val clipboard = MemoryClipboard()
            setContent {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    ViewerTheme(darkTheme = false) {
                        TraceAnalyzerWorkspace(
                            state = TraceAnalyzerWorkspaceState(error = message),
                            actions = TraceAnalyzerWorkspaceActions(),
                        )
                    }
                }
            }

            onNodeWithTag("trace-workspace-error").assertExists()
            val action = onNodeWithText(message, useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
            runOnIdle { assertTrue(action.action()) }
            waitForIdle()
            assertEquals(message, clipboard.text())
        }

    @Test
    fun `diagnostic limitations retain copy actions when their displayed text overflows`() =
        runDesktopComposeUiTest(width = 320, height = 240) {
            val limitation = "Unavailable because " + "the source was not collected. ".repeat(25)
            val clipboard = MemoryClipboard()
            setContent {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    ViewerTheme(darkTheme = true) { EvidenceLine("Limitation", limitation) }
                }
            }

            val action = onNodeWithText(limitation, useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
            runOnIdle { assertTrue(action.action()) }
            waitForIdle()
            assertEquals(limitation, clipboard.text())
        }

    @Test
    fun `capture reasons states and trace empty states use copyable text`() {
        assertCopyable("PerfettoCapturePage.kt", "localizedStringResource(Res.string.capability_query_failed")
        assertCopyable("PerfettoCapturePage.kt", "it,")
        assertCopyable("PerfettoCapturePage.kt", "when (state)")
        assertCopyable("TraceAnalyzerWorkspace.kt", "message,")
        assertCopyable("TraceAnalyzerWorkspace.kt", "value,")
        assertCopyable("TraceAnalyzerWorkspace.kt", "text,")
        assertCopyable("TraceAnalyzerWorkspace.kt", "localizedStringResource(Res.string.trace_workspace_no_trace,")
        assertCopyable("TraceAnalyzerWorkspace.kt", "localizedStringResource(Res.string.trace_workspace_no_trace_description,")
        assertCopyable("TraceAnalyzerDetails.kt", "localizedStringResource(Res.string.trace_workspace_read_only_query,")
        assertCopyable("TraceAnalyzerBookmarks.kt", "localizedStringResource(Res.string.trace_workspace_no_anchors,")
    }

    private fun assertCopyable(file: String, argument: String) {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/perfetto/presentation", file))
        val normalized = source.replace(Regex("\\s+"), " ")
        val call = Regex("\\bCopyableText\\(\\s*(?:text\\s*=\\s*)?" + Regex.escape(argument))
        assertTrue(call.containsMatchIn(normalized), "$file must expose full-message copying for $argument")
    }

    private class MemoryClipboard : Clipboard {
        private var entry: ClipEntry? = null

        override suspend fun getClipEntry(): ClipEntry? = entry

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            entry = clipEntry
        }

        fun text(): String? = entry?.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor) as? String
    }
}
