package com.androidperformancestudio.presentation

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.application.FlameGraphDetailsState
import com.androidperformancestudio.application.FlameGraphFrameDetails
import com.androidperformancestudio.application.FlameGraphPanelState
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.visualization.FlameGraphLayout
import com.androidperformancestudio.visualization.FlameViewport
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FlameGraphComposeUiTest {
    @Test
    fun `wasd changes the flame graph horizontal viewport`() =
        runDesktopComposeUiTest(width = 700, height = 260) {
            setContent {
                MaterialTheme {
                    FlameGraphPanel(
                        sessionIdentity = Path.of("keyboard-navigation"),
                        state = FlameGraphPanelState(),
                        snapshot = accessibilitySnapshot(),
                        actions = goldenActions(),
                    )
                }
            }

            val renderNode = onNodeWithContentDescription("renderFrame, 60%, Native")
            val before = renderNode.fetchSemanticsNode().boundsInRoot.width

            onNodeWithContentDescription("Flame graph call stacks").performKeyInput {
                keyDown(Key.W)
                keyUp(Key.W)
            }
            waitForIdle()

            val after = onNodeWithContentDescription("renderFrame, 60%, Native").fetchSemanticsNode().boundsInRoot.width
            assertTrue(after > before, "W should widen the visible render frame: $before -> $after")
        }

    @Test
    fun `semantic overlay selects frames and opens details without a pointer gesture`() =
        runDesktopComposeUiTest(width = 700, height = 260) {
            val snapshot = accessibilitySnapshot()
            val layout = FlameGraphLayout.layout(snapshot, FlameViewport(widthPx = 700, heightPx = 80, scrollRow = 0))
            var selected by mutableStateOf<FlameCallNodeId?>(null)
            var details by mutableStateOf<FlameGraphDetailsState>(FlameGraphDetailsState.Closed)
            var contextMenuOpenedAt by mutableStateOf<FlameCallNodeId?>(null)

            setContent {
                MaterialTheme {
                    FlameGraphSemanticsOverlay(
                        snapshot = snapshot,
                        layout = layout,
                        selectedNodeId = selected,
                        onSelect = { selected = it },
                        onOpenDetails = { nodeId ->
                            selected = nodeId
                            details =
                                FlameGraphDetailsState.Ready(
                                    FlameGraphFrameDetails.SymbolFallback(
                                        function = "renderFrame",
                                        resource = "libui.so",
                                        address = 0x10,
                                        libraryOffset = 0x10,
                                        buildId = null,
                                        reason = "test fallback",
                                    ),
                                )
                        },
                        onOpenContextMenu = { nodeId, _ -> contextMenuOpenedAt = nodeId },
                        modifier = Modifier.size(700.dp, 80.dp),
                    )
                    FlameGraphDetailsPanel(details, onClose = { details = FlameGraphDetailsState.Closed })
                }
            }

            val renderNode = onNodeWithContentDescription("renderFrame, 60%, Native")
            renderNode.fetchSemanticsNode()
            renderNode.performSemanticsAction(SemanticsActions.OnClick)
            renderNode.assertIsSelected()
            assertEquals(FlameCallNodeId(2), selected)

            val customActions = renderNode.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            requireNotNull(customActions.firstOrNull { it.label == "Open details" }).action()
            onNodeWithTag("flame-details").fetchSemanticsNode()

            requireNotNull(customActions.firstOrNull { it.label == "Open context menu" }).action()
            assertEquals(FlameCallNodeId(2), contextMenuOpenedAt)
        }
}
