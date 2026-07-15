package com.androidperformancestudio.profileanalysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FlameGraphNavigatorTest {
    @Test
    fun `parent and widest child use relationships independent of flame orientation`() {
        val forward = navigationSnapshot(startsAtBottom = true)
        val inverted = navigationSnapshot(startsAtBottom = false)

        listOf(forward, inverted).forEach { snapshot ->
            assertEquals(id(1), FlameGraphNavigator.parent(snapshot, id(4)))
            assertEquals(id(4), FlameGraphNavigator.widestChild(snapshot, id(1)))
            assertNull(FlameGraphNavigator.parent(snapshot, id(1)))
            assertNull(FlameGraphNavigator.widestChild(snapshot, id(3)))
        }
    }

    @Test
    fun `widest child skips ineligible widths and resolves equal widths in table order`() {
        val snapshot = navigationSnapshot()
        val equalWidthSnapshot = navigationSnapshot(equalChildWidths = true)

        assertEquals(id(4), FlameGraphNavigator.widestChild(snapshot, id(1), minimumNormalizedWidth = 0.001))
        assertEquals(id(4), FlameGraphNavigator.widestChild(snapshot, id(1), minimumNormalizedWidth = 0.3))
        assertNull(FlameGraphNavigator.widestChild(snapshot, id(1), minimumNormalizedWidth = 0.301))
        assertEquals(id(2), FlameGraphNavigator.widestChild(equalWidthSnapshot, id(1)))
    }

    @Test
    fun `siblings follow stable alphabetical table order and skip narrow nodes`() {
        val snapshot = navigationSnapshot()

        assertEquals(id(2), FlameGraphNavigator.previousSibling(snapshot, id(4)))
        assertEquals(id(4), FlameGraphNavigator.nextSibling(snapshot, id(2)))
        assertNull(FlameGraphNavigator.previousSibling(snapshot, id(2)))
        assertNull(FlameGraphNavigator.nextSibling(snapshot, id(4)))
        assertEquals(id(3), FlameGraphNavigator.nextSibling(snapshot, id(4), minimumNormalizedWidth = 0.0004))
        assertEquals(id(6), FlameGraphNavigator.nextSibling(snapshot, id(5)))
        assertEquals(id(5), FlameGraphNavigator.previousSibling(snapshot, id(6)))
    }

    @Test
    fun `root and missing node boundaries return no navigation target`() {
        val snapshot = navigationSnapshot()

        assertNull(FlameGraphNavigator.parent(snapshot, id(404)))
        assertNull(FlameGraphNavigator.widestChild(snapshot, id(404)))
        assertNull(FlameGraphNavigator.previousSibling(snapshot, id(404)))
        assertNull(FlameGraphNavigator.nextSibling(snapshot, id(404)))
        assertEquals(id(1), FlameGraphNavigator.previousSibling(snapshot, id(5)))
        assertNull(FlameGraphNavigator.nextSibling(snapshot, id(6)))
    }

    @Test
    fun `invalid navigation thresholds fail explicitly`() {
        val snapshot = navigationSnapshot()

        listOf(-0.001, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { FlameGraphNavigator.parent(snapshot, id(2), invalid) }
            assertFailsWith<IllegalArgumentException> { FlameGraphNavigator.widestChild(snapshot, id(1), invalid) }
            assertFailsWith<IllegalArgumentException> { FlameGraphNavigator.previousSibling(snapshot, id(2), invalid) }
            assertFailsWith<IllegalArgumentException> { FlameGraphNavigator.nextSibling(snapshot, id(2), invalid) }
        }
    }

    private fun navigationSnapshot(
        startsAtBottom: Boolean = true,
        equalChildWidths: Boolean = false,
    ): FlameGraphSnapshot {
        // Table order is root alpha, children alpha/beta/narrow, root omega, root sigma.
        val parents = intArrayOf(-1, 0, 0, 0, -1, -1)
        val ids = longArrayOf(1, 2, 4, 3, 5, 6)
        val frames = ids.indices.associate { index -> index.toLong() to frame(index.toLong()) }
        val nodes =
            CallNodeTable(
                ids = ids,
                parentIndexes = parents,
                frameIds = LongArray(ids.size) { it.toLong() },
                depths = intArrayOf(0, 1, 1, 1, 0, 0),
                inclusiveWeights = longArrayOf(1_000, 300, 300, 0, 1_000, 1_000),
                selfWeights = LongArray(ids.size),
                sampleCounts = LongArray(ids.size),
                threadCounts = IntArray(ids.size),
                categories = List(ids.size) { null },
                framesById = frames,
            )
        return FlameGraphSnapshot(
            query = CallStackAnalysisQuery(),
            callNodes = nodes,
            rows =
                FlameGraphRows(
                    nodeIndexesByRow = listOf(intArrayOf(0, 4, 5), intArrayOf(1, 2, 3)),
                    starts = doubleArrayOf(0.0, 0.0, 0.25, 0.55, 0.4, 0.7),
                    ends =
                        if (equalChildWidths) {
                            doubleArrayOf(0.4, 0.25, 0.5, 0.5505, 0.7, 1.0)
                        } else {
                            doubleArrayOf(0.4, 0.25, 0.55, 0.5505, 0.7, 1.0)
                        },
                    startsAtBottom = startsAtBottom,
                ),
            totalWeight = 3_000,
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

    private fun id(value: Long) = FlameCallNodeId(value)
}
