package com.androidperformancestudio.presentation

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.application.FlameGraphDetailsState
import com.androidperformancestudio.application.FlameGraphFrameDetails
import com.androidperformancestudio.application.FlameGraphPanelState
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.visualization.FlameGraphLayout
import com.androidperformancestudio.visualization.FlameViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FlameGraphComposeUiTest {
    @Test
    fun `default Firefox tooltip follows a real hover and closes behind context menu`() =
        runDesktopComposeUiTest(width = 760, height = 320) {
            var panelState by mutableStateOf(FlameGraphPanelState())
            var hoverDispatchCount = 0
            val actions =
                goldenActions().copy(
                    onHoverFlameNode = { nodeId ->
                        hoverDispatchCount++
                        panelState = panelState.copy(hoveredNodeId = nodeId)
                    },
                )
            setContent {
                MaterialTheme {
                    FlameGraphPanel(
                        state = panelState,
                        snapshot = accessibilitySnapshot(),
                        actions = actions,
                    )
                }
            }

            val canvas = onNodeWithContentDescription("Flame graph call stacks")
            val canvasBounds = canvas.fetchSemanticsNode().boundsInRoot
            val frameBounds = onNodeWithContentDescription("renderFrame, 60%, Native").fetchSemanticsNode().boundsInRoot
            val hoverPosition = Offset(x = 20f, y = frameBounds.center.y - canvasBounds.top)
            canvas.performMouseInput { moveTo(hoverPosition) }
            waitUntil { panelState.hoveredNodeId != null }

            val tooltip = onNodeWithTag("firefox-flame-tooltip").fetchSemanticsNode().boundsInRoot
            assertTrue(
                kotlin.math.abs(tooltip.left - (canvasBounds.left + hoverPosition.x + 11f)) <= 2f,
                "Tooltip did not follow the pointer with Firefox's 11px offset: $tooltip",
            )

            val dispatchCountAfterEnteringFrame = hoverDispatchCount
            canvas.performMouseInput { moveTo(hoverPosition + Offset(4f, 0f)) }
            waitForIdle()
            assertEquals(
                dispatchCountAfterEnteringFrame,
                hoverDispatchCount,
                "Moving inside one frame must not rebuild the tooltip on every pointer event",
            )
            assertEquals(
                tooltip,
                onNodeWithTag("firefox-flame-tooltip").fetchSemanticsNode().boundsInRoot,
                "The tooltip anchor must remain stable while the pointer stays in one frame",
            )

            panelState = panelState.copy(contextNodeId = panelState.hoveredNodeId)
            waitForIdle()
            onNodeWithTag("firefox-flame-tooltip").assertDoesNotExist()
        }

    @Test
    fun `wasd changes the flame graph horizontal viewport`() =
        runDesktopComposeUiTest(width = 700, height = 260) {
            setContent {
                MaterialTheme {
                    FlameGraphPanel(
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
