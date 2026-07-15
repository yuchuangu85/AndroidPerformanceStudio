package com.androidperformancestudio.visualization

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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FlameGraphLayoutTest {
    @Test
    fun `materializes only visible rows and clamped overscan`() {
        val snapshot = rowSnapshot(rowCount = 40)

        val layout =
            FlameGraphLayout.layout(
                snapshot,
                FlameViewport(widthPx = 800, heightPx = 160, scrollRow = 20),
            )

        assertEquals(19..30, layout.materializedRowRange)
        assertEquals((19..30).toList(), layout.nodes.map(VisibleFlameNode::nodeIndex))
    }

    @Test
    fun `forward rows anchor to bottom and inverted rows anchor to top`() {
        val forward =
            FlameGraphLayout.layout(
                rowSnapshot(rowCount = 3, startsAtBottom = true),
                FlameViewport(widthPx = 100, heightPx = 50, scrollRow = 0, rowHeightPx = 10f),
            )
        val inverted =
            FlameGraphLayout.layout(
                rowSnapshot(rowCount = 3, startsAtBottom = false),
                FlameViewport(widthPx = 100, heightPx = 50, scrollRow = 0, rowHeightPx = 10f),
            )

        assertEquals(listOf(40f, 30f, 20f), forward.nodes.map(VisibleFlameNode::y))
        assertEquals(listOf(0f, 10f, 20f), inverted.nodes.map(VisibleFlameNode::y))
    }

    @Test
    fun `uses full width normalized edges and skips subpixel nodes only from drawing`() {
        val snapshot =
            snapshot(
                rows = listOf(intArrayOf(0, 1, 2)),
                starts = doubleArrayOf(0.0, 0.104, 0.2),
                ends = doubleArrayOf(0.104, 0.109, 0.8),
                ids = longArrayOf(10, 20, 30),
            )

        val layout =
            FlameGraphLayout.layout(
                snapshot,
                FlameViewport(widthPx = 100, heightPx = 16, scrollRow = 0),
            )

        assertEquals(listOf(0, 2), layout.nodes.map(VisibleFlameNode::nodeIndex))
        assertEquals(0f, layout.nodes[0].x)
        assertEquals(10f, layout.nodes[0].width)
        assertEquals(20f, layout.nodes[1].x)
        assertEquals(60f, layout.nodes[1].width)
        assertEquals(0..0, layout.materializedRowRange)
        assertEquals(3, snapshot.callNodes.size)
    }

    @Test
    fun `hit testing uses half open bounds and last painted node`() {
        val bottom = VisibleFlameNode(0, FlameCallNodeId(10), 0f, 0f, 20f, 20f)
        val top = VisibleFlameNode(1, FlameCallNodeId(20), 10f, 10f, 20f, 20f)
        val layout = VisibleFlameLayout(listOf(bottom, top), 0..1)

        assertSame(top, FlameGraphLayout.hitTest(layout, x = 10f, y = 10f))
        assertSame(bottom, FlameGraphLayout.hitTest(layout, x = 9.999f, y = 10f))
        assertNull(FlameGraphLayout.hitTest(layout, x = 30f, y = 20f))
        assertNull(FlameGraphLayout.hitTest(layout, x = Float.NaN, y = 0f))
    }

    @Test
    fun `scroll and viewport edge cases are deterministic`() {
        val snapshot = rowSnapshot(rowCount = 5)

        val negativeScroll =
            FlameGraphLayout.layout(
                snapshot,
                FlameViewport(widthPx = 100, heightPx = 17, scrollRow = Int.MIN_VALUE, rowHeightPx = 8.5f),
            )
        val oversizedScroll =
            FlameGraphLayout.layout(
                snapshot,
                FlameViewport(widthPx = 100, heightPx = 17, scrollRow = Int.MAX_VALUE, rowHeightPx = 8.5f),
            )
        val zeroHeight =
            FlameGraphLayout.layout(
                snapshot,
                FlameViewport(widthPx = 100, heightPx = 0, scrollRow = 0),
            )
        val zeroWidth =
            FlameGraphLayout.layout(
                snapshot,
                FlameViewport(widthPx = 0, heightPx = 32, scrollRow = 0),
            )

        assertEquals(0..2, negativeScroll.materializedRowRange)
        assertEquals(2..4, oversizedScroll.materializedRowRange)
        assertEquals(IntRange.EMPTY, zeroHeight.materializedRowRange)
        assertTrue(zeroHeight.nodes.isEmpty())
        assertEquals(0..2, zeroWidth.materializedRowRange)
        assertTrue(zeroWidth.nodes.isEmpty())
    }

    @Test
    fun `empty and defensive snapshots remain safe and visible nodes preserve exact identity`() {
        val empty = snapshot(emptyList(), doubleArrayOf(), doubleArrayOf(), longArrayOf())
        val mutableRows = mutableListOf(intArrayOf(0))
        val rows = FlameGraphRows(mutableRows, doubleArrayOf(0.0), doubleArrayOf(1.0), startsAtBottom = true)
        mutableRows[0][0] = 99
        val source = snapshot(rows, longArrayOf(991))

        val emptyLayout =
            FlameGraphLayout.layout(empty, FlameViewport(widthPx = -1, heightPx = -1, scrollRow = 0))
        val layout = FlameGraphLayout.layout(source, FlameViewport(widthPx = 10, heightPx = 10, scrollRow = 0))

        assertEquals(IntRange.EMPTY, emptyLayout.materializedRowRange)
        assertTrue(emptyLayout.nodes.isEmpty())
        assertEquals(0, layout.nodes.single().nodeIndex)
        assertEquals(FlameCallNodeId(991), layout.nodes.single().nodeId)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (layout.nodes as MutableList<VisibleFlameNode>).clear()
        }
    }

    @Test
    fun `invalid row dimensions fail explicitly`() {
        assertFailsWith<IllegalArgumentException> { FlameViewport(1, 1, 0, rowHeightPx = 0f) }
        assertFailsWith<IllegalArgumentException> { FlameViewport(1, 1, 0, rowHeightPx = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { FlameViewport(1, 1, 0, overscanRows = -1) }
    }

    @Test
    fun `extreme finite row heights never create non finite rectangles`() {
        val layout =
            FlameGraphLayout.layout(
                rowSnapshot(rowCount = 2),
                FlameViewport(
                    widthPx = Int.MAX_VALUE,
                    heightPx = Int.MAX_VALUE,
                    scrollRow = Int.MIN_VALUE,
                    rowHeightPx = Float.MAX_VALUE,
                ),
            )

        assertTrue(layout.nodes.all { node -> node.x.isFinite() && node.y.isFinite() && node.width.isFinite() })
    }

    @Test
    fun `layout is bounded by materialized rows for deep compact tables`() {
        val snapshot = rowSnapshot(rowCount = 100_000)

        val layout =
            FlameGraphLayout.layout(
                snapshot,
                FlameViewport(widthPx = 100, heightPx = 32, scrollRow = 50_000),
            )

        assertEquals(49_999..50_002, layout.materializedRowRange)
        assertEquals(4, layout.nodes.size)
        assertFalse(layout.nodes.any { it.width < 1f })
    }

    @Test
    fun `layout never reads offscreen rows or nodes`() {
        val source = CountingLayoutSource(rowCount = 100_000)

        val layout =
            FlameGraphLayout.layout(
                source,
                FlameViewport(widthPx = 100, heightPx = 32, scrollRow = 50_000),
            )

        assertEquals(49_999..50_002, layout.materializedRowRange)
        assertEquals((49_999..50_002).toList(), source.rowsRead)
        assertEquals((49_999..50_002).toList(), source.startsRead)
        assertEquals((49_999..50_002).toList(), source.endsRead)
        assertEquals((49_999..50_002).toList(), source.idsRead)
    }

    private fun rowSnapshot(
        rowCount: Int,
        startsAtBottom: Boolean = true,
    ): FlameGraphSnapshot {
        val rows = List(rowCount) { row -> intArrayOf(row) }
        return snapshot(
            FlameGraphRows(
                rows,
                DoubleArray(rowCount),
                DoubleArray(rowCount) { 1.0 },
                startsAtBottom,
            ),
            LongArray(rowCount) { index -> 1_000L + index },
        )
    }

    private fun snapshot(
        rows: List<IntArray>,
        starts: DoubleArray,
        ends: DoubleArray,
        ids: LongArray,
    ): FlameGraphSnapshot = snapshot(FlameGraphRows(rows, starts, ends, startsAtBottom = true), ids)

    private fun snapshot(
        rows: FlameGraphRows,
        ids: LongArray,
    ): FlameGraphSnapshot {
        val frames =
            ids.indices.associate { index ->
                index.toLong() to
                    CallStackFrame(
                        frameId = index.toLong(),
                        functionId = FlameFunctionId(index.toLong()),
                        symbolName = "node-$index",
                        resource = "lib.so",
                        virtualAddress = index.toLong(),
                        implementation = FrameImplementation.NATIVE,
                    )
            }
        return FlameGraphSnapshot(
            query = CallStackAnalysisQuery(),
            callNodes =
                CallNodeTable(
                    ids = ids,
                    parentIndexes = IntArray(ids.size) { -1 },
                    frameIds = LongArray(ids.size) { it.toLong() },
                    depths = IntArray(ids.size) { it },
                    inclusiveWeights = LongArray(ids.size) { 1 },
                    selfWeights = LongArray(ids.size) { 1 },
                    sampleCounts = LongArray(ids.size) { 1 },
                    threadCounts = IntArray(ids.size) { 1 },
                    categories = List(ids.size) { "native" },
                    framesById = frames,
                ),
            rows = rows,
            totalWeight = ids.size.toLong(),
            emptyReason = null,
            invalidTransforms = emptyList(),
        )
    }

    private class CountingLayoutSource(
        override val rowCount: Int,
    ) : FlameGraphLayoutSource {
        override val startsAtBottom: Boolean = true
        val rowsRead = mutableListOf<Int>()
        val startsRead = mutableListOf<Int>()
        val endsRead = mutableListOf<Int>()
        val idsRead = mutableListOf<Int>()

        override fun nodeIndexesAt(rowIndex: Int): IntArray {
            rowsRead += rowIndex
            return intArrayOf(rowIndex)
        }

        override fun normalizedStartAt(nodeIndex: Int): Double {
            startsRead += nodeIndex
            return 0.0
        }

        override fun normalizedEndAt(nodeIndex: Int): Double {
            endsRead += nodeIndex
            return 1.0
        }

        override fun nodeIdAt(nodeIndex: Int): FlameCallNodeId {
            idsRead += nodeIndex
            return FlameCallNodeId(nodeIndex.toLong())
        }
    }
}
