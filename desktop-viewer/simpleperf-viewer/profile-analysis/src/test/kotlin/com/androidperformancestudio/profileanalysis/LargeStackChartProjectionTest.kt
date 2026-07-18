@file:Suppress("MagicNumber")

package com.androidperformancestudio.profileanalysis

import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LargeStackChartProjectionTest {
    @Test
    fun `million sample projection and narrow range culling remain bounded`() {
        StackChartProjector.project(table(10_000), CallStackAnalysisQuery(), 10_000)
        val source = table(SAMPLE_COUNT)
        lateinit var snapshot: StackChartSnapshot
        lateinit var culledSnapshot: StackChartSnapshot

        val elapsedMillis =
            measureTimeMillis {
                snapshot = StackChartProjector.project(source, CallStackAnalysisQuery(), SAMPLE_COUNT.toLong())
                culledSnapshot =
                    StackChartProjector.project(
                        source,
                        CallStackAnalysisQuery(
                            previewRange = AnalysisTimeRange(CULL_START_NANOS, CULL_END_NANOS_EXCLUSIVE),
                        ),
                        CULL_END_NANOS_EXCLUSIVE,
                    )
            }

        assertEquals(THREAD_COUNT, snapshot.blocks.size)
        assertEquals(SAMPLE_COUNT.toLong(), snapshot.blocks.sumOf(StackChartBlock::weight))
        assertEquals(0, snapshot.maxDepth)
        assertEquals(1, culledSnapshot.blocks.size)
        assertEquals(CULL_SAMPLE_COUNT.toLong(), culledSnapshot.blocks.single().weight)
        assertEquals(CULL_START_NANOS, culledSnapshot.blocks.single().startNanos)
        assertEquals(CULL_END_NANOS_EXCLUSIVE, culledSnapshot.blocks.single().endNanosExclusive)
        assertTrue(elapsedMillis <= TIMEOUT_MILLIS, "Projection took ${elapsedMillis}ms")
    }

    private fun table(sampleCount: Int): CallStackTable {
        val frame =
            CallStackFrame(
                frameId = FRAME_ID,
                functionId = FlameFunctionId(FRAME_ID),
                symbolName = "renderFrame",
                resource = "libui.so",
                virtualAddress = 0,
                implementation = FrameImplementation.NATIVE,
            )
        val threadKeys = List(THREAD_COUNT) { "thread:$it" }
        val samplesPerThread = (sampleCount / THREAD_COUNT).coerceAtLeast(1)
        return CallStackTable(
            framesById = mapOf(FRAME_ID to frame),
            stacks =
                List(sampleCount) { index ->
                    WeightedCallStack(
                        sampleId = index.toLong(),
                        timestampNanos = index.toLong(),
                        weight = 1,
                        threadKey = threadKeys[(index / samplesPerThread).coerceAtMost(THREAD_COUNT - 1)],
                        category = null,
                        subcategory = null,
                        frameIdsRootToLeaf = listOf(FRAME_ID),
                    )
                },
        )
    }

    private companion object {
        const val SAMPLE_COUNT = 1_000_000
        const val THREAD_COUNT = 8
        const val FRAME_ID = 1L
        const val CULL_START_NANOS = 500_000L
        const val CULL_SAMPLE_COUNT = 100
        const val CULL_END_NANOS_EXCLUSIVE = CULL_START_NANOS + CULL_SAMPLE_COUNT
        const val TIMEOUT_MILLIS = 10_000L
    }
}
