package com.androidperformancestudio.profileanalysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class StackChartIdentityTest {
    @Test
    fun `equal child frames under different ancestor paths do not coalesce`() {
        val source = table(listOf(1, 2, 3), listOf(4, 2, 3))

        val snapshot = StackChartProjector.project(source, CallStackAnalysisQuery(), 30)
        val leaves = snapshot.blocks.filter { it.depth == 2 }

        assertEquals(listOf(10L to 20L, 20L to 30L), leaves.map { it.startNanos to it.endNanosExclusive })
        assertNotEquals(leaves[0].callNodeId, leaves[1].callNodeId)
        assertNotEquals(
            snapshot.parentCallNodeIds[leaves[0].callNodeId],
            snapshot.parentCallNodeIds[leaves[1].callNodeId],
        )
    }

    @Test
    fun `adjacent frames for the same function path coalesce across frame ids`() {
        val source = table(listOf(1, 3), listOf(1, 5))
        val alias = source.framesById.getValue(5).copy(functionId = FlameFunctionId(3))
        val aliased = source.copy(framesById = source.framesById + (5L to alias))

        val snapshot = StackChartProjector.project(aliased, CallStackAnalysisQuery(), 30)
        val leaf = snapshot.blocks.single { it.depth == 1 }

        assertEquals(10, leaf.startNanos)
        assertEquals(30, leaf.endNanosExclusive)
        assertEquals(2, leaf.weight)
        assertEquals(
            CallTreeProjector.project(aliased).findByPath(CallNodePath(listOf(FlameFunctionId(1), FlameFunctionId(3)))),
            leaf.callNodeId,
        )
    }

    @Test
    fun `block and parent ids match flame projection after filters transforms and inversion`() {
        val source = table(listOf(1, 2, 3), listOf(4, 2, 5), listOf(1, 3, 3))
        val queries =
            listOf(
                CallStackAnalysisQuery(),
                CallStackAnalysisQuery(searchText = "frame3"),
                CallStackAnalysisQuery(implementation = ImplementationFilter.NATIVE),
                CallStackAnalysisQuery(implementation = ImplementationFilter.SCRIPT),
                CallStackAnalysisQuery(transforms = listOf(CallStackTransform.FocusFunction(FlameFunctionId(2)))),
                CallStackAnalysisQuery(transforms = listOf(CallStackTransform.MergeFunction(FlameFunctionId(2)))),
                CallStackAnalysisQuery(transforms = listOf(CallStackTransform.CollapseResource("shared.so"))),
                CallStackAnalysisQuery(
                    transforms = listOf(CallStackTransform.CollapseDirectRecursion(FlameFunctionId(3))),
                ),
                CallStackAnalysisQuery(
                    implementation = ImplementationFilter.NATIVE,
                    transforms = listOf(CallStackTransform.FocusFunction(FlameFunctionId(4))),
                ),
                CallStackAnalysisQuery(
                    searchText = "frame3",
                    transforms = listOf(CallStackTransform.MergeFunction(FlameFunctionId(3))),
                ),
            )

        for (direction in CallStackDirection.entries) {
            for (baseQuery in queries) {
                assertBlockIdentities(source, baseQuery.copy(direction = direction))
            }
        }
    }

    private fun assertBlockIdentities(
        source: CallStackTable,
        query: CallStackAnalysisQuery,
    ) {
        val preview = CallStackFilter.apply(source, CallStackAnalysisQuery(previewRange = query.previewRange))
        val transformed = CallStackTransformer.apply(preview.table, query.transforms).table
        val visible = CallStackFilter.apply(transformed, query.copy(previewRange = null)).table
        val flame = CallTreeProjector.project(visible, query.direction)
        val snapshot = StackChartProjector.project(source, query, 40)
        val samples = visible.stacks.associateBy(WeightedCallStack::sampleId)

        assertEquals(flame.size, snapshot.parentCallNodeIds.size)
        snapshot.blocks.forEach { block ->
            val stack = samples.getValue(block.sampleId)
            val frames =
                if (query.direction == CallStackDirection.FORWARD) {
                    stack.frameIdsRootToLeaf
                } else {
                    stack.frameIdsRootToLeaf.asReversed()
                }
            val path = CallNodePath(frames.take(block.depth + 1).map { visible.frame(it).functionId })
            val expected = assertNotNull(flame.findByPath(path))
            val index = assertNotNull(flame.indexOf(expected))
            val parent = flame.parentIndexAt(index)?.let(flame::nodeIdAt)

            assertEquals(expected, block.callNodeId, "$query: $path")
            assertEquals(parent, snapshot.parentCallNodeIds[expected])
            assertEquals(block, snapshot.blockById(block.id))
            assertEquals(true, snapshot.blocksByCallNodeId.getValue(expected).contains(block))
        }
    }

    @Test
    fun `search implementation and transforms preserve excluded sample intervals`() {
        val source = table(listOf(3), listOf(4), listOf(3), listOf(4))
        val queries =
            listOf(
                CallStackAnalysisQuery(searchText = "frame3"),
                CallStackAnalysisQuery(implementation = ImplementationFilter.NATIVE),
                CallStackAnalysisQuery(transforms = listOf(CallStackTransform.DropFunction(FlameFunctionId(4)))),
                CallStackAnalysisQuery(transforms = listOf(CallStackTransform.FocusFunction(FlameFunctionId(3)))),
            )

        queries.forEach { query ->
            val snapshot = StackChartProjector.project(source, query, 50)

            assertEquals(listOf(10L to 20L, 30L to 40L), snapshot.blocks.map { it.startNanos to it.endNanosExclusive })
        }
    }

    @Test
    fun `empty original stack leaves a temporal gap`() {
        val snapshot =
            StackChartProjector.project(table(listOf(3), emptyList(), listOf(3)), CallStackAnalysisQuery(), 40)

        assertEquals(listOf(10L to 20L, 30L to 40L), snapshot.blocks.map { it.startNanos to it.endNanosExclusive })
    }

    @Test
    fun `sample identities distinguish equal-time child blocks under different parents`() {
        val source = table(listOf(1, 3), listOf(4, 3))
        val equalTime = source.copy(stacks = source.stacks.map { it.copy(timestampNanos = 10) })

        val snapshot = StackChartProjector.project(equalTime, CallStackAnalysisQuery(), 20)

        assertEquals(snapshot.blocks.size, snapshot.blocksById.size)
        assertEquals(
            snapshot.blocks.map(StackChartBlock::sampleId),
            snapshot.blocksById.values.map(StackChartBlock::sampleId),
        )
    }

    @Test
    fun `sample end is bounded by preview and viewport even when the following sample is excluded`() {
        val query = CallStackAnalysisQuery(previewRange = AnalysisTimeRange(10, 15))
        val source = table(listOf(1), listOf(3))

        val preview = StackChartProjector.project(source, query, 30)
        val viewport = StackChartProjector.project(source, CallStackAnalysisQuery(), 15)

        assertEquals(15, preview.blocks.single().endNanosExclusive)
        assertEquals(15, preview.endNanosExclusive)
        assertEquals(15, viewport.blocks.first().endNanosExclusive)
    }

    private fun table(vararg paths: List<Long>): CallStackTable =
        CallStackTable(
            framesById =
                (1L..5L).associateWith { id ->
                    CallStackFrame(
                        frameId = id,
                        functionId = FlameFunctionId(id),
                        symbolName = "frame$id",
                        resource = if (id == 2L || id == 3L) "shared.so" else "$id.so",
                        virtualAddress = id,
                        implementation = if (id == 4L) FrameImplementation.MANAGED else FrameImplementation.NATIVE,
                    )
                },
            stacks =
                paths.mapIndexed { index, frames ->
                    WeightedCallStack(index.toLong(), (index + 1) * 10L, 1, "main", null, null, frames)
                },
        )
}
