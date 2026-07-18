package com.androidperformancestudio.profileanalysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StackChartProjectorTest {
    @Test
    fun `projects sample time horizontally and root depth vertically`() {
        val snapshot = StackChartProjector.project(twoSampleTable(), CallStackAnalysisQuery(), 31)

        assertEquals(10, snapshot.blocks.first().startNanos)
        assertEquals(20, snapshot.blocks.first().endNanosExclusive)
        assertEquals(0, snapshot.blocks.first().depth)
        assertEquals(10, snapshot.startNanos)
        assertEquals(31, snapshot.endNanosExclusive)
        assertEquals(1, snapshot.maxDepth)
        assertNull(snapshot.emptyReason)
    }

    @Test
    fun `inverted direction reverses visible frame depth`() {
        val query = CallStackAnalysisQuery(direction = CallStackDirection.INVERTED)

        val snapshot = StackChartProjector.project(twoSampleTable(), query, 31)

        assertEquals("leaf", snapshot.frameAtDepth(0).symbolName)
        assertEquals("root", snapshot.frameAtDepth(1).symbolName)
    }

    @Test
    fun `filters and transforms stacks before layout`() {
        val query =
            CallStackAnalysisQuery(
                searchText = "managedLeaf",
                implementation = ImplementationFilter.SCRIPT,
                transforms = listOf(CallStackTransform.FocusFunction(FlameFunctionId(MANAGED_LEAF))),
            )

        val snapshot = StackChartProjector.project(filterTable(), query, 31)

        assertEquals(listOf("managedLeaf"), snapshot.blocks.map { snapshot.framesById.getValue(it.frameId).symbolName })
        assertEquals(listOf(20L to 31L), snapshot.blocks.map { it.startNanos to it.endNanosExclusive })
    }

    @Test
    fun `adjacent equal frames coalesce only for the same thread and depth`() {
        val snapshot = StackChartProjector.project(repeatedFrameTable(), CallStackAnalysisQuery(), 51)

        assertEquals(
            listOf(
                Triple("main", 10L, 30L),
                Triple("worker", 30L, 40L),
                Triple("main", 40L, 51L),
            ),
            snapshot.blocksAtDepth(0).map { Triple(it.threadKey, it.startNanos, it.endNanosExclusive) },
        )
        assertEquals(listOf(3L, 3L, 4L), snapshot.blocksAtDepth(0).map(StackChartBlock::weight))
        assertEquals(
            listOf(
                StackChartBlockId("main:0:$ROOT:10"),
                StackChartBlockId("worker:0:$ROOT:30"),
                StackChartBlockId("main:0:$ROOT:40"),
            ),
            snapshot.blocksAtDepth(0).map(StackChartBlock::id),
        )
        assertEquals(3, snapshot.blocksAtDepth(1).size)
    }

    @Test
    fun `sorts equal-time samples by sample id deterministically`() {
        val table =
            CallStackTable(
                framesById = frames(),
                stacks =
                    listOf(
                        stack(sampleId = 2, timestamp = 10, frameIds = listOf(ROOT), threadKey = "two"),
                        stack(sampleId = 1, timestamp = 10, frameIds = listOf(LEAF), threadKey = "one"),
                    ),
            )

        val snapshot = StackChartProjector.project(table, CallStackAnalysisQuery(), 20)

        assertEquals(listOf(1L, 2L), snapshot.blocks.map(StackChartBlock::sampleId))
        assertEquals(listOf(10L to 10L, 10L to 20L), snapshot.blocks.map { it.startNanos to it.endNanosExclusive })
    }

    @Test
    fun `reports the stage that emptied the projection`() {
        val noSamples = StackChartProjector.project(CallStackTable(frames(), emptyList()), CallStackAnalysisQuery(), 31)
        val rangeEmpty =
            StackChartProjector.project(
                twoSampleTable(),
                CallStackAnalysisQuery(previewRange = AnalysisTimeRange(30, 40)),
                40,
            )
        val filteredAll =
            StackChartProjector.project(
                twoSampleTable(),
                CallStackAnalysisQuery(implementation = ImplementationFilter.SCRIPT),
                31,
            )

        assertEquals(StackChartEmptyReason.NO_SAMPLES, noSamples.emptyReason)
        assertEquals(StackChartEmptyReason.RANGE_EMPTY, rangeEmpty.emptyReason)
        assertEquals(StackChartEmptyReason.FILTERED_ALL, filteredAll.emptyReason)
        assertEquals(emptyList(), filteredAll.blocks)
        assertNull(filteredAll.startNanos)
        assertNull(filteredAll.endNanosExclusive)
    }

    @Test
    fun `snapshot defensively copies frame and block collections`() {
        val mutableFrames = mutableMapOf(ROOT to frames().getValue(ROOT))
        val mutableBlocks =
            mutableListOf(
                StackChartBlock(StackChartBlockId("block"), 1, 10, 20, 0, ROOT, "main", 1),
            )
        val snapshot = StackChartSnapshot(mutableFrames, mutableBlocks, 10, 20, 0, null)

        mutableFrames.clear()
        mutableBlocks.clear()

        assertEquals(setOf(ROOT), snapshot.framesById.keys)
        assertEquals(listOf(StackChartBlockId("block")), snapshot.blocks.map(StackChartBlock::id))
    }

    @Suppress("MaxLineLength")
    private fun StackChartSnapshot.blocksAtDepth(depth: Int): List<StackChartBlock> = blocks.filter { block -> block.depth == depth }

    private fun StackChartSnapshot.frameAtDepth(depth: Int): CallStackFrame =
        framesById.getValue(blocks.first { block -> block.depth == depth }.frameId)

    private fun twoSampleTable(): CallStackTable =
        CallStackTable(
            framesById = frames(),
            stacks =
                listOf(
                    stack(
                        sampleId = 2,
                        timestamp = 20,
                        frameIds = listOf(ROOT, LEAF),
                        threadKey = "worker",
                        weight = 2,
                    ),
                    stack(sampleId = 1, timestamp = 10, frameIds = listOf(ROOT, LEAF)),
                ),
        )

    private fun repeatedFrameTable(): CallStackTable =
        CallStackTable(
            framesById = frames(),
            stacks =
                listOf(
                    stack(sampleId = 1, timestamp = 10, frameIds = listOf(ROOT, LEAF)),
                    stack(sampleId = 2, timestamp = 20, frameIds = listOf(ROOT, LEAF), weight = 2),
                    stack(
                        sampleId = 3,
                        timestamp = 30,
                        frameIds = listOf(ROOT, LEAF),
                        threadKey = "worker",
                        weight = 3,
                    ),
                    stack(sampleId = 4, timestamp = 40, frameIds = listOf(ROOT, OTHER), weight = 4),
                ),
        )

    private fun filterTable(): CallStackTable =
        CallStackTable(
            framesById = frames(),
            stacks =
                listOf(
                    stack(sampleId = 1, timestamp = 10, frameIds = listOf(ROOT, LEAF)),
                    stack(sampleId = 2, timestamp = 20, frameIds = listOf(ROOT, MANAGED_LEAF)),
                ),
        )

    private fun frames(): Map<Long, CallStackFrame> =
        listOf(
            frame(ROOT, "root", FrameImplementation.NATIVE),
            frame(LEAF, "leaf", FrameImplementation.NATIVE),
            frame(OTHER, "other", FrameImplementation.NATIVE),
            frame(MANAGED_LEAF, "managedLeaf", FrameImplementation.MANAGED),
        ).associateBy(CallStackFrame::frameId)

    private fun frame(
        id: Long,
        symbol: String,
        implementation: FrameImplementation,
    ): CallStackFrame =
        CallStackFrame(
            frameId = id,
            functionId = FlameFunctionId(id),
            symbolName = symbol,
            resource = "$symbol.so",
            virtualAddress = id,
            implementation = implementation,
        )

    private fun stack(
        sampleId: Long,
        timestamp: Long,
        frameIds: List<Long>,
        threadKey: String = "main",
        weight: Long = 1,
    ): WeightedCallStack =
        WeightedCallStack(
            sampleId = sampleId,
            timestampNanos = timestamp,
            weight = weight,
            threadKey = threadKey,
            category = null,
            subcategory = null,
            frameIdsRootToLeaf = frameIds,
        )

    private companion object {
        const val ROOT = 1L
        const val LEAF = 2L
        const val OTHER = 3L
        const val MANAGED_LEAF = 4L
    }
}
