package com.androidperformancestudio.profileanalysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class StackChartSnapshotTest {
    @Test
    fun `category defaults to null after the existing positional call node argument`() {
        val nodeId = FlameCallNodeId(7)
        val block = StackChartBlock(StackChartBlockId("legacy"), 1, 10, 20, 0, 1, "main", 1, nodeId)

        assertEquals(nodeId, block.callNodeId)
        assertNull(block.category)
    }

    @Test
    fun `snapshot indexes defensively copy collections and preserve block order`() {
        val nodeId = FlameCallNodeId(7)
        val first = block("first", nodeId)
        val second = block("second", nodeId)
        val legacy = block("legacy", null)
        val blocks = mutableListOf(first, legacy, second)
        val parents = mutableMapOf<FlameCallNodeId, FlameCallNodeId?>(nodeId to null)
        val snapshot = StackChartSnapshot(emptyMap(), blocks, 10, 20, 0, null, parents)

        blocks.clear()
        parents.clear()

        assertEquals(listOf(first, legacy, second), snapshot.blocksById.values.toList())
        assertSame(second, snapshot.blockById(second.id))
        assertNull(snapshot.blockById(StackChartBlockId("missing")))
        assertEquals(listOf(first, second), snapshot.blocksByCallNodeId.getValue(nodeId))
        assertEquals(mapOf(nodeId to null), snapshot.parentCallNodeIds)
        assertFailsWith<UnsupportedOperationException> { (snapshot.blocksById as MutableMap<*, *>).clear() }
        assertFailsWith<UnsupportedOperationException> { (snapshot.blocksByCallNodeId as MutableMap<*, *>).clear() }
        assertFailsWith<UnsupportedOperationException> {
            (snapshot.blocksByCallNodeId.getValue(nodeId) as MutableList<*>).clear()
        }
        assertFailsWith<UnsupportedOperationException> { (snapshot.parentCallNodeIds as MutableMap<*, *>).clear() }
    }

    @Test
    fun `copy rebuilds indexes without changing the original snapshot`() {
        val first = block("first", FlameCallNodeId(7))
        val second = block("second", FlameCallNodeId(8))
        val snapshot = StackChartSnapshot(emptyMap(), listOf(first), 10, 20, 0, null)

        val copied = snapshot.copy(blocks = listOf(second))

        assertEquals(mapOf(first.id to first), snapshot.blocksById)
        assertEquals(mapOf(second.id to second), copied.blocksById)
        assertEquals(setOf(FlameCallNodeId(8)), copied.blocksByCallNodeId.keys)
        assertEquals(listOf(second), copied.blocksByCallNodeId.getValue(FlameCallNodeId(8)))
    }

    @Test
    fun `legacy duplicate block ids retain last occurrence lookup semantics`() {
        val first = block("duplicate", null)
        val last = first.copy(weight = 2)
        val snapshot = StackChartSnapshot(emptyMap(), listOf(first, last), 10, 20, 0, null)

        assertSame(last, snapshot.blockById(first.id))
        assertEquals(emptyMap(), snapshot.blocksByCallNodeId)
    }

    private fun block(
        id: String,
        nodeId: FlameCallNodeId?,
    ): StackChartBlock = StackChartBlock(StackChartBlockId(id), 1, 10, 20, 0, 1, "main", 1, callNodeId = nodeId)
}
