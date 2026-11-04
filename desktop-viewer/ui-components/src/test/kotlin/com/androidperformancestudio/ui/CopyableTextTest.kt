@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.androidperformancestudio.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CopyableTextTest {
    @Test
    fun `tagged feedback keeps its complete text and copy action on the same semantics node`() =
        runDesktopComposeUiTest {
            val reason = "Trace file is missing. Re-import or record a new capture."
            setContent {
                CopyableText(reason, Modifier.testTag("feedback"), language = UiLanguage.ENGLISH)
            }
            val feedback = onNodeWithTag("feedback").assertTextEquals(reason)
            val actions = feedback.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            assertTrue(actions.any { it.label == "Copy full message" })
        }

    @Test
    fun `copy action preserves the full ellipsized multiline reason`() =
        runDesktopComposeUiTest {
            val clipboard = MemoryClipboard()
            val reason =
                "Failed to attach Compose inspector agent\n" +
                    "PathClassLoader ".repeat(50) + "\nCaused by: missing symbol"
            setContent {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    CopyableText(reason, Modifier.width(180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val action =
                onNodeWithText(reason)
                    .fetchSemanticsNode()
                    .config[SemanticsActions.CustomActions]
                    .single { it.label == "Copy full message" }
            runOnIdle { assertTrue(action.action()) }
            waitForIdle()
            assertEquals(reason, clipboard.text())
        }

    @Test
    fun `right click copies the complete current reason in both languages`() {
        listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
            runDesktopComposeUiTest {
                val clipboard = MemoryClipboard()
                val reason = mutableStateOf("Original reason")
                setContent {
                    CompositionLocalProvider(LocalClipboard provides clipboard) {
                        CopyableText(reason.value, language = language)
                    }
                }
                runOnIdle { reason.value = "New reason\nFull failure detail" }
                onNodeWithText(reason.value).performMouseInput { click(button = MouseButton.Secondary) }
                onNodeWithText(if (language == UiLanguage.ENGLISH) "Copy full message" else "复制完整提示").performClick()
                waitForIdle()
                assertEquals(reason.value, clipboard.text())
            }
        }
    }

    @Test
    fun `mouse selection keeps ordinary copy separate from copy full message`() =
        runDesktopComposeUiTest {
            val clipboard = MemoryClipboard()
            setContent {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    CopyableText("Failure reason details", Modifier.testTag("reason"))
                }
            }
            onNodeWithText("Failure reason details").performMouseInput { doubleClick(Offset(12f, 8f)) }
            val copyModifier =
                if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) Key.MetaLeft else Key.CtrlLeft
            onNodeWithTag("reason").performKeyInput {
                keyDown(copyModifier)
                keyDown(Key.C)
                keyUp(Key.C)
                keyUp(copyModifier)
            }
            waitForIdle()
            assertEquals("Failure", clipboard.text())
        }

    @Test
    fun `a neighboring retry button remains clickable and row sizing is preserved`() =
        runDesktopComposeUiTest {
            var retries = 0
            setContent {
                Row(Modifier.width(300.dp)) {
                    CopyableText("Failure reason", Modifier.weight(1f))
                    Button(onClick = { retries++ }) { Text("Retry") }
                }
            }
            onNodeWithText("Retry").performClick()
            assertEquals(1, retries)
            onNodeWithText("Failure reason").assertExists()
        }

    private class MemoryClipboard : Clipboard {
        var entry: ClipEntry? = null

        override suspend fun getClipEntry(): ClipEntry? = entry

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            entry = clipEntry
        }

        fun text(): String? = entry?.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor) as? String
    }
}
