package com.androidperformancestudio.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.visualization.FirefoxFlameGraphStyle
import com.androidperformancestudio.visualization.FlameTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FlameGraphContextMenuUiTest {
    @Test
    fun `popup keyboard navigation skips disabled commands and escape dismisses`() =
        runDesktopComposeUiTest(width = 640, height = 400) {
            val commands = mutableListOf<FlameGraphContextCommand>()
            var dismissals = 0
            val first = FlameGraphContextCommand.Copy("first")
            val last = FlameGraphContextCommand.Copy("last")
            setContent {
                MaterialTheme {
                    FirefoxFlameGraphContextMenu(
                        entries =
                            listOf(
                                FlameGraphContextEntry("First", "f", first),
                                FlameGraphContextEntry(
                                    "Unavailable",
                                    "d",
                                    FlameGraphContextCommand.Clear,
                                    enabled = false,
                                ),
                                FlameGraphContextEntry("Last", null, last, separatorBefore = true),
                            ),
                        anchor = Offset.Zero,
                        style = FirefoxFlameGraphStyle.resolve(FlameTheme.LIGHT),
                        onCommand = { commands += it },
                        onDismiss = { dismissals++ },
                    )
                }
            }

            onNodeWithTag("flame-context-entry-1").assertIsNotEnabled()
            onNodeWithTag("flame-context-menu").performKeyInput {
                pressKey(Key.DirectionDown)
                pressKey(Key.DirectionDown)
                pressKey(Key.Enter)
            }
            assertEquals<List<FlameGraphContextCommand>>(listOf(last), commands)
            onNodeWithTag("flame-context-menu").performKeyInput { pressKey(Key.D) }
            assertEquals<List<FlameGraphContextCommand>>(listOf(last), commands)
            onNodeWithTag("flame-context-menu").performKeyInput { pressKey(Key.F) }
            assertEquals<List<FlameGraphContextCommand>>(listOf(last, first), commands)
            onNodeWithTag("flame-context-menu").performKeyInput { pressKey(Key.Escape) }
            assertEquals(1, dismissals)
        }
}
