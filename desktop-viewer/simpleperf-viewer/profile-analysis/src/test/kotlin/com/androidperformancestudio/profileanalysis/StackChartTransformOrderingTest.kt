package com.androidperformancestudio.profileanalysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StackChartTransformOrderingTest {
    @Test
    fun `focus sees native ancestors before implementation filtering hides them`() {
        val source = table(listOf(1, 2), listOf(3))
        val query =
            CallStackAnalysisQuery(
                implementation = ImplementationFilter.SCRIPT,
                searchText = "frame2,2.so",
                transforms = listOf(CallStackTransform.FocusFunction(FlameFunctionId(1))),
            )

        val snapshot = StackChartProjector.project(source, query, 30)
        val block = snapshot.blocks.single()

        assertEquals(2, block.frameId)
        assertEquals(0, block.depth)
        assertEquals(10, block.startNanos)
        assertEquals(20, block.endNanosExclusive)
        assertNull(snapshot.emptyReason)
    }

    @Test
    fun `call node focus uses original path and preserves rejected sample time gaps`() {
        val source = table(listOf(1, 2, 3), listOf(1, 3), listOf(1, 2, 3), listOf(1, 3))
        val query =
            CallStackAnalysisQuery(
                implementation = ImplementationFilter.NATIVE,
                searchText = "frame3",
                transforms =
                    listOf(
                        CallStackTransform.FocusCallNode(CallNodePath(listOf(FlameFunctionId(1), FlameFunctionId(2)))),
                    ),
            )

        val snapshot = StackChartProjector.project(source, query, 50)
        val expectedNode =
            CallTreeProjector
                .project(table(listOf(3)))
                .findByPath(CallNodePath(listOf(FlameFunctionId(3))))

        assertEquals(listOf(10L to 20L, 30L to 40L), snapshot.blocks.map { it.startNanos to it.endNanosExclusive })
        assertEquals(listOf(3L, 3L), snapshot.blocks.map(StackChartBlock::frameId))
        assertEquals(listOf(expectedNode, expectedNode), snapshot.blocks.map(StackChartBlock::callNodeId))
    }

    @Test
    fun `search cannot match a frame removed by a transform`() {
        val query =
            CallStackAnalysisQuery(
                searchText = "frame3",
                transforms = listOf(CallStackTransform.MergeFunction(FlameFunctionId(3))),
            )

        val snapshot = StackChartProjector.project(table(listOf(1, 3)), query, 20)

        assertEquals(emptyList(), snapshot.blocks)
        assertEquals(StackChartEmptyReason.FILTERED_ALL, snapshot.emptyReason)
    }

    @Test
    fun `preview excluded call paths cannot validate a focus transform`() {
        val query =
            CallStackAnalysisQuery(
                previewRange = AnalysisTimeRange(10, 20),
                transforms = listOf(CallStackTransform.FocusCallNode(CallNodePath(listOf(FlameFunctionId(2))))),
            )

        val snapshot = StackChartProjector.project(table(listOf(1, 3), listOf(2)), query, 30)

        assertEquals(listOf(1L, 3L), snapshot.blocks.map(StackChartBlock::frameId))
        assertEquals(listOf(10L to 20L, 10L to 20L), snapshot.blocks.map { it.startNanos to it.endNanosExclusive })
    }

    @Test
    fun `empty transform is filtered all but empty preview remains range empty`() {
        val source = table(listOf(1))
        val query =
            CallStackAnalysisQuery(
                transforms = listOf(CallStackTransform.DropFunction(FlameFunctionId(1))),
            )

        val transformedEmpty = StackChartProjector.project(source, query, 30)
        val previewEmpty =
            StackChartProjector.project(source, query.copy(previewRange = AnalysisTimeRange(20, 30)), 30)

        assertEquals(StackChartEmptyReason.FILTERED_ALL, transformedEmpty.emptyReason)
        assertEquals(StackChartEmptyReason.RANGE_EMPTY, previewEmpty.emptyReason)
    }

    private fun table(vararg paths: List<Long>): CallStackTable =
        CallStackTable(
            framesById =
                (1L..3L).associateWith { id ->
                    CallStackFrame(
                        frameId = id,
                        functionId = FlameFunctionId(id),
                        symbolName = "frame$id",
                        resource = "$id.so",
                        virtualAddress = id,
                        implementation = if (id == 2L) FrameImplementation.MANAGED else FrameImplementation.NATIVE,
                    )
                },
            stacks =
                paths.mapIndexed { index, frames ->
                    WeightedCallStack(index.toLong(), (index + 1) * 10L, 1, "main", null, null, frames)
                },
        )
}
