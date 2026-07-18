package com.androidperformancestudio.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
        val updated = report.copy(stackChart = PanelProjection.Ready(snapshot))
        base.copy(loadState = ReportLoadState.Ready(updated), lastReadyReport = updated)
    }
