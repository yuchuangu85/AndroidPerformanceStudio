@file:Suppress("MaxLineLength")

package com.androidperformancestudio.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.profileanalysis.CallStackTransform
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.StackChartBlock
import com.androidperformancestudio.profileanalysis.StackChartBlockId
import com.androidperformancestudio.profileanalysis.StackChartSnapshot
import com.androidperformancestudio.storage.PanelProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StackChartInteractionParityTest {
    @Test
    fun `drag keeps publishing the latest viewport while preview echoes recompose the chart`() =
        runDesktopComposeUiTest(width = 900, height = 160) {
            val snapshot = (linkedStackChartState().lastReadyReport!!.stackChart as PanelProjection.Ready).value
            var preview by mutableStateOf(StackChartViewport(25_000_000, 75_000_000))
            val published = mutableListOf<StackChartViewport>()
            setContent {
                StackChartCanvas(
                    snapshot,
                    StackChartViewport(0, 100_000_000),
                    null,
                    onSelect = {},
                    previewViewport = preview,
                    onViewportChange = {
                        preview = it
                        published += it
                    },
                )
            }
            val canvas = onNodeWithTag("stack-chart-canvas")
            canvas.performMouseInput {
                moveTo(Offset(500f, 100f))
                press()
                moveTo(Offset(450f, 100f))
            }
            waitForIdle()
            canvas.performMouseInput { moveTo(Offset(400f, 100f)) }
            waitForIdle()
            val during = preview
            canvas.performMouseInput { release() }
            waitForIdle()
            assertTrue(published.size >= 2)
            assertTrue(published.zipWithNext().all { (left, right) -> left.startNanos <= right.startNanos })
            assertEquals(during, preview, "Releasing must not restore the pre-drag viewport")
        }

    @Test
    fun `slash focuses stack search and does not invoke graph character shortcuts`() =
        runDesktopComposeUiTest(width = 1200, height = 760) {
            setContent { ReportPage(linkedStackChartState(), goldenActions()) }
            onNodeWithTag("stack-chart-canvas").performKeyInput {
                keyDown(Key.Slash)
                keyUp(Key.Slash)
            }
            onNodeWithTag("stack-filter-input").assertIsFocused()
        }

    @Test
    fun `viewport zoom previews and only the explicit zoom command commits a range`() =
        runDesktopComposeUiTest(width = 900, height = 760) {
            var state by mutableStateOf(linkedStackChartState())
            var committed: Pair<Long?, Long?>? = null
            var previews = 0
            setContent {
                ReportPage(
                    state,
                    goldenActions().copy(
                        onFlamePreviewRange = { range ->
                            previews++
                            state = state.copy(callStackQuery = state.callStackQuery.copy(previewRange = range))
                        },
                        onTimeRange = { start, end -> committed = start to end },
                    ),
                )
            }
            val canvas = onNodeWithTag("stack-chart-canvas")
            canvas.performMouseInput { scroll(1f) }
            waitForIdle()
            assertEquals(0, previews, "Vertical scrolling must not submit a query")
            mainClock.autoAdvance = false
            canvas.performKeyInput {
                keyDown(Key.Q)
            }
            repeat(3) { mainClock.advanceTimeByFrame() }
            canvas.performKeyInput {
                keyUp(Key.Q)
            }
            mainClock.autoAdvance = true
            waitForIdle()
            assertTrue(previews > 0)
            assertEquals(null, committed)
            onNodeWithText("Zoom to selection").performClick()
            val range = state.callStackQuery.previewRange!!
            assertEquals(range.startNanosInclusive to range.endNanosExclusive, committed)
        }

    @Test
    fun `hover displays interval duration and context suppresses tooltip and dispatches transform`() =
        runDesktopComposeUiTest(width = 900, height = 760) {
            var transform: CallStackTransform? = null
            setContent {
                ReportPage(linkedStackChartState(), goldenActions().copy(onApplyFlameTransform = { transform = it }))
            }
            val canvas = onNodeWithTag("stack-chart-canvas")
            canvas.performMouseInput { moveTo(Offset(200f, 22f)) }
            waitForIdle()
            onNodeWithTag("firefox-flame-tooltip").assertExists()
            onNodeWithText("25.0ms").assertExists()
            canvas.performMouseInput { click(Offset(200f, 22f), MouseButton.Secondary) }
            waitForIdle()
            onNodeWithTag("firefox-flame-tooltip").assertDoesNotExist()
            onNodeWithText("Focus on function").performMouseInput { click() }
            waitForIdle()
            assertTrue(transform is CallStackTransform.FocusFunction)
        }

    @Test
    fun `double click and enter open shared details and blank click clears selection`() =
        runDesktopComposeUiTest(width = 900, height = 760) {
            var state by mutableStateOf(linkedStackChartState())
            val opened = mutableListOf<FlameCallNodeId>()
            setContent {
                ReportPage(
                    state,
                    goldenActions().copy(
                        onOpenFlameDetails = { opened += it },
                        onSelectStackChartBlock = { id ->
                            state =
                                state.copy(
                                    workspace =
                                        state.workspace.copy(
                                            selections =
                                                state.workspace.selections.copy(
                                                    stackChartBlockId = id,
                                                    callNodeId = if (id == StackChartBlockId("render")) FlameCallNodeId(2) else null,
                                                ),
                                        ),
                                )
                        },
                    ),
                )
            }
            val canvas = onNodeWithTag("stack-chart-canvas")
            canvas.performMouseInput { doubleClick(Offset(200f, 22f)) }
            waitForIdle()
            assertEquals(listOf(FlameCallNodeId(2)), opened)
            canvas.performMouseInput { click(Offset(200f, 22f)) }
            mainClock.advanceTimeBy(500)
            waitForIdle()
            canvas.performKeyInput {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }
            waitForIdle()
            assertEquals(listOf(FlameCallNodeId(2), FlameCallNodeId(2)), opened)
            canvas.performMouseInput { click(Offset(50f, 120f)) }
            mainClock.advanceTimeBy(500)
            waitForIdle()
            assertEquals(null, state.workspace.selections.stackChartBlockId)
        }

    @Test
    fun `dense chart keeps bounded semantics and uses one pointer surface`() =
        runDesktopComposeUiTest(width = 500, height = 64) {
            val base = linkedStackChartState().lastReadyReport!!
            val snapshot = (base.stackChart as PanelProjection.Ready).value
            var selected by mutableStateOf<StackChartBlockId?>(null)
            setContent {
                StackChartCanvas(
                    snapshot.copy(blocks = (0 until 50_000).map { block("dense-$it", it.toLong(), it + 1L, 0) }),
                    StackChartViewport(0, 50_000),
                    selected,
                    onSelect = { selected = it },
                )
            }
            val nodes = onAllNodesWithTag("stack-chart-canvas").fetchSemanticsNodes().single()
            assertTrue(nodes.children.size <= 501, "Semantic children must scale with pixels, not sample count")
            onNodeWithTag("stack-chart-canvas").performMouseInput { click(Offset(240f, 8f)) }
            mainClock.advanceTimeBy(500)
            waitForIdle()
            assertTrue(selected != null)
            onNodeWithTag("stack-block-${selected!!.value}").assertIsSelected()
        }

    @Test
    fun `returning from call tree uses the shared selection instead of the previous stack block`() =
        runDesktopComposeUiTest(width = 900, height = 760) {
            val base = linkedStackChartState()
            var state by mutableStateOf(
                base.copy(
                    workspace =
                        base.workspace.copy(
                            selections =
                                base.workspace.selections.copy(
                                    stackChartBlockId = StackChartBlockId("render"),
                                    callNodeId = FlameCallNodeId(1),
                                ),
                        ),
                ),
            )
            var opened: FlameCallNodeId? = null
            setContent { ReportPage(state, goldenActions().copy(onOpenFlameDetails = { opened = it })) }
            onNodeWithTag("stack-block-root").assertIsSelected()
            onNodeWithTag("stack-chart-canvas").performKeyInput {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }
            waitForIdle()
            assertEquals(FlameCallNodeId(1), opened)
            state = state.copy(workspace = state.workspace.copy(selections = state.workspace.selections.copy(callNodeId = null)))
            waitForIdle()
            onNodeWithTag("stack-block-root").assertIsNotSelected()
            onNodeWithTag("stack-block-render").assertIsNotSelected()
        }
}

internal fun linkedStackChartState() =
    stackChartReportState().let { base ->
        val flame = accessibilitySnapshot()
        val frames = (0 until flame.callNodes.size).mapNotNull(flame.callNodes::frameAt).associateBy { it.frameId }
        val snapshot =
            StackChartSnapshot(
                frames,
                listOf(
                    StackChartBlock(StackChartBlockId("root"), 1, 0, 100_000_000, 0, 10, "main", 10, FlameCallNodeId(1)),
                    StackChartBlock(StackChartBlockId("render"), 1, 0, 25_000_000, 1, 20, "main", 6, FlameCallNodeId(2)),
                ),
                0,
                100_000_000,
                1,
                null,
            )
        val report =
            base.lastReadyReport!!.copy(
                sessionOverview = base.lastReadyReport!!.sessionOverview.copy(startNanos = 0, endNanosInclusive = 99_999_999),
                stackChart = PanelProjection.Ready(snapshot),
                flameGraph = flame,
            )
        base.copy(
            loadState = ReportLoadState.Ready(report),
            lastReadyReport = report,
            workspace = base.workspace.copy(detailsVisible = false),
        )
    }
