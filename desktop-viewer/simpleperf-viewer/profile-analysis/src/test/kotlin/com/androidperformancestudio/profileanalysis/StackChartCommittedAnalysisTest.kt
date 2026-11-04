package com.androidperformancestudio.profileanalysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StackChartCommittedAnalysisTest {
    @Test
    fun `committed analysis shares the complete forward block identities and real rows`() {
        val snapshot = StackChartProjector.project(table(), CallStackAnalysisQuery(), 30)
        val analysis = assertNotNull(snapshot.committedAnalysis)
        val expected = CallTreeProjector.project(table())

        assertEquals(CallStackDirection.FORWARD, analysis.query.direction)
        assertNull(analysis.query.previewRange)
        assertEquals(expected, analysis.callNodes)
        assertEquals(FlameGraphRowProjector.project(expected, CallStackDirection.FORWARD), analysis.rows)
        assertEquals(10, analysis.totalWeight)
        snapshot.blocks.forEach { block ->
            val index = assertNotNull(analysis.callNodes.indexOf(assertNotNull(block.callNodeId)))
            assertEquals(
                snapshot.framesById.getValue(block.frameId).functionId,
                analysis.callNodes.frameAt(index)?.functionId,
            )
        }
        assertSame(analysis, snapshot.copy().committedAnalysis)
        assertNull(snapshot.copy(committedAnalysis = null).committedAnalysis)
    }

    @Test
    fun `preview empty layout still carries complete forward committed analysis`() {
        val query =
            CallStackAnalysisQuery(previewRange = AnalysisTimeRange(12, 18), direction = CallStackDirection.INVERTED)
        val snapshot = StackChartProjector.project(table(), query, 30)
        val analysis = assertNotNull(snapshot.committedAnalysis)

        assertTrue(snapshot.blocks.isEmpty())
        assertEquals(StackChartEmptyReason.RANGE_EMPTY, snapshot.emptyReason)
        assertEquals(query.copy(previewRange = null, direction = CallStackDirection.FORWARD), analysis.query)
        assertEquals(CallTreeProjector.project(table()), analysis.callNodes)
        assertEquals(10, analysis.totalWeight)
        assertNull(analysis.emptyReason)
    }

    @Test
    fun `committed analysis applies transforms before implementation and search`() {
        val query =
            CallStackAnalysisQuery(
                searchText = "frame2",
                implementation = ImplementationFilter.NATIVE,
                transforms =
                    listOf(
                        CallStackTransform.FocusCallNode(CallNodePath(listOf(FlameFunctionId(1), FlameFunctionId(2)))),
                    ),
                previewRange = AnalysisTimeRange(12, 18),
            )
        val analysis = assertNotNull(StackChartProjector.project(table(), query, 30).committedAnalysis)

        assertEquals(query.copy(previewRange = null), analysis.query)
        assertTrue(analysis.invalidTransforms.isEmpty())
        assertEquals(1, analysis.callNodes.size)
        assertEquals("frame2", analysis.callNodes.frameAt(0)?.symbolName)
        assertEquals(3, analysis.totalWeight)
    }

    @Test
    fun `committed empty reasons and invalid transforms describe their own full query`() {
        val missing = CallStackTransform.FocusCallNode(CallNodePath(listOf(FlameFunctionId(99))))
        val invalid =
            assertNotNull(
                StackChartProjector
                    .project(
                        table(),
                        CallStackAnalysisQuery(transforms = listOf(missing)),
                        30,
                    ).committedAnalysis,
            )
        val search =
            assertNotNull(
                StackChartProjector
                    .project(
                        table(),
                        CallStackAnalysisQuery(searchText = "absent"),
                        30,
                    ).committedAnalysis,
            )
        val implementation =
            assertNotNull(
                StackChartProjector
                    .project(
                        table(),
                        CallStackAnalysisQuery(implementation = ImplementationFilter.SCRIPT),
                        30,
                    ).committedAnalysis,
            )

        assertEquals(listOf(missing), invalid.invalidTransforms)
        assertEquals(10, invalid.totalWeight)
        assertEquals(FlameGraphEmptyReason.SEARCH_FILTERED_ALL, search.emptyReason)
        assertEquals(FlameGraphEmptyReason.IMPLEMENTATION_FILTERED_ALL, implementation.emptyReason)
        assertEquals(0, search.callNodes.size)
    }

    @Test
    fun `legacy snapshot construction defaults to no committed analysis`() {
        val snapshot = StackChartSnapshot(emptyMap(), emptyList(), null, null, 0, StackChartEmptyReason.NO_SAMPLES)
        assertNull(snapshot.committedAnalysis)
    }

    @Test
    fun `sample weighted committed analysis retains source traced timing outside an empty preview`() {
        val source = table().withWeightType(CallStackWeightType.SAMPLES)
        val query =
            CallStackAnalysisQuery(
                previewRange = AnalysisTimeRange(12, 18),
                weightType = CallStackWeightType.SAMPLES,
            )
        val analysis = assertNotNull(StackChartProjector.project(source, query, 30).committedAnalysis)

        assertEquals(CallStackWeightType.SAMPLES, analysis.weightType)
        assertEquals(CallStackWeightType.SAMPLES, analysis.query.weightType)
        assertEquals(2L, analysis.totalWeight)
        assertEquals(1_000_010L, analysis.callNodes.tracedInclusiveNanosAt(0))
        assertEquals(0L, analysis.callNodes.tracedSelfNanosAt(0))
        assertEquals(0L, analysis.callNodes.selfSampleCountAt(0))
    }

    private fun table(): CallStackTable =
        CallStackTable(
            (1L..3L).associateWith { id ->
                CallStackFrame(id, FlameFunctionId(id), "frame$id", "/lib.so", id, FrameImplementation.NATIVE)
            },
            listOf(
                WeightedCallStack(1, 10, 3, "main", null, null, listOf(1, 2)),
                WeightedCallStack(2, 20, 7, "main", null, null, listOf(1, 3)),
            ),
        )
}
