package com.androidperformancestudio.application

import com.androidperformancestudio.profileanalysis.AnalysisTimeRange
import com.androidperformancestudio.profileanalysis.CallNodePath
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.CallStackFilter
import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.CallStackTable
import com.androidperformancestudio.profileanalysis.CallStackTransform
import com.androidperformancestudio.profileanalysis.CallStackTransformer
import com.androidperformancestudio.profileanalysis.CallTreeProjector
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FlameGraphNavigationCommand
import com.androidperformancestudio.profileanalysis.FlameGraphRowProjector
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.profileanalysis.FrameImplementation
import com.androidperformancestudio.profileanalysis.StackChartProjector
import com.androidperformancestudio.profileanalysis.StackChartSnapshot
import com.androidperformancestudio.profileanalysis.WeightedCallStack
import com.androidperformancestudio.storage.PanelProjection
import com.androidperformancestudio.storage.ProfileProjectionRequest
import com.androidperformancestudio.storage.ProfileProjectionSnapshot
import com.androidperformancestudio.storage.ProfileQuery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReportControllerStackChartAnalysisTest {
    @Test
    fun `visible interval with no preview sample still supports selection hover context navigation and details`() =
        runTest {
            val fixture = openStackController()
            fixture.previewGap()
            val report = fixture.report()
            assertEquals(0, report.flameGraph.callNodes.size)
            val block = fixture.chart().blocks.single { it.frameId == 2L }
            val leafId = assertNotNull(block.callNodeId)
            assertEquals(10, block.startNanos)
            assertEquals(20, block.endNanosExclusive)

            fixture.controller.selectStackChartBlock(block.id)
            fixture.controller.hoverCallNode(leafId)
            fixture.controller.openCallNodeContext(leafId)
            assertEquals(leafId, fixture.controller.state.value.workspace.selections.callNodeId)
            assertEquals(leafId, fixture.controller.state.value.flameGraph.hoveredNodeId)
            assertEquals(leafId, fixture.controller.state.value.flameGraph.contextNodeId)
            val analysis = assertNotNull(fixture.chart().committedAnalysis)
            val parent = assertNotNull(analysis.callNodes.findByPath(CallNodePath(listOf(FlameFunctionId(1)))))
            assertEquals(parent, fixture.controller.navigateCallNode(FlameGraphNavigationCommand.PARENT))
            fixture.controller.selectCallNode(leafId)
            fixture.controller.openFrameDetails(leafId)
            runCurrent()
            assertEquals("frame2", fixture.details.single().function)
            assertIs<FlameGraphDetailsState.Ready>(fixture.controller.state.value.flameGraph.details)
            assertEquals(AnalysisTimeRange(12, 18), fixture.controller.state.value.callStackQuery.previewRange)
        }

    @Test
    fun `committed visible block path stays valid for transforms when preview is empty`() =
        runTest {
            val fixture = openStackController()
            fixture.previewGap()
            val path = CallNodePath(listOf(FlameFunctionId(1), FlameFunctionId(2)))
            val transform = CallStackTransform.FocusCallNode(path)

            fixture.controller.applyTransform(transform)

            val analysis = assertNotNull(fixture.chart().committedAnalysis)
            assertEquals(listOf(transform), analysis.query.transforms)
            assertTrue(analysis.invalidTransforms.isEmpty())
            assertTrue(
                fixture.controller.state.value.flameGraph.invalidTransforms
                    .isEmpty(),
            )
            assertEquals("frame2", analysis.callNodes.frameAt(0)?.symbolName)
            assertEquals(
                0,
                fixture
                    .report()
                    .flameGraph.callNodes.size,
            )
            val block = fixture.chart().blocks.single()
            fixture.controller.openFrameDetails(assertNotNull(block.callNodeId))
            runCurrent()
            assertEquals("frame2", fixture.details.single().function)
        }

    @Test
    fun `stack selection survives preview refresh but other tabs still validate their preview graph`() =
        runTest {
            val fixture = openStackController()
            val id =
                assertNotNull(
                    fixture
                        .chart()
                        .blocks
                        .single { it.frameId == 2L }
                        .callNodeId,
                )
            fixture.controller.selectCallNode(id)
            fixture.previewGap()
            assertEquals(id, fixture.controller.state.value.workspace.selections.callNodeId)
            fixture.controller.retryProjection()
            assertEquals(id, fixture.controller.state.value.workspace.selections.callNodeId)

            fixture.controller.selectTab(ReportTab.FLAME_GRAPH)
            runCurrent()
            fixture.controller.selectCallNode(id)
            fixture.controller.hoverCallNode(id)
            fixture.controller.openCallNodeContext(id)
            fixture.controller.openFrameDetails(id)
            runCurrent()
            assertNull(fixture.controller.state.value.workspace.selections.callNodeId)
            assertNull(fixture.controller.state.value.flameGraph.hoveredNodeId)
            assertNull(fixture.controller.state.value.flameGraph.contextNodeId)
            assertTrue(fixture.details.isEmpty())
        }

    @Test
    fun `removed committed selection falls back to a visible ancestor even with an empty preview`() =
        runTest {
            val fixture = openStackController()
            val leaf =
                assertNotNull(
                    fixture
                        .chart()
                        .blocks
                        .single { it.frameId == 2L }
                        .callNodeId,
                )
            fixture.controller.selectCallNode(leaf)
            fixture.previewGap()
            fixture.controller.applyTransform(CallStackTransform.CollapseFunctionSubtree(FlameFunctionId(1)))

            val analysis = assertNotNull(fixture.chart().committedAnalysis)
            val root = assertNotNull(analysis.callNodes.findByPath(CallNodePath(listOf(FlameFunctionId(1)))))
            assertEquals(root, fixture.controller.state.value.workspace.selections.callNodeId)
            assertEquals(
                0,
                fixture
                    .report()
                    .flameGraph.callNodes.size,
            )
        }

    @Test
    fun `stack selection crosses an inverted tab and returns to its original committed function`() =
        runTest {
            val fixture = openStackController()
            val leaf =
                assertNotNull(
                    fixture
                        .chart()
                        .blocks
                        .single { it.frameId == 2L }
                        .callNodeId,
                )
            fixture.controller.selectCallNode(leaf)
            fixture.controller.updateFlameGraphDirection(CallStackDirection.INVERTED)
            fixture.controller.selectTab(ReportTab.FLAME_GRAPH)
            runCurrent()
            val inverted = fixture.report().flameGraph
            val invertedId = assertNotNull(fixture.controller.state.value.workspace.selections.callNodeId)
            assertEquals(
                FlameFunctionId(2),
                inverted.callNodes.frameAt(assertNotNull(inverted.callNodes.indexOf(invertedId)))?.functionId,
            )

            fixture.controller.selectTab(ReportTab.STACK_CHART)
            runCurrent()
            assertEquals(leaf, fixture.controller.state.value.workspace.selections.callNodeId)
        }

    @Test
    fun `entering stack tab resolves real block details while inverted projection is still published`() =
        runTest {
            val fixture = openStackController()
            fixture.controller.selectTab(ReportTab.FLAME_GRAPH)
            runCurrent()
            fixture.controller.updateCallStackDirection(CallStackDirection.INVERTED)
            assertEquals(
                CallStackDirection.INVERTED,
                fixture
                    .report()
                    .flameGraph.query.direction,
            )
            val block = fixture.chart().blocks.single { it.frameId == 2L }

            fixture.controller.selectTab(ReportTab.STACK_CHART)
            fixture.controller.selectStackChartBlock(block.id)
            fixture.controller.openFrameDetails(assertNotNull(block.callNodeId))
            assertIs<FlameGraphDetailsState.Loading>(fixture.controller.state.value.flameGraph.details)
            assertEquals(block.callNodeId, fixture.controller.state.value.workspace.selections.callNodeId)
        }

    @Test
    fun `legacy stack snapshots without committed analysis keep using the flame graph`() =
        runTest {
            val fixture = openStackController(includeCommitted = false)
            assertNull(fixture.chart().committedAnalysis)
            val block = fixture.chart().blocks.single { it.frameId == 2L }
            fixture.controller.selectStackChartBlock(block.id)
            fixture.controller.openFrameDetails(assertNotNull(block.callNodeId))
            runCurrent()
            assertEquals(block.callNodeId, fixture.controller.state.value.workspace.selections.callNodeId)
            assertIs<FlameGraphDetailsState.Ready>(fixture.controller.state.value.flameGraph.details)
        }

    @Test
    fun `stale stack frame details remain generation guarded`() =
        runTest {
            val resolved = CompletableDeferred<FlameGraphFrameDetails>()
            val fixture = openStackController(provider = FlameGraphFrameDetailsProvider { resolved.await() })
            fixture.previewGap()
            val id =
                assertNotNull(
                    fixture
                        .chart()
                        .blocks
                        .single { it.frameId == 2L }
                        .callNodeId,
                )
            fixture.controller.openFrameDetails(id)
            runCurrent()
            assertIs<FlameGraphDetailsState.Loading>(fixture.controller.state.value.flameGraph.details)
            fixture.controller.updateFlameSearch("frame3")
            resolved.complete(
                FlameGraphFrameDetails.Source(Files.createTempFile("aps-stale-", ".cpp"), 1, null, listOf("stale")),
            )
            runCurrent()
            assertEquals(FlameGraphDetailsState.Closed, fixture.controller.state.value.flameGraph.details)
        }

    private suspend fun TestScope.openStackController(
        includeCommitted: Boolean = true,
        provider: FlameGraphFrameDetailsProvider? = null,
    ): Fixture {
        val requests = mutableListOf<FlameGraphFrameDetailsRequest>()
        val loader =
            object : ProfileProjectionLoader {
                override suspend fun load(
                    session: PreparedProfileSession,
                    query: ProfileQuery,
                ): ProfileProjectionSnapshot = snapshot(ProfileProjectionRequest(query = query), includeCommitted)

                override suspend fun load(
                    session: PreparedProfileSession,
                    request: ProfileProjectionRequest,
                ): ProfileProjectionSnapshot = snapshot(request, includeCommitted)
            }
        val workspace = ProfileWorkspaceController(backgroundScope, loader)
        val controller =
            ReportController(
                scope = backgroundScope,
                workspaceController = workspace,
                detailsProvider =
                    provider ?: FlameGraphFrameDetailsProvider { request ->
                        requests += request
                        FlameGraphFrameDetails.Source(
                            request.sessionDirectory.resolve("source.cpp"),
                            1,
                            null,
                            listOf("source"),
                        )
                    },
            )
        controller.openSession(Files.createTempDirectory("aps-stack-committed-"))
        controller.selectTab(ReportTab.STACK_CHART)
        runCurrent()
        return Fixture(this, controller, requests)
    }

    private class Fixture(
        private val scope: TestScope,
        val controller: ReportController,
        val details: List<FlameGraphFrameDetailsRequest>,
    ) {
        suspend fun previewGap() {
            controller.previewRange(AnalysisTimeRange(12, 18))
            scope.advanceTimeBy(16)
            scope.runCurrent()
        }

        fun report(): ReportData = assertIs<ReportLoadState.Ready>(controller.state.value.loadState).report

        fun chart(): StackChartSnapshot = assertIs<PanelProjection.Ready<StackChartSnapshot>>(report().stackChart).value
    }
}

private fun snapshot(
    request: ProfileProjectionRequest,
    includeCommitted: Boolean,
): ProfileProjectionSnapshot {
    val source =
        CallStackTable(
            (1L..3L).associateWith { id ->
                CallStackFrame(id, FlameFunctionId(id), "frame$id", "/lib.so", id, FrameImplementation.NATIVE)
            },
            listOf(
                WeightedCallStack(1, 10, 3, "main", null, null, listOf(1, 2)),
                WeightedCallStack(2, 20, 7, "main", null, null, listOf(1, 3)),
            ),
        )
    val query = request.callStackAnalysis
    val preview = CallStackFilter.apply(source, CallStackAnalysisQuery(previewRange = query.previewRange))
    val transformed = CallStackTransformer.apply(preview.table, query.transforms)
    val filtered = CallStackFilter.apply(transformed.table, query.copy(previewRange = null))
    val nodes = CallTreeProjector.project(filtered.table, query.direction)
    val chart =
        StackChartProjector.project(source, query.copy(previewRange = null, direction = CallStackDirection.FORWARD), 30)
    return workspaceSnapshot(request.query).copy(
        flameGraph =
            FlameGraphSnapshot(
                query,
                nodes,
                FlameGraphRowProjector.project(nodes, query.direction),
                filtered.table.stacks
                    .filter { it.frameIdsRootToLeaf.isNotEmpty() }
                    .sumOf(WeightedCallStack::weight),
                null,
                transformed.invalidTransforms,
            ),
        stackChart = PanelProjection.Ready(if (includeCommitted) chart else chart.copy(committedAnalysis = null)),
    )
}
