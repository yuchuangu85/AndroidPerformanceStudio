package com.androidperformancestudio.presentation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FrameImplementation
import com.androidperformancestudio.profileanalysis.StackChartBlockId
import com.androidperformancestudio.profileanalysis.StackChartSnapshot
import com.androidperformancestudio.storage.PanelProjection
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class StackChartComposeUiTest {
    @Test
    fun `stack blocks align with the Firefox timeline gutter with and without details`() {
        listOf(900, 1280).forEach { width ->
            listOf(false, true).forEach { detailsVisible ->
                runDesktopComposeUiTest(width = width, height = 800) {
                    val state =
                        stackChartReportState().let {
                            it.copy(workspace = it.workspace.copy(detailsVisible = detailsVisible))
                        }
                    setContent { ReportPage(state, goldenActions()) }

                    val content = onNodeWithTag("report-content").fetchSemanticsNode().boundsInRoot
                    val canvas = onNodeWithTag("stack-chart-canvas").fetchSemanticsNode().boundsInRoot
                    val firstBlock = onNodeWithTag("stack-block-block-1").fetchSemanticsNode().boundsInRoot
                    assertEquals(content.left, canvas.left)
                    assertEquals(canvas.left + 150f, firstBlock.left)
                }
            }
        }
    }

    @Test
    fun `left edge clicks use plot coordinates and graph drags pan without committing a range`() =
        runDesktopComposeUiTest(width = 365, height = 64) {
            var selected: StackChartBlockId? = null
            var preview: StackChartViewport? = null
            val report = requireNotNull(stackChartReportState().lastReadyReport)
            val snapshot = (report.stackChart as PanelProjection.Ready).value
            setContent {
                StackChartCanvas(
                    snapshot = snapshot,
                    viewport = StackChartViewport(0, 10_001),
                    selectedBlockId = null,
                    onSelect = { selected = it },
                    onViewportChange = { preview = it },
                )
            }

            val canvas = onNodeWithTag("stack-chart-canvas")
            canvas.performMouseInput { click(Offset(4f, 8f)) }
            mainClock.advanceTimeBy(500)
            waitForIdle()
            assertEquals(null, selected)
            canvas.performMouseInput { click(Offset(154f, 8f)) }
            mainClock.advanceTimeBy(500)
            waitForIdle()
            assertEquals(StackChartBlockId("block-1"), selected)
            canvas.performMouseInput { click(Offset(262f, 8f)) }
            mainClock.advanceTimeBy(500)
            waitForIdle()
            assertEquals(StackChartBlockId("block-2"), selected)
            canvas.performMouseInput { click(Offset(364f, 8f)) }
            mainClock.advanceTimeBy(500)
            waitForIdle()
            assertEquals(null, selected)

            canvas.performMouseInput {
                moveTo(Offset(0f, 40f))
                press()
                moveTo(Offset(4f, 40f))
                moveTo(Offset(175f, 40f))
                moveTo(Offset(350f, 40f))
                release()
            }
            assertEquals(null, preview)
        }

    @Test
    fun `selecting a visible block dispatches its stable identity`() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            var selected: StackChartBlockId? = null
            val state = stackChartReportState()

            setContent {
                ReportPage(
                    state,
                    goldenActions().copy(onSelectStackChartBlock = { selected = it }),
                )
            }

            onNodeWithTag("stack-chart-panel").assertExists()
            onNodeWithTag("stack-block-block-1").performClick()
            mainClock.advanceTimeBy(500)
            waitForIdle()
            assertEquals(StackChartBlockId("block-1"), selected)
        }

    @Test
    fun `selected block is exposed through semantics`() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            val selected = StackChartBlockId("block-1")
            val base = stackChartReportState()
            val state =
                base.copy(
                    workspace =
                        base.workspace.copy(
                            selections = base.workspace.selections.copy(stackChartBlockId = selected),
                        ),
                )

            setContent { ReportPage(state, goldenActions()) }

            onNodeWithTag("stack-block-block-1").assertIsSelected()
        }
}

internal fun stackChartReportState() =
    sampleReportState(ReportTab.STACK_CHART).let { base ->
        val report = requireNotNull(base.lastReadyReport)
        val frame =
            CallStackFrame(
                frameId = 1,
                functionId = FlameFunctionId(1),
                symbolName = "renderFrame",
                resource = "/system/lib64/libui.so",
                virtualAddress = 0,
                implementation = FrameImplementation.NATIVE,
            )
        val snapshot =
            StackChartSnapshot(
                framesById = mapOf(1L to frame),
                blocks = listOf(block("block-1", 0, 5_000, 0), block("block-2", 5_000, 10_001, 0)),
                startNanos = 0,
                endNanosExclusive = 10_001,
                maxDepth = 0,
                emptyReason = null,
            )
        val updated =
            report.copy(
                sessionOverview = report.sessionOverview.copy(startNanos = 0, endNanosInclusive = 10_000),
                stackChart = PanelProjection.Ready(snapshot),
            )
        base.copy(loadState = ReportLoadState.Ready(updated), lastReadyReport = updated)
    }
