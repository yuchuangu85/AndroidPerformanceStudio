package com.androidperformancestudio.profileanalysis

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlameGraphRowProjectorTest {
    @Test
    fun `roots partition width and children remain ordered inside their parent`() {
        val nodes =
            nodeTable(
                parents = intArrayOf(-1, 0, 0, -1, 3),
                depths = intArrayOf(0, 1, 1, 0, 1),
                weights = longArrayOf(6, 2, 3, 4, 4),
            )

        val rows = FlameGraphRowProjector.project(nodes, CallStackDirection.FORWARD)

        assertTrue(rows.startsAtBottom)
        assertContentEquals(intArrayOf(0, 3), rows.nodeIndexesByRow[0])
        assertContentEquals(intArrayOf(1, 2, 4), rows.nodeIndexesByRow[1])
        assertInterval(rows, 0, 0.0, 0.6)
        assertInterval(rows, 1, 0.0, 0.2)
        assertInterval(rows, 2, 0.2, 0.5)
        assertInterval(rows, 3, 0.6, 1.0)
        assertInterval(rows, 4, 0.6, 1.0)
    }

    @Test
    fun `inverted rows only change vertical origin and never mirror intervals`() {
        val nodes = nodeTable(intArrayOf(-1, -1), intArrayOf(0, 0), longArrayOf(1, 3))

        val forward = FlameGraphRowProjector.project(nodes, CallStackDirection.FORWARD)
        val inverted = FlameGraphRowProjector.project(nodes, CallStackDirection.INVERTED)

        assertTrue(forward.startsAtBottom)
        assertEquals(false, inverted.startsAtBottom)
        assertContentEquals(forward.starts, inverted.starts)
        assertContentEquals(forward.ends, inverted.ends)
        assertContentEquals(forward.nodeIndexesByRow.single(), inverted.nodeIndexesByRow.single())
    }

    @Test
    fun `zero weight nodes remain in the call table but do not create invalid row intervals`() {
        val nodes = nodeTable(intArrayOf(-1, 0, -1), intArrayOf(0, 1, 0), longArrayOf(0, 0, 5))

        val rows = FlameGraphRowProjector.project(nodes, CallStackDirection.FORWARD)

        assertContentEquals(intArrayOf(2), rows.nodeIndexesByRow.single())
        assertInterval(rows, 2, 0.0, 1.0)
        assertEquals(0.0, rows.starts[0])
        assertEquals(0.0, rows.ends[0])
        assertEquals(0.0, rows.starts[1])
        assertEquals(0.0, rows.ends[1])
    }

    @Test
    fun `empty and all-zero tables produce compact empty rows`() {
        val empty = nodeTable(intArrayOf(), intArrayOf(), longArrayOf())
        val zeros = nodeTable(intArrayOf(-1, 0), intArrayOf(0, 1), longArrayOf(0, 0))

        assertTrue(FlameGraphRowProjector.project(empty).nodeIndexesByRow.isEmpty())
        assertTrue(FlameGraphRowProjector.project(zeros).nodeIndexesByRow.isEmpty())
    }

    @Test
    fun `extreme weights produce finite valid intervals without overflowing totals`() {
        val nodes =
            nodeTable(
                intArrayOf(-1, -1, -1),
                intArrayOf(0, 0, 0),
                longArrayOf(Long.MAX_VALUE, 1, Long.MAX_VALUE),
            )

        val rows = FlameGraphRowProjector.project(nodes)

        rows.nodeIndexesByRow.flatten().forEach { node ->
            assertTrue(rows.starts[node].isFinite())
            assertTrue(rows.ends[node].isFinite())
            assertTrue(rows.starts[node] >= 0.0)
            assertTrue(rows.ends[node] <= 1.0)
            assertTrue(rows.starts[node] < rows.ends[node])
        }
        assertEquals(0.0, rows.starts[0])
        assertEquals(1.0, rows.ends[2])
    }

    @Test
    fun `deep projections use iterative traversal`() {
        val size = 10_000
        val nodes =
            nodeTable(
                parents = IntArray(size) { index -> index - 1 },
                depths = IntArray(size) { it },
                weights = LongArray(size) { 1 },
            )

        val rows = FlameGraphRowProjector.project(nodes)

        assertEquals(size, rows.nodeIndexesByRow.size)
        assertContentEquals(intArrayOf(size - 1), rows.nodeIndexesByRow.last())
        assertInterval(rows, size - 1, 0.0, 1.0)
    }

    private fun nodeTable(
        parents: IntArray,
        depths: IntArray,
        weights: LongArray,
    ): CallNodeTable {
        val size = weights.size
        val frames = (0 until size).associate { index -> index.toLong() to frame(index.toLong()) }
        return CallNodeTable(
            ids = LongArray(size) { it.toLong() },
            parentIndexes = parents,
            frameIds = LongArray(size) { it.toLong() },
            depths = depths,
            inclusiveWeights = weights,
            selfWeights = LongArray(size),
            sampleCounts = LongArray(size),
            threadCounts = IntArray(size),
            categories = List(size) { null },
            framesById = frames,
        )
    }

    private fun frame(id: Long) =
        CallStackFrame(
            id,
            FlameFunctionId(id),
            "frame-$id",
            "app.so",
            id,
            FrameImplementation.NATIVE,
        )

    private fun assertInterval(
        rows: FlameGraphRows,
        node: Int,
        expectedStart: Double,
        expectedEnd: Double,
    ) {
        assertEquals(expectedStart, rows.starts[node], absoluteTolerance = 1e-12)
        assertEquals(expectedEnd, rows.ends[node], absoluteTolerance = 1e-12)
    }

    private fun List<IntArray>.flatten(): List<Int> = flatMap(IntArray::asList)
}
