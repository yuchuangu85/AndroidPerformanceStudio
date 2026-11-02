@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.androidperformancestudio.methodrecording.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import java.awt.datatransfer.DataFlavor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MethodRecordingCopyableMessagesTest {
    @Test
    fun `method trace errors retain the original message and expose a working copy action`() =
        runDesktopComposeUiTest(width = 520, height = 400) {
            val message = "TRACE_READ_FAILED: Original trace diagnostic details"
            val clipboard = MemoryClipboard()
            setContent {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    ViewerTheme(darkTheme = false) {
                        MethodRecordingScreen(MethodRecordingState(error = message), UiLanguage.ENGLISH)
                    }
                }
            }

            val action = onNodeWithText(message, useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
            runOnIdle { assertTrue(action.action()) }
            waitForIdle()
            assertEquals(message, clipboard.text())
        }

    @Test
    fun `method recording error loading empty and device states use copyable text`() {
        assertCopyable("MethodRecordingScreen.kt", "message,")
        assertCopyable("MethodRecordingScreen.kt", "localizedStringResource(Res.string.working")
        assertCopyable("MethodRecordingScreen.kt", "localizedStringResource(Res.string.no_trace_loaded")
        assertCopyable("MethodRecordingMainPage.kt", "deviceStatus,")
        val screen = source("MethodRecordingScreen.kt")
        assertTrue(Regex("Text\\(\\s*text = method.symbolName").containsMatchIn(screen))
    }

    private fun assertCopyable(file: String, argument: String) {
        val normalized = source(file).replace(Regex("\\s+"), " ")
        val call = Regex("\\bCopyableText\\(\\s*(?:text\\s*=\\s*)?" + Regex.escape(argument))
        assertTrue(call.containsMatchIn(normalized), "$file must expose copying for $argument")
    }

    private fun source(file: String): String =
        Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/methodrecording/app", file))

    private class MemoryClipboard : Clipboard {
        private var entry: ClipEntry? = null

        override suspend fun getClipEntry(): ClipEntry? = entry

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            entry = clipEntry
        }

        fun text(): String? = entry?.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor) as? String
    }
}
