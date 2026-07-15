package com.androidperformancestudio.profileanalysis

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlameGraphProjectionPropertyTest {
    @Test
    fun `random projected rows preserve normalized containment and non-overlap invariants`() {
        repeat(100) { seed ->
            val table = randomTable(seed)
            CallStackDirection.entries.forEach { direction ->
                val nodes = CallTreeProjector.project(table, direction)
                val rows = FlameGraphRowProjector.project(nodes, direction)
                assertProjectionInvariants(nodes, rows, seed, direction)
            }
        }
    }

    @Test
    fun `equivalent sample permutations have byte-for-byte deterministic node projections`() {
        repeat(50) { seed ->
            val table = randomTable(seed)
            val shuffled = table.copy(stacks = table.stacks.shuffled(Random(seed + 10_000)))

            val first = CallTreeProjector.project(table)
            val second = CallTreeProjector.project(shuffled)

            assertTrue(first.ids.contentEquals(second.ids), "ids seed=$seed")
            assertTrue(first.parentIndexes.contentEquals(second.parentIndexes), "parents seed=$seed")
            assertTrue(first.frameIds.contentEquals(second.frameIds), "frames seed=$seed")
            assertTrue(first.depths.contentEquals(second.depths), "depths seed=$seed")
            assertTrue(first.inclusiveWeights.contentEquals(second.inclusiveWeights), "weights seed=$seed")
            assertTrue(first.categories == second.categories, "categories seed=$seed")
        }
    }

    private fun assertProjectionInvariants(
        nodes: CallNodeTable,
        rows: FlameGraphRows,
        seed: Int,
        direction: CallStackDirection,
    ) {
        val starts = rows.starts
        val ends = rows.ends
        val parents = nodes.parentIndexes
        val rowNodes = rows.nodeIndexesByRow.flatMap(IntArray::asList)
        rowNodes.forEach { node ->
            assertTrue(starts[node].isFinite(), "finite start seed=$seed direction=$direction node=$node")
            assertTrue(ends[node].isFinite(), "finite end seed=$seed direction=$direction node=$node")
            assertTrue(starts[node] >= 0.0, "start seed=$seed direction=$direction node=$node")
            assertTrue(starts[node] < ends[node], "width seed=$seed direction=$direction node=$node")
            assertTrue(ends[node] <= 1.0, "end seed=$seed direction=$direction node=$node")
            val parent = parents[node]
            if (parent >= 0) {
                assertTrue(starts[node] >= starts[parent], "parent start seed=$seed direction=$direction node=$node")
                assertTrue(ends[node] <= ends[parent], "parent end seed=$seed direction=$direction node=$node")
            }
        }
        rows.nodeIndexesByRow.forEach { row ->
            row.asList().zipWithNext().forEach { (left, right) ->
                assertTrue(ends[left] <= starts[right], "overlap seed=$seed direction=$direction")
            }
        }
        val roots =
            rows.nodeIndexesByRow
                .firstOrNull()
                .orEmpty()
                .filter { parents[it] == -1 }
        val rootWeight = parents.indices.filter { parents[it] == -1 }.sumOf { nodes.inclusiveWeights[it] }
        if (rootWeight > 0) {
            assertTrue(
                roots.isNotEmpty(),
                "positive root weight requires visible roots seed=$seed direction=$direction",
            )
            assertEquals(0.0, starts[roots.first()], absoluteTolerance = 1e-12)
            assertEquals(1.0, ends[roots.last()], absoluteTolerance = 1e-12)
            val width = roots.sumOf { ends[it] - starts[it] }
            assertEquals(1.0, width, absoluteTolerance = 1e-12)
        }
    }

    private fun randomTable(seed: Int): CallStackTable {
        val random = Random(seed)
        val frames =
            (1L..12L).associateWith { id ->
                CallStackFrame(
                    frameId = id,
                    functionId = FlameFunctionId(id),
                    symbolName = "symbol-${('a'.code + (12 - id).toInt()).toChar()}",
                    resource = "resource-${id % 3}",
                    virtualAddress = id,
                    implementation = FrameImplementation.NATIVE,
                )
            }
        val stacks =
            List(random.nextInt(1, 40)) { index ->
                val depth = random.nextInt(1, 9)
                val frameIds = List(depth) { random.nextLong(1, 13) }
                val categories = List(depth) { listOf<String?>("UI", "IO", "Graphics", null).random(random) }
                WeightedCallStack(
                    sampleId = index.toLong(),
                    timestampNanos = random.nextLong(),
                    weight = random.nextLong(0, 100),
                    threadKey = "thread-${random.nextInt(4)}",
                    category = "fallback",
                    subcategory = null,
                    frameIdsRootToLeaf = frameIds,
                    categoriesRootToLeaf = categories,
                )
            }
        return CallStackTable(frames, stacks)
    }

    private fun IntArray?.orEmpty(): IntArray = this ?: IntArray(0)
}
