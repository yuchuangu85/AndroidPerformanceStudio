package com.androidperformancestudio.application

import com.androidperformancestudio.profileanalysis.CallNodePath
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.CallStackTable
import com.androidperformancestudio.profileanalysis.CallStackTransform
import com.androidperformancestudio.profileanalysis.CallTreeProjector
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameFunctionId
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReportControllerDirectionTest {
    @Test
    fun `CPU tabs keep independent invert preferences and non CPU tabs stay forward`() =
        runTest {
            val harness = openedController()
            val controller = harness.controller
            controller.selectTab(ReportTab.CALL_TREE)
            runCurrent()
            harness.mutate { controller.updateCallStackDirection(CallStackDirection.INVERTED) }
            assertTrue(controller.state.value.workspace.invertCallTree)
            assertEquals(false, controller.state.value.workspace.invertFlameGraph)

            harness.selectTab(ReportTab.FLAME_GRAPH, CallStackDirection.FORWARD)
            harness.mutate { controller.updateCallStackDirection(CallStackDirection.INVERTED) }
            assertTrue(controller.state.value.workspace.invertCallTree)
            assertTrue(controller.state.value.workspace.invertFlameGraph)

            harness.selectTab(ReportTab.STACK_CHART, CallStackDirection.FORWARD)
            controller.updateCallStackDirection(CallStackDirection.INVERTED)
            assertEquals(CallStackDirection.FORWARD, controller.state.value.callStackQuery.direction)
            assertTrue(
                harness.loader.started
                    .tryReceive()
                    .isFailure,
            )
            harness.selectTab(ReportTab.CALL_TREE, CallStackDirection.INVERTED)
            harness.selectTab(ReportTab.MARKER_TABLE, CallStackDirection.FORWARD)
            harness.selectTab(ReportTab.FLAME_GRAPH, CallStackDirection.INVERTED)
        }

    @Test
    fun `explicit inactive direction setters save preferences without querying`() =
        runTest {
            val harness = openedController()
            val controller = harness.controller
            val generation = harness.workspace.state.value.generation
            controller.updateCallTreeDirection(CallStackDirection.INVERTED)
            controller.updateFlameGraphDirection(CallStackDirection.INVERTED)
            assertEquals(CallStackDirection.INVERTED, controller.state.value.callTreeDirection)
            assertEquals(CallStackDirection.INVERTED, controller.state.value.flameGraphDirection)
            assertEquals(CallStackDirection.FORWARD, controller.state.value.callStackQuery.direction)
            assertEquals(generation, harness.workspace.state.value.generation)
            assertTrue(
                harness.loader.started
                    .tryReceive()
                    .isFailure,
            )
            harness.selectTab(ReportTab.CALL_TREE, CallStackDirection.INVERTED)
        }

    @Test
    fun `tab direction is submitted asynchronously and the latest generation wins`() =
        runTest {
            val harness = openedController(ignoreCancellation = true)
            val controller = harness.controller
            controller.updateCallTreeDirection(CallStackDirection.INVERTED)
            val before = harness.workspace.state.value.generation
            controller.selectTab(ReportTab.CALL_TREE)
            assertEquals(before, harness.workspace.state.value.generation)
            assertTrue(
                harness.loader.started
                    .tryReceive()
                    .isFailure,
                "selectTab must not run the loader inline",
            )
            runCurrent()
            val obsolete = harness.loader.started.receive()
            assertEquals(CallStackDirection.INVERTED, obsolete.request.callStackAnalysis.direction)

            controller.selectTab(ReportTab.STACK_CHART)
            runCurrent()
            val newest = harness.loader.started.receive()
            assertEquals(CallStackDirection.FORWARD, newest.request.callStackAnalysis.direction)
            newest.succeed()
            runCurrent()
            obsolete.succeed()
            runCurrent()

            val state = controller.state.value
            assertEquals(ReportTab.STACK_CHART, state.selectedTab)
            assertEquals(
                CallStackDirection.FORWARD,
                assertIs<ReportLoadState.Ready>(state.loadState)
                    .report.flameGraph.query.direction,
            )
        }

    @Test
    fun `rapid tab changes coalesce to the latest desired direction`() =
        runTest {
            val harness = openedController()
            val controller = harness.controller
            controller.updateCallTreeDirection(CallStackDirection.INVERTED)
            controller.selectTab(ReportTab.CALL_TREE)
            controller.selectTab(ReportTab.FLAME_GRAPH)
            controller.selectTab(ReportTab.STACK_CHART)
            runCurrent()

            assertEquals(ReportTab.STACK_CHART, controller.state.value.selectedTab)
            assertEquals(CallStackDirection.FORWARD, controller.state.value.callStackQuery.direction)
            assertTrue(
                harness.loader.started
                    .tryReceive()
                    .isFailure,
            )
        }

    @Test
    fun `direction tab submission preserves filters transforms and the latest semantic mutation`() =
        runTest {
            val harness = openedController()
            val controller = harness.controller
            harness.mutate { controller.updateThreads(setOf(17)) }
            harness.mutate { controller.updateFlameSearch("work") }
            val transform = CallStackTransform.CollapseResource("/lib.so")
            harness.mutate { controller.applyTransform(transform) }
            controller.updateCallTreeDirection(CallStackDirection.INVERTED)
            controller.selectTab(ReportTab.CALL_TREE)
            val filtering = async(start = CoroutineStart.UNDISPATCHED) { controller.updateEvents(setOf("cycles")) }
            runCurrent()
            val pending = harness.loader.started.receive()
            assertEquals(CallStackDirection.INVERTED, pending.request.callStackAnalysis.direction)
            assertEquals("work", pending.request.callStackAnalysis.searchText)
            assertEquals(listOf(transform), pending.request.callStackAnalysis.transforms)
            assertEquals(setOf(17), pending.request.query.threadIds)
            assertEquals(setOf("cycles"), pending.request.query.eventTypes)
            pending.succeed()
            runCurrent()
            filtering.await()
            assertTrue(
                harness.loader.started
                    .tryReceive()
                    .isFailure,
            )
        }

    @Test
    fun `queued tab projection cannot leak into a newly opened session`() =
        runTest {
            val harness = openedController()
            val controller = harness.controller
            controller.updateCallTreeDirection(CallStackDirection.INVERTED)
            controller.selectTab(ReportTab.CALL_TREE)
            val session = Files.createTempDirectory("aps-direction-new-session-")
            val opening = async(start = CoroutineStart.UNDISPATCHED) { controller.openSession(session) }
            runCurrent()
            val pending = harness.loader.started.receive()
            assertEquals(CallStackDirection.FORWARD, pending.request.callStackAnalysis.direction)
            pending.succeed()
            runCurrent()
            opening.await()
            assertEquals(ReportTab.OVERVIEW, controller.state.value.selectedTab)
            assertEquals(false, controller.state.value.workspace.invertCallTree)
            assertEquals(
                session,
                assertIs<ReportLoadState.Ready>(controller.state.value.loadState).report.session.directory,
            )
            assertTrue(
                harness.loader.started
                    .tryReceive()
                    .isFailure,
            )
        }

    @Test
    fun `focus actions restore the destination tabs direction`() =
        runTest {
            val harness = openedController()
            val controller = harness.controller
            controller.updateCallTreeDirection(CallStackDirection.INVERTED)
            controller.focusCallTreeFunction("work")
            runCurrent()
            val tree = harness.loader.started.receive()
            assertEquals(CallStackDirection.INVERTED, tree.request.callStackAnalysis.direction)
            tree.succeed()
            runCurrent()

            controller.focusFunction("work")
            runCurrent()
            val flame = harness.loader.started.receive()
            assertEquals(CallStackDirection.FORWARD, flame.request.callStackAnalysis.direction)
            flame.succeed()
            runCurrent()
            assertEquals(ReportTab.FLAME_GRAPH, controller.state.value.selectedTab)
        }

    @Test
    fun `inversion keeps the same selected function using its heavy descendant path`() =
        runTest {
            val harness = openedController()
            val controller = harness.controller
            controller.selectTab(ReportTab.CALL_TREE)
            runCurrent()
            controller.selectCallNode(harness.nodeAt(1, 2))
            harness.mutate { controller.updateCallStackDirection(CallStackDirection.INVERTED) }
            assertEquals(harness.nodeAt(3, 2), controller.state.value.workspace.selections.callNodeId)
            harness.selectTab(ReportTab.STACK_CHART, CallStackDirection.FORWARD)
            assertEquals(harness.nodeAt(1, 2), controller.state.value.workspace.selections.callNodeId)
        }

    @Test
    fun `forward stack block selection does not alias an inverted call node`() =
        runTest {
            val harness = openedController()
            val controller = harness.controller
            controller.selectTab(ReportTab.CALL_TREE)
            runCurrent()
            controller.selectCallNode(harness.nodeAt(1, 2))
            harness.mutate { controller.updateCallStackDirection(CallStackDirection.INVERTED) }
            val report = assertIs<ReportLoadState.Ready>(controller.state.value.loadState).report
            val chart = assertIs<PanelProjection.Ready<StackChartSnapshot>>(report.stackChart).value
            controller.selectStackChartBlock(chart.blocks.single { it.frameId == 2L }.id)
            val selected = assertNotNull(controller.state.value.workspace.selections.callNodeId)
            val nodes = report.flameGraph.callNodes
            assertEquals(FlameFunctionId(2), nodes.frameAt(assertNotNull(nodes.indexOf(selected)))?.functionId)

            harness.selectTab(ReportTab.STACK_CHART, CallStackDirection.FORWARD)
            assertEquals(harness.nodeAt(1, 2), controller.state.value.workspace.selections.callNodeId)
        }

    @Test
    fun `equal direction tab changes do not close independently loaded frame details`() =
        runTest {
            val harness = openedController()
            val controller = harness.controller
            controller.selectTab(ReportTab.FLAME_GRAPH)
            runCurrent()
            controller.openFrameDetails(harness.nodeAt(1, 2))
            runCurrent()
            val details = assertIs<FlameGraphDetailsState.Ready>(controller.state.value.flameGraph.details)
            controller.selectTab(ReportTab.STACK_CHART)
            runCurrent()
            assertEquals(details, controller.state.value.flameGraph.details)
            assertTrue(
                harness.loader.started
                    .tryReceive()
                    .isFailure,
            )
        }

    @Test
    fun `inactive direction and no op transform mutations do not cancel pending frame details`() =
        runTest {
            val result = CompletableDeferred<FlameGraphFrameDetails>()
            val harness = openedController(detailsProvider = FlameGraphFrameDetailsProvider { result.await() })
            val controller = harness.controller
            controller.openFrameDetails(harness.nodeAt(1, 2))
            runCurrent()
            assertIs<FlameGraphDetailsState.Loading>(controller.state.value.flameGraph.details)
            controller.updateCallTreeDirection(CallStackDirection.INVERTED)
            controller.removeTransformsAfter(0)
            controller.selectTab(ReportTab.STACK_CHART)
            runCurrent()
            val details =
                FlameGraphFrameDetails.Source(Files.createTempFile("aps-source-", ".cpp"), 1, null, listOf("source"))
            result.complete(details)
            runCurrent()
            assertEquals(FlameGraphDetailsState.Ready(details), controller.state.value.flameGraph.details)
            assertTrue(
                harness.loader.started
                    .tryReceive()
                    .isFailure,
            )
        }

    @Test
    fun `stale stack node id pointing at a different function cannot replace inverted selection`() =
        runTest {
            val harness = openedController()
            val controller = harness.controller
            controller.selectTab(ReportTab.CALL_TREE)
            runCurrent()
            harness.mutate { controller.updateCallStackDirection(CallStackDirection.INVERTED) }
            val selected = harness.nodeAt(3, 2)
            val differentFunction = harness.nodeAt(3)
            controller.selectCallNode(selected)
            val reload = async { controller.retryProjection() }
            harness.loader.started.receive().succeed { snapshot ->
                val chart = assertIs<PanelProjection.Ready<StackChartSnapshot>>(snapshot.stackChart).value
                snapshot.copy(
                    stackChart =
                        PanelProjection.Ready(
                            chart.copy(
                                blocks = chart.blocks.map { block -> block.copy(callNodeId = differentFunction) },
                            ),
                        ),
                )
            }
            runCurrent()
            reload.await()
            val report = assertIs<ReportLoadState.Ready>(controller.state.value.loadState).report
            val chart = assertIs<PanelProjection.Ready<StackChartSnapshot>>(report.stackChart).value
            controller.selectStackChartBlock(chart.blocks.single { it.frameId == 2L }.id)
            assertEquals(selected, controller.state.value.workspace.selections.callNodeId)
        }

    @Test
    fun `user timing visibility is a display only workspace setting`() =
        runTest {
            val harness = openedController()
            val before = harness.workspace.state.value.generation
            assertEquals(false, harness.controller.state.value.workspace.stackChartShowUserTimings)
            harness.controller.setStackChartShowUserTimings(true)
            assertTrue(harness.controller.state.value.workspace.stackChartShowUserTimings)
            harness.controller.setStackChartShowUserTimings(false)
            assertEquals(false, harness.controller.state.value.workspace.stackChartShowUserTimings)
            assertEquals(before, harness.workspace.state.value.generation)
            assertTrue(
                harness.loader.started
                    .tryReceive()
                    .isFailure,
            )
        }

    @Test
    fun `report publishes independent user timings rather than filtered marker projection`() =
        runTest {
            val harness = openedController()
            val reloading = async { harness.controller.retryProjection() }
            val unsearched = emptyMarkers()
            val filtered = PanelProjection.Failed("MARKER_QUERY_FAILED", "searched markers unavailable")
            harness.loader.started
                .receive()
                .succeed { it.copy(markers = filtered, userTimings = unsearched) }
            runCurrent()
            reloading.await()
            val report = assertIs<ReportLoadState.Ready>(harness.controller.state.value.loadState).report
            assertEquals(filtered, report.markers)
            assertEquals(unsearched, report.userTimings)
        }

    @Test
    fun `remove transforms after count keeps the prefix and skips no op projections`() =
        runTest {
            val harness = openedController()
            val controller = harness.controller
            val first = CallStackTransform.CollapseResource("/first.so")
            val second = CallStackTransform.CollapseResource("/second.so")
            harness.mutate { controller.applyTransform(first) }
            harness.mutate { controller.applyTransform(second) }
            val generation = harness.workspace.state.value.generation
            controller.removeTransformsAfter(2)
            controller.removeTransformsAfter(Int.MAX_VALUE)
            assertEquals(generation, harness.workspace.state.value.generation)
            assertTrue(
                harness.loader.started
                    .tryReceive()
                    .isFailure,
            )
            harness.mutate { controller.removeTransformsAfter(1) }
            assertEquals(listOf(first), controller.state.value.callStackQuery.transforms)
            harness.mutate { controller.removeTransformsAfter(-1) }
            assertTrue(
                controller.state.value.callStackQuery.transforms
                    .isEmpty(),
            )
            controller.removeTransformsAfter(0)
            assertTrue(
                harness.loader.started
                    .tryReceive()
                    .isFailure,
            )
        }

    private suspend fun TestScope.openedController(
        ignoreCancellation: Boolean = false,
        detailsProvider: FlameGraphFrameDetailsProvider = FlameGraphFrameDetailsResolver(),
    ): Harness {
        val loader = DirectionLoader(ignoreCancellation)
        val workspace = ProfileWorkspaceController(backgroundScope, loader)
        val controller =
            ReportController(
                scope = backgroundScope,
                workspaceController = workspace,
                detailsProvider = detailsProvider,
            )
        val opening = async { controller.openSession(Files.createTempDirectory("aps-direction-")) }
        loader.started.receive().succeed()
        runCurrent()
        opening.await()
        return Harness(this, controller, workspace, loader)
    }

    private class Harness(
        private val scope: TestScope,
        val controller: ReportController,
        val workspace: ProfileWorkspaceController,
        val loader: DirectionLoader,
    ) {
        suspend fun mutate(action: suspend () -> Unit) {
            val mutation = scope.async { action() }
            loader.started.receive().succeed()
            scope.runCurrent()
            mutation.await()
        }

        suspend fun selectTab(
            tab: ReportTab,
            direction: CallStackDirection,
        ) {
            controller.selectTab(tab)
            scope.runCurrent()
            val request = loader.started.receive()
            assertEquals(direction, request.request.callStackAnalysis.direction)
            request.succeed()
            scope.runCurrent()
            assertEquals(tab, controller.state.value.selectedTab)
        }

        fun nodeAt(vararg functions: Long): FlameCallNodeId =
            assertNotNull(
                assertIs<ReportLoadState.Ready>(controller.state.value.loadState)
                    .report.flameGraph.callNodes
                    .findByPath(CallNodePath(functions.map(::FlameFunctionId))),
            )
    }

    private class DirectionLoader(
        private val ignoreCancellation: Boolean,
    ) : ProfileProjectionLoader {
        val started = Channel<PendingDirection>(Channel.UNLIMITED)

        override suspend fun load(
            session: PreparedProfileSession,
            query: ProfileQuery,
        ): ProfileProjectionSnapshot = load(session, ProfileProjectionRequest(query = query))

        override suspend fun load(
            session: PreparedProfileSession,
            request: ProfileProjectionRequest,
        ): ProfileProjectionSnapshot {
            val pending = PendingDirection(request)
            started.send(pending)
            return if (ignoreCancellation) {
                withContext(NonCancellable) { pending.result.await() }
            } else {
                pending.result.await()
            }
        }
    }

    private class PendingDirection(
        val request: ProfileProjectionRequest,
    ) {
        val result = CompletableDeferred<ProfileProjectionSnapshot>()

        fun succeed(transform: (ProfileProjectionSnapshot) -> ProfileProjectionSnapshot = { it }) {
            val table = directionTable()
            val query = request.callStackAnalysis
            val nodes = CallTreeProjector.project(table, query.direction)
            result.complete(
                transform(
                    workspaceSnapshot(request.query).copy(
                        flameGraph =
                            FlameGraphSnapshot(
                                query,
                                nodes,
                                FlameGraphRowProjector.project(nodes, query.direction),
                                10,
                                null,
                                emptyList(),
                            ),
                        stackChart =
                            PanelProjection.Ready(
                                StackChartProjector.project(
                                    table,
                                    query.copy(direction = CallStackDirection.FORWARD),
                                    20,
                                ),
                            ),
                    ),
                ),
            )
        }
    }
}

private fun directionTable(): CallStackTable =
    CallStackTable(
        framesById =
            (1L..3L).associateWith { id ->
                CallStackFrame(id, FlameFunctionId(id), "frame$id", "/lib.so", id, FrameImplementation.NATIVE)
            },
        stacks = listOf(WeightedCallStack(1, 10, 10, "thread", null, null, listOf(1, 2, 3))),
    )
