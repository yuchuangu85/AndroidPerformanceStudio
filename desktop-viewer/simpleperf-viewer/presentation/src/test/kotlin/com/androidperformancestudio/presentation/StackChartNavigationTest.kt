package com.androidperformancestudio.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FrameImplementation
import com.androidperformancestudio.profileanalysis.StackChartBlock
import com.androidperformancestudio.profileanalysis.StackChartBlockId
import com.androidperformancestudio.profileanalysis.StackChartSnapshot
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StackChartNavigationTest {
    @Test
    fun `stack chart zooms and pans in every direction`() =
        runDesktopComposeUiTest(width = 365, height = 64) {
            val snapshot = navigationSnapshot()
            setContent {
                MaterialTheme {
                    StackChartCanvas(
                        snapshot = snapshot,
                        viewport = StackChartViewport(0, 100),
                        selectedBlockId = null,
                        onSelect = {},
                        onCommitRange = { _, _ -> },
                    )
                }
            }

            val zoomBlock = onNodeWithTag("stack-block-zoom")
            val beforeZoom = zoomBlock.fetchSemanticsNode().boundsInRoot
            onNodeWithTag("stack-chart-canvas").performKeyInput {
                keyDown(Key.W)
                keyUp(Key.W)
            }
            waitForIdle()
            val afterZoom = zoomBlock.fetchSemanticsNode().boundsInRoot
            assertTrue(afterZoom.width > beforeZoom.width, "W should zoom into the Stack Chart")

            onNodeWithTag("stack-chart-canvas").performKeyInput {
                keyDown(Key.D)
                keyUp(Key.D)
            }
            waitForIdle()
            val afterHorizontalPan = zoomBlock.fetchSemanticsNode().boundsInRoot
            assertTrue(afterHorizontalPan.left < afterZoom.left, "D should pan the Stack Chart right")

            onNodeWithTag("stack-chart-canvas").performKeyInput {
                keyDown(Key.A)
                keyUp(Key.A)
            }
            waitForIdle()
            val afterPanBack = zoomBlock.fetchSemanticsNode().boundsInRoot
            assertTrue(afterPanBack.left > afterHorizontalPan.left, "A should pan the Stack Chart left")

            onNodeWithTag("stack-chart-canvas").performKeyInput {
                keyDown(Key.S)
                keyUp(Key.S)
            }
            waitForIdle()
            val afterZoomOut = zoomBlock.fetchSemanticsNode().boundsInRoot
            assertTrue(afterZoomOut.width < afterPanBack.width, "S should zoom out of the Stack Chart")

            val verticalBlock = onNodeWithTag("stack-block-depth-2")
            val beforeVerticalPan = verticalBlock.fetchSemanticsNode().boundsInRoot
            onNodeWithTag("stack-chart-canvas").performMouseInput { scroll(1f) }
            waitForIdle()
            val afterVerticalPan = verticalBlock.fetchSemanticsNode().boundsInRoot
            assertTrue(
                afterVerticalPan.top < beforeVerticalPan.top,
                "Mouse wheel should pan the Stack Chart vertically",
            )

            onNodeWithTag("stack-chart-canvas").performMouseInput { scroll(-1f) }
            waitForIdle()
            val afterVerticalPanBack = verticalBlock.fetchSemanticsNode().boundsInRoot
            assertTrue(afterVerticalPanBack.top > afterVerticalPan.top, "Reverse wheel should pan the Stack Chart back")
        }
}

private fun navigationSnapshot(): StackChartSnapshot {
    val frame =
        CallStackFrame(
            frameId = 1,
            functionId = FlameFunctionId(1),
            symbolName = "renderFrame",
            resource = "libui.so",
            virtualAddress = 1,
            implementation = FrameImplementation.NATIVE,
        )
    return StackChartSnapshot(
        framesById = mapOf(frame.frameId to frame),
        blocks =
            listOf(
                navigationBlock("zoom", 30, 40, 0),
                navigationBlock("depth-1", 0, 100, 1),
                navigationBlock("depth-2", 0, 100, 2),
                navigationBlock("depth-3", 0, 100, 3),
                navigationBlock("depth-4", 0, 100, 4),
            ),
        startNanos = 0,
        endNanosExclusive = 100,
        maxDepth = 4,
        emptyReason = null,
    )
}

private fun navigationBlock(
    id: String,
    start: Long,
    end: Long,
    depth: Int,
) = StackChartBlock(StackChartBlockId(id), 1, start, end, depth, 1, "main", 1)
