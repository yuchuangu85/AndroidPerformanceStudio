package com.androidperformancestudio.profileanalysis

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CallStackContractsTest {
    @Test
    fun `search parser trims blanks and preserves Firefox comma order`() {
        assertEquals(listOf("render", "libc"), parseFlameSearchTerms(" render, ,libc "))
    }

    @Test
    fun `call node path uses structural equality`() {
        assertEquals(
            CallNodePath(listOf(FlameFunctionId(1), FlameFunctionId(2))),
            CallNodePath(listOf(FlameFunctionId(1), FlameFunctionId(2))),
        )
    }

    @Test
    fun `call node path snapshots its source before map lookup`() {
        val source = mutableListOf(FlameFunctionId(1), FlameFunctionId(2))
        val path = CallNodePath(source)
        val idsByPath = mutableMapOf(path to FlameCallNodeId(7))
        val table = callNodeTable(idsByPath = idsByPath)

        source.clear()
        idsByPath.clear()

        assertEquals(listOf(FlameFunctionId(1), FlameFunctionId(2)), path.functions)
        assertEquals(FlameCallNodeId(7), table.findByPath(CallNodePath(listOf(FlameFunctionId(1), FlameFunctionId(2)))))
    }

    @Test
    fun `call node table snapshots constructor inputs and returned arrays`() {
        val ids = longArrayOf(7)
        val parentIndexes = intArrayOf(-1)
        val frameIds = longArrayOf(11)
        val depths = intArrayOf(0)
        val inclusiveWeights = longArrayOf(5)
        val selfWeights = longArrayOf(3)
        val sampleCounts = longArrayOf(2)
        val threadCounts = intArrayOf(1)
        val categories = mutableListOf<String?>("UI")
        val frames = mutableMapOf(11L to frame(11))
        val table =
            CallNodeTable(
                ids = ids,
                parentIndexes = parentIndexes,
                frameIds = frameIds,
                depths = depths,
                inclusiveWeights = inclusiveWeights,
                selfWeights = selfWeights,
                sampleCounts = sampleCounts,
                threadCounts = threadCounts,
                categories = categories,
                framesById = frames,
            )

        ids[0] = 99
        parentIndexes[0] = 99
        frameIds[0] = 99
        depths[0] = 99
        inclusiveWeights[0] = 99
        selfWeights[0] = 99
        sampleCounts[0] = 99
        threadCounts[0] = 99
        categories[0] = "mutated"
        frames.clear()
        table.ids[0] = 100
        table.parentIndexes[0] = 100
        table.frameIds[0] = 100
        table.depths[0] = 100
        table.inclusiveWeights[0] = 100
        table.selfWeights[0] = 100
        table.sampleCounts[0] = 100
        table.threadCounts[0] = 100

        assertContentEquals(longArrayOf(7), table.ids)
        assertContentEquals(intArrayOf(-1), table.parentIndexes)
        assertContentEquals(longArrayOf(11), table.frameIds)
        assertContentEquals(intArrayOf(0), table.depths)
        assertContentEquals(longArrayOf(5), table.inclusiveWeights)
        assertContentEquals(longArrayOf(3), table.selfWeights)
        assertContentEquals(longArrayOf(2), table.sampleCounts)
        assertContentEquals(intArrayOf(1), table.threadCounts)
        assertEquals(listOf("UI"), table.categories)
        assertEquals(frame(11), table.framesById[11])
    }

    @Test
    fun `flame graph rows snapshot nested arrays and returned arrays`() {
        val row = intArrayOf(1, 2)
        val rowsSource = mutableListOf(row)
        val starts = doubleArrayOf(0.0, 0.5)
        val ends = doubleArrayOf(0.5, 1.0)
        val rows = FlameGraphRows(rowsSource, starts, ends, startsAtBottom = true)

        row[0] = 99
        rowsSource.clear()
        starts[0] = 99.0
        ends[0] = 99.0
        rows.nodeIndexesByRow[0][0] = 100
        rows.starts[0] = 100.0
        rows.ends[0] = 100.0

        assertContentEquals(intArrayOf(1, 2), rows.nodeIndexesByRow.single())
        assertContentEquals(doubleArrayOf(0.0, 0.5), rows.starts)
        assertContentEquals(doubleArrayOf(0.5, 1.0), rows.ends)
    }

    @Test
    fun `stack query table and snapshot snapshot caller collections`() {
        val frameIds = mutableListOf(11L)
        val frameCategories = mutableListOf<String?>("Graphics")
        val stack = weightedStack(frameIds, frameCategories)
        val frames = mutableMapOf(11L to frame(11))
        val stacks = mutableListOf(stack)
        val table = CallStackTable(frames, stacks)
        val transforms = mutableListOf<CallStackTransform>(CallStackTransform.FocusFunction(FlameFunctionId(11)))
        val query = CallStackAnalysisQuery(transforms = transforms)
        val invalidTransforms = transforms.toMutableList()
        val snapshot = FlameGraphSnapshot(query, callNodeTable(), emptyRows(), 1, null, invalidTransforms)

        frameIds[0] = 99
        frameCategories[0] = "mutated"
        frames.clear()
        stacks.clear()
        transforms.clear()
        invalidTransforms.clear()

        assertEquals(listOf(11L), stack.frameIdsRootToLeaf)
        assertEquals(listOf("Graphics"), stack.categoriesRootToLeaf)
        assertEquals(frame(11), table.frame(11))
        assertEquals(listOf(stack), table.stacks)
        assertEquals(listOf(CallStackTransform.FocusFunction(FlameFunctionId(11))), query.transforms)
        assertEquals(listOf(CallStackTransform.FocusFunction(FlameFunctionId(11))), snapshot.invalidTransforms)
    }

    @Test
    fun `weighted stacks fallback categories per frame and reject misaligned snapshots`() {
        val fallback = weightedStack(listOf(11L, 12L))

        assertEquals(listOf("UI", "UI"), fallback.categoriesRootToLeaf)
        assertFailsWith<IllegalArgumentException> {
            weightedStack(listOf(11L, 12L), listOf("Graphics"))
        }
    }

    private fun frame(frameId: Long) =
        CallStackFrame(
            frameId = frameId,
            functionId = FlameFunctionId(frameId),
            symbolName = "frame-$frameId",
            resource = "lib.so",
            virtualAddress = frameId,
            implementation = FrameImplementation.NATIVE,
        )

    private fun weightedStack(
        frameIds: List<Long>,
        categoriesRootToLeaf: List<String?>? = null,
    ) = WeightedCallStack(
        sampleId = 1,
        timestampNanos = 2,
        weight = 3,
        threadKey = "thread",
        category = "UI",
        subcategory = null,
        frameIdsRootToLeaf = frameIds,
        categoriesRootToLeaf = categoriesRootToLeaf,
    )

    private fun callNodeTable(idsByPath: Map<CallNodePath, FlameCallNodeId> = emptyMap()) =
        CallNodeTable(
            ids = longArrayOf(7),
            parentIndexes = intArrayOf(-1),
            frameIds = longArrayOf(11),
            depths = intArrayOf(0),
            inclusiveWeights = longArrayOf(1),
            selfWeights = longArrayOf(1),
            sampleCounts = longArrayOf(1),
            threadCounts = intArrayOf(1),
            categories = listOf("UI"),
            framesById = mapOf(11L to frame(11)),
            idsByPath = idsByPath,
        )

    private fun emptyRows() = FlameGraphRows(emptyList(), doubleArrayOf(), doubleArrayOf(), startsAtBottom = true)
}
