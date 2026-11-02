package com.androidperformancestudio.presentation

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.application.FlameGraphDetailsState
import com.androidperformancestudio.application.FlameGraphFrameDetails
import com.androidperformancestudio.application.FlameGraphPanelState
import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FlameGraphRows
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.profileanalysis.FrameImplementation
import com.androidperformancestudio.visualization.FlameGraphLayout
import com.androidperformancestudio.visualization.FlameViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class FlameGraphComposeUiTest {
    @Test
    fun `ctrl and shift wheel events are consumed without moving the flame viewport`() =
        runDesktopComposeUiTest(width = 700, height = 360) {
            var wheelConsumed = false
            var wheelModifiers = false to false
            setContent {
                MaterialTheme {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Final) { event ->
                                wheelConsumed = event.changes.isNotEmpty() && event.changes.all { it.isConsumed }
                                wheelModifiers =
                                    event.keyboardModifiers.isCtrlPressed to event.keyboardModifiers.isShiftPressed
                            },
                    ) {
                        FlameGraphPanel(
                            state = FlameGraphPanelState(),
                            snapshot = deepViewportSnapshot(),
                            actions = goldenActions(),
                        )
                    }
                }
            }
            val canvas = onNodeWithContentDescription("Flame graph call stacks")
            val frame = onNodeWithContentDescription("depth-5, 100%, Native")
            val initial = frame.fetchSemanticsNode().boundsInRoot
            canvas.performMouseInput {
                moveTo(center)
                scroll(-2f)
            }
            waitForIdle()
            var before = frame.fetchSemanticsNode().boundsInRoot
            assertTrue(before.top > initial.top, "An ordinary wheel event must reach and move the flame viewport")
            assertTrue(wheelConsumed, "An ordinary wheel event inside the viewport must be consumed")
            canvas.performMouseInput {
                moveTo(center)
                scroll(-1f)
            }
            waitForIdle()
            val next = frame.fetchSemanticsNode().boundsInRoot
            assertTrue(next.top > before.top, "Repeated wheel events must accumulate from the current viewport")
            before = next
            listOf(Key.CtrlLeft, Key.ShiftLeft).forEach { modifier ->
                wheelConsumed = false
                canvas.performKeyInput { keyDown(modifier) }
                canvas.performMouseInput { scroll(-1f) }
                canvas.performKeyInput { keyUp(modifier) }
                waitForIdle()
                assertEquals((modifier == Key.CtrlLeft) to (modifier == Key.ShiftLeft), wheelModifiers)
                assertTrue(wheelConsumed, "$modifier wheel event was not consumed")
                assertEquals(before, frame.fetchSemanticsNode().boundsInRoot)
            }
        }

    @Test
    fun `vertical keys require no ctrl meta alt or shift modifiers`() =
        runDesktopComposeUiTest(width = 700, height = 360) {
            setContent {
                MaterialTheme {
                    FlameGraphPanel(
                        state = FlameGraphPanelState(),
                        snapshot = deepViewportSnapshot(),
                        actions = goldenActions(),
                    )
                }
            }
            val canvas = onNodeWithContentDescription("Flame graph call stacks")
            val frame = onNodeWithContentDescription("depth-5, 100%, Native")
            val before = frame.fetchSemanticsNode().boundsInRoot
            mainClock.autoAdvance = false
            listOf(Key.CtrlLeft, Key.MetaLeft, Key.AltLeft, Key.ShiftLeft).forEach { modifier ->
                listOf(Key.W, Key.S, Key.K, Key.J).forEach { key ->
                    canvas.performKeyInput {
                        keyDown(modifier)
                        keyDown(key)
                    }
                    repeat(3) { mainClock.advanceTimeByFrame() }
                    canvas.performKeyInput {
                        keyUp(key)
                        keyUp(modifier)
                    }
                    waitForIdle()
                    assertEquals(before, frame.fetchSemanticsNode().boundsInRoot, "$modifier + $key moved the viewport")
                }
            }
            listOf(Key.W to Key.S, Key.K to Key.J).forEach { (up, down) ->
                canvas.performKeyInput { keyDown(up) }
                repeat(3) { mainClock.advanceTimeByFrame() }
                canvas.performKeyInput { keyUp(up) }
                waitForIdle()
                assertTrue(frame.fetchSemanticsNode().boundsInRoot.top > before.top)
                val stopped = frame.fetchSemanticsNode().boundsInRoot
                repeat(3) { mainClock.advanceTimeByFrame() }
                assertEquals(stopped, frame.fetchSemanticsNode().boundsInRoot, "Keyup must stop continuous motion")
                canvas.performKeyInput { keyDown(down) }
                repeat(3) { mainClock.advanceTimeByFrame() }
                canvas.performKeyInput { keyUp(down) }
                waitForIdle()
                assertTrue(
                    frame.fetchSemanticsNode().boundsInRoot.top < stopped.top,
                    "The opposite held key must reverse vertical movement",
                )
            }
        }

    @Test
    fun `synthetic fractional line wheel events accumulate subpixels instead of whole rows`() =
        runDesktopComposeUiTest(width = 700, height = 360) {
            setContent {
                MaterialTheme {
                    FlameGraphPanel(
                        state = FlameGraphPanelState(),
                        snapshot = deepViewportSnapshot(),
                        actions = goldenActions(),
                    )
                }
            }
            val canvas = onNodeWithContentDescription("Flame graph call stacks")
            val frame = onNodeWithContentDescription("depth-5, 100%, Native")
            val before = frame.fetchSemanticsNode().boundsInRoot.top
            repeat(4) {
                canvas.performMouseInput {
                    moveTo(center)
                    scroll(-1f / 60f)
                }
                waitForIdle()
            }
            assertEquals(before + 1f, frame.fetchSemanticsNode().boundsInRoot.top)
        }

    @Test
    fun `native trackpad pan uses fractional physical pixels and ignores horizontal movement`() =
        runDesktopComposeUiTest(width = 700, height = 360) {
            setContent {
                MaterialTheme {
                    FlameGraphPanel(
                        state = FlameGraphPanelState(),
                        snapshot = deepViewportSnapshot(),
                        actions = goldenActions(),
                    )
                }
            }
            val canvas = onNodeWithContentDescription("Flame graph call stacks")
            val frame = onNodeWithContentDescription("depth-5, 100%, Native")
            val before = frame.fetchSemanticsNode().boundsInRoot
            canvas.performMultiModalInput {
                trackpad {
                    moveTo(center)
                    panStart()
                    repeat(4) { panMoveBy(Offset(30f, 0.25f)) }
                    panEnd()
                }
            }
            waitForIdle()
            val after = frame.fetchSemanticsNode().boundsInRoot
            assertEquals(before.top + 1f, after.top)
            assertEquals(before.left, after.left)
            assertEquals(before.width, after.width)
        }

    @Test
    fun `drag pans by vertical pixels only and never selects or opens details`() =
        runDesktopComposeUiTest(width = 700, height = 360) {
            var selectionCount = 0
            var detailsCount = 0
            setContent {
                MaterialTheme {
                    FlameGraphPanel(
                        state = FlameGraphPanelState(),
                        snapshot = deepViewportSnapshot(),
                        actions =
                            goldenActions().copy(
                                onSelectCallNode = { selectionCount++ },
                                onOpenFlameDetails = { detailsCount++ },
                            ),
                    )
                }
            }
            val canvas = onNodeWithContentDescription("Flame graph call stacks")
            val frame = onNodeWithContentDescription("depth-5, 100%, Native")
            val before = frame.fetchSemanticsNode().boundsInRoot
            canvas.performMouseInput {
                moveTo(center)
                press()
                moveBy(Offset(30f, 7.25f))
                moveBy(Offset(30f, 0.75f))
                release()
            }
            waitForIdle()
            val after = frame.fetchSemanticsNode().boundsInRoot
            assertEquals(before.top + 8f, after.top)
            assertEquals(before.left, after.left)
            assertEquals(before.width, after.width)
            assertEquals(0, selectionCount)
            assertEquals(0, detailsCount)
        }

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
    fun `wasd never changes the flame graph horizontal viewport`() =
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
            mainClock.autoAdvance = false

            listOf(Key.W, Key.S, Key.A, Key.D, Key.H, Key.L, Key.Q, Key.E, Key.Y, Key.U).forEach { key ->
                val canvas = onNodeWithContentDescription("Flame graph call stacks")
                canvas.performKeyInput { keyDown(key) }
                repeat(3) { mainClock.advanceTimeByFrame() }
                canvas.performKeyInput { keyUp(key) }
                waitForIdle()
                assertEquals(before, renderNode.fetchSemanticsNode().boundsInRoot.width)
            }
        }

    @Test
    fun `vertical viewport survives snapshot changes but resets for a new session or direction`() =
        runDesktopComposeUiTest(width = 700, height = 360) {
            var snapshot by mutableStateOf(deepViewportSnapshot())
            var sessionKey by mutableStateOf("capture-1")
            setContent {
                MaterialTheme {
                    FlameGraphPanel(
                        state = FlameGraphPanelState(),
                        snapshot = snapshot,
                        actions = goldenActions(),
                        sessionKey = sessionKey,
                    )
                }
            }
            waitForIdle()
            val node = onNodeWithContentDescription("depth-5, 100%, Native")
            val before = node.fetchSemanticsNode().boundsInRoot.top
            val canvas = onNodeWithContentDescription("Flame graph call stacks")
            mainClock.autoAdvance = false
            canvas.performKeyInput { keyDown(Key.W) }
            repeat(3) { mainClock.advanceTimeByFrame() }
            canvas.performKeyInput { keyUp(Key.W) }
            waitForIdle()
            val scrolled = node.fetchSemanticsNode().boundsInRoot.top
            assertTrue(scrolled > before, "W must move bottom-up content down to reveal deeper rows")

            snapshot = deepViewportSnapshot(rowCount = 60)
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertEquals(scrolled, node.fetchSemanticsNode().boundsInRoot.top)

            sessionKey = "capture-2"
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertEquals(before, node.fetchSemanticsNode().boundsInRoot.top)

            snapshot = deepViewportSnapshot(startsAtBottom = false)
            mainClock.advanceTimeByFrame()
            waitForIdle()
            val topDownBefore = node.fetchSemanticsNode().boundsInRoot.top
            canvas.performKeyInput { keyDown(Key.S) }
            repeat(3) { mainClock.advanceTimeByFrame() }
            canvas.performKeyInput { keyUp(Key.S) }
            waitForIdle()
            assertTrue(node.fetchSemanticsNode().boundsInRoot.top < topDownBefore)
        }

    @Test
    fun `blur stops held motion before keyup is delivered`() =
        runDesktopComposeUiTest(width = 700, height = 360) {
            val outsideFocus = FocusRequester()
            setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        FlameGraphPanel(
                            state = FlameGraphPanelState(),
                            snapshot = deepViewportSnapshot(),
                            actions = goldenActions(),
                        )
                        Box(Modifier.size(1.dp).focusRequester(outsideFocus).focusable())
                    }
                }
            }
            val canvas = onNodeWithContentDescription("Flame graph call stacks")
            val frame = onNodeWithContentDescription("depth-5, 100%, Native")
            mainClock.autoAdvance = false
            val before = frame.fetchSemanticsNode().boundsInRoot
            canvas.performKeyInput { keyDown(Key.W) }
            repeat(3) { mainClock.advanceTimeByFrame() }
            assertTrue(frame.fetchSemanticsNode().boundsInRoot.top > before.top)
            runOnIdle { outsideFocus.requestFocus() }
            val stopped = frame.fetchSemanticsNode().boundsInRoot
            repeat(4) { mainClock.advanceTimeByFrame() }
            assertEquals(stopped, frame.fetchSemanticsNode().boundsInRoot)
            canvas.performKeyInput { keyUp(Key.W) }
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
                    FirefoxFrameDetailsBottomBox(
                        details,
                        onClose = { details = FlameGraphDetailsState.Closed },
                        style = rememberFirefoxFlameGraphStyle(),
                    )
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

private fun deepViewportSnapshot(
    rowCount: Int = 50,
    startsAtBottom: Boolean = true,
): FlameGraphSnapshot {
    val ids = LongArray(rowCount) { it + 1L }
    return FlameGraphSnapshot(
        query = CallStackAnalysisQuery(),
        callNodes =
            CallNodeTable(
                ids = ids,
                parentIndexes = IntArray(rowCount) { it - 1 },
                frameIds = ids,
                depths = IntArray(rowCount) { it },
                inclusiveWeights = LongArray(rowCount) { 1 },
                selfWeights = LongArray(rowCount),
                sampleCounts = LongArray(rowCount) { 1 },
                threadCounts = IntArray(rowCount) { 1 },
                categories = List(rowCount) { "Native" },
                framesById =
                    ids.associateWith { id ->
                        CallStackFrame(
                            id,
                            FlameFunctionId(id),
                            "depth-${id - 1}",
                            "app.so",
                            0,
                            FrameImplementation.NATIVE,
                        )
                    },
            ),
        rows =
            FlameGraphRows(
                nodeIndexesByRow = List(rowCount) { intArrayOf(it) },
                starts = DoubleArray(rowCount),
                ends = DoubleArray(rowCount) { 1.0 },
                startsAtBottom = startsAtBottom,
            ),
        totalWeight = 1,
        emptyReason = null,
        invalidTransforms = emptyList(),
    )
}
