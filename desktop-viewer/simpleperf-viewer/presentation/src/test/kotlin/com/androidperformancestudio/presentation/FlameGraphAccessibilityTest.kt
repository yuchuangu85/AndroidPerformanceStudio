package com.androidperformancestudio.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.CallStackDirection
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

@OptIn(ExperimentalTestApi::class)
class FlameGraphAccessibilityTest {
    @Test
    fun `semantic nodes describe visible frames and preserve selected offscreen frame`() {
        runDesktopComposeUiTest {
            setContent {
                val snapshot = accessibilitySnapshot()
                val visibleLayout =
                    FlameGraphLayout.layout(
                        snapshot,
                        FlameViewport(widthPx = 1_000, heightPx = 16, scrollRow = 0),
                    )

                val nodes = FlameGraphSemanticsPresenter.nodes(snapshot, visibleLayout, FlameCallNodeId(3))

                assertEquals(
                    listOf("root, 100%, Native", "renderFrame, 60%, Native", "drawFrame, 20%, Managed"),
                    nodes.map { it.contentDescription },
                )
                assertTrue(
                    nodes
                        .first { it.nodeId == FlameCallNodeId(2) }
                        .stateDescription
                        .contains("inclusive weight 6"),
                )
                assertTrue(nodes.first { it.nodeId == FlameCallNodeId(2) }.stateDescription.contains("1 sample"))
                assertTrue(nodes.first { it.nodeId == FlameCallNodeId(3) }.selected)
            }
        }
    }

    @Test
    fun `semantic descriptions expose selected hover and context states without color`() {
        runDesktopComposeUiTest {
            setContent {
                val snapshot = accessibilitySnapshot()
                val layout =
                    FlameGraphLayout.layout(
                        snapshot,
                        FlameViewport(widthPx = 1_000, heightPx = 48, scrollRow = 0),
                    )

                val nodes =
                    FlameGraphSemanticsPresenter.nodes(
                        snapshot = snapshot,
                        layout = layout,
                        selectedNodeId = FlameCallNodeId(1),
                        hoveredNodeId = FlameCallNodeId(2),
                        contextNodeId = FlameCallNodeId(3),
                    )

                assertTrue(nodes.first { it.nodeId == FlameCallNodeId(1) }.stateDescription.contains("selected"))
                assertTrue(nodes.first { it.nodeId == FlameCallNodeId(2) }.stateDescription.contains("hovered"))
                assertTrue(
                    nodes
                        .first { it.nodeId == FlameCallNodeId(3) }
                        .stateDescription
                        .contains("context menu open"),
                )
            }
        }
    }
}

internal fun accessibilitySnapshot(): FlameGraphSnapshot {
    val frames =
        mapOf(
            10L to CallStackFrame(10, FlameFunctionId(10), "root", "libroot.so", 0, FrameImplementation.NATIVE),
            20L to CallStackFrame(20, FlameFunctionId(20), "renderFrame", "libui.so", 8, FrameImplementation.NATIVE),
            30L to CallStackFrame(30, FlameFunctionId(30), "drawFrame", "Main.kt", 16, FrameImplementation.MANAGED),
        )
    return FlameGraphSnapshot(
        query = CallStackAnalysisQuery(direction = CallStackDirection.FORWARD),
        callNodes =
            CallNodeTable(
                ids = longArrayOf(1, 2, 3),
                parentIndexes = intArrayOf(-1, 0, 1),
                frameIds = longArrayOf(10, 20, 30),
                depths = intArrayOf(0, 1, 2),
                inclusiveWeights = longArrayOf(10, 6, 2),
                selfWeights = longArrayOf(4, 4, 2),
                sampleCounts = longArrayOf(2, 1, 1),
                threadCounts = intArrayOf(1, 1, 1),
                categories = listOf("Native", "Native", "Managed"),
                framesById = frames,
            ),
        rows =
            FlameGraphRows(
                nodeIndexesByRow = listOf(intArrayOf(0), intArrayOf(1), intArrayOf(2)),
                starts = doubleArrayOf(0.0, 0.0, 0.0),
                ends = doubleArrayOf(1.0, 0.6, 0.2),
                startsAtBottom = true,
            ),
        totalWeight = 10,
        emptyReason = null,
        invalidTransforms = emptyList(),
    )
}
