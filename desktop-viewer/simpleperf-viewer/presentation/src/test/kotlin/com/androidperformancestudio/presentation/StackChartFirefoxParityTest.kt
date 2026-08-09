package com.androidperformancestudio.presentation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
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
class StackChartFirefoxParityTest {
    @Test
    fun `stack chart keeps Firefox plot margins row gap and visible labels`() =
        runDesktopComposeUiTest(width = 365, height = 64) {
            setContent {
                StackChartCanvas(
                    snapshot = snapshot(),
                    viewport = StackChartViewport(0, 100),
                    selectedBlockId = null,
                    onSelect = {},
                    onCommitRange = { _, _ -> },
                )
            }

            val pixels = onNodeWithTag("stack-chart-canvas").captureToImage().toPixelMap()
            val hasVisibleLabel =
                (153..190).any { x ->
                    (2..13).any { y -> pixels[x, y].isNear(Color.Black) }
                }
            assertTrue(
                pixels[0, 0].isNear(Color.White) &&
                    pixels[151, 15].isNear(Color.White) &&
                    hasVisibleLabel,
                "Stack Chart must match Firefox's 150 px plot margin, 15 px rows, and visible frame labels",
            )
        }

    private fun snapshot(): StackChartSnapshot {
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
            blocks = listOf(StackChartBlock(StackChartBlockId("block"), 1, 0, 100, 0, frame.frameId, "main", 1)),
            startNanos = 0,
            endNanosExclusive = 100,
            maxDepth = 0,
            emptyReason = null,
        )
    }
}

private fun Color.isNear(other: Color): Boolean =
    kotlin.math.abs(red - other.red) < 0.05f &&
        kotlin.math.abs(green - other.green) < 0.05f &&
        kotlin.math.abs(blue - other.blue) < 0.05f
