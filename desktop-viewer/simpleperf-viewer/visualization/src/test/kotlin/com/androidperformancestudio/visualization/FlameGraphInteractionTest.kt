package com.androidperformancestudio.visualization

import androidx.compose.ui.geometry.Offset
import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FlameGraphRows
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.profileanalysis.FrameImplementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FlameGraphInteractionTest {
    private val back = VisibleFlameNode(0, FlameCallNodeId(10), 0f, 0f, 20f, 20f)
    private val front = VisibleFlameNode(1, FlameCallNodeId(20), 10f, 10f, 20f, 20f)
    private val layout = VisibleFlameLayout(listOf(back, front), 0..1)

    @Test
    fun `hover enter change and exit emit exact node identities`() {
        assertEquals(FlameGraphIntent.Hover(back.nodeId), FlameGraphInteraction.hover(layout, Offset(5f, 5f)))
        assertEquals(FlameGraphIntent.Hover(front.nodeId), FlameGraphInteraction.hover(layout, Offset(15f, 15f)))
        assertEquals(FlameGraphIntent.Hover(null), FlameGraphInteraction.hover(layout, Offset(40f, 40f)))
        assertEquals(FlameGraphIntent.Hover(null), FlameGraphInteraction.hoverExit())
    }

    @Test
    fun `single click selects topmost node or clears selection on blank canvas`() {
        assertEquals(FlameGraphIntent.Select(front.nodeId), FlameGraphInteraction.select(layout, Offset(15f, 15f)))
        assertEquals(FlameGraphIntent.Select(null), FlameGraphInteraction.select(layout, Offset(40f, 40f)))
    }

    @Test
    fun `secondary click emits only local node context intent`() {
        val position = Offset(15f, 15f)

        assertEquals(
            FlameGraphIntent.OpenContextMenu(front.nodeId, position),
            FlameGraphInteraction.openContextMenu(layout, position),
        )
        assertNull(FlameGraphInteraction.openContextMenu(layout, Offset(40f, 40f)))
    }

    @Test
    fun `double click emits only node details and does nothing on blank canvas`() {
        assertEquals(
            FlameGraphIntent.OpenDetails(front.nodeId),
            FlameGraphInteraction.openDetails(layout, Offset(15f, 15f)),
        )
        assertNull(FlameGraphInteraction.openDetails(layout, Offset(40f, 40f)))
    }

    @Test
    fun `selection scrolls its structural row into a clamped viewport`() {
        val snapshot = deepSnapshot(rowCount = 20)
        val viewport = FlameViewport(widthPx = 100, heightPx = 48, scrollRow = 3, rowHeightPx = 16f)

        assertEquals(3, FlameGraphLayout.scrollRowToReveal(snapshot, FlameCallNodeId(4), viewport))
        assertEquals(8, FlameGraphLayout.scrollRowToReveal(snapshot, FlameCallNodeId(10), viewport))
        assertEquals(1, FlameGraphLayout.scrollRowToReveal(snapshot, FlameCallNodeId(1), viewport))
        assertEquals(
            8,
            FlameGraphLayout.scrollRowToReveal(
                deepSnapshot(rowCount = 20, startsAtBottom = false),
                FlameCallNodeId(10),
                viewport,
            ),
        )
        assertEquals(
            17,
            FlameGraphLayout.scrollRowToReveal(snapshot, FlameCallNodeId(19), viewport.copy(scrollRow = 99)),
        )
        assertEquals(3, FlameGraphLayout.scrollRowToReveal(snapshot, FlameCallNodeId(404), viewport))
    }

    @Test
    fun `selection scrolling validates row height and handles empty viewport`() {
        val snapshot = deepSnapshot(rowCount = 2)

        assertEquals(
            0,
            FlameGraphLayout.scrollRowToReveal(
                snapshot,
                FlameCallNodeId(1),
                FlameViewport(widthPx = 100, heightPx = 0, scrollRow = 99),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            FlameViewport(widthPx = 100, heightPx = 10, scrollRow = 0, rowHeightPx = Float.NaN)
        }
    }

    private fun deepSnapshot(
        rowCount: Int,
        startsAtBottom: Boolean = true,
    ): FlameGraphSnapshot {
        val ids = LongArray(rowCount) { it.toLong() }
        val frames = ids.associateWith { id -> frame(id) }
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
                    sampleCounts = LongArray(rowCount),
                    threadCounts = IntArray(rowCount),
                    categories = List(rowCount) { null },
                    framesById = frames,
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

    private fun frame(id: Long) =
        CallStackFrame(
            frameId = id,
            functionId = FlameFunctionId(id),
            symbolName = "frame-$id",
            resource = "app.so",
            virtualAddress = id,
            implementation = FrameImplementation.NATIVE,
        )
}
