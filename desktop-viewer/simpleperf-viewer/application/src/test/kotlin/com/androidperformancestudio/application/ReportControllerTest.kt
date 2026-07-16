package com.androidperformancestudio.application

import com.androidperformancestudio.analysis.DiagnosticTarget
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.NormalizedProfileRecord
import com.androidperformancestudio.model.NormalizedSample
import com.androidperformancestudio.model.ProfileExecutionType
import com.androidperformancestudio.model.ProfileFile
import com.androidperformancestudio.model.ProfileFrame
import com.androidperformancestudio.model.ProfileMetadata
import com.androidperformancestudio.model.ProfileThread
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.profileanalysis.AnalysisTimeRange
import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.CallStackTransform
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FlameGraphNavigationCommand
import com.androidperformancestudio.profileanalysis.FlameGraphRowProjector
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.profileanalysis.FrameImplementation
import com.androidperformancestudio.storage.ProfileProjectionRequest
import com.androidperformancestudio.storage.ProfileProjectionSnapshot
import com.androidperformancestudio.storage.ProfileQuery
import com.androidperformancestudio.storage.SQLiteSampleStore
import com.androidperformancestudio.storage.TopFunctionSort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass")
class ReportControllerTest {
    @Test
    fun `opens indexed session into overview timeline tree and flame report`() =
        runTest {
            val session = indexedSession()
            val controller = ReportController(timelineBucketCount = 3)

            controller.openSession(session)

            val ready = assertIs<ReportLoadState.Ready>(controller.state.value.loadState)
            assertEquals(session, ready.report.session.directory)
            assertEquals(3L, ready.report.overview.sampleCount)
            assertEquals(15L, ready.report.overview.totalEventWeight)
            assertEquals(2, ready.report.topThreads.size)
            assertEquals(listOf(3L, 5L, 7L), ready.report.timeline.map { it.eventWeight })
            assertEquals(
                "runLoop",
                ready.report.callTree
                    .single { it.parentId == null }
                    .symbolName,
            )
            assertEquals(
                15L,
                ready.report.flameGraph.totalWeight,
            )
            assertTrue(
                ready.report.session.artifacts
                    .any { it.name == "perf.data" && it.exists },
            )
            assertTrue(ready.report.diagnostics.any { it.ruleId == "data-quality" })
            assertIs<DiagnosticTarget.Function>(
                ready.report.diagnostics
                    .first { it.ruleId == "cpu-hotspot" }
                    .target,
            )
            assertEquals(ReportTab.OVERVIEW, controller.state.value.selectedTab)
        }

    @Test
    fun `time thread top search and tree direction filters recompute linked report`() =
        runTest {
            val controller = ReportController(timelineBucketCount = 2)
            controller.openSession(indexedSession())

            controller.updateTimeRange(15, 30)
            controller.updateThreads(setOf(101))
            controller.updateTopFunctions("render", TopFunctionSort.EXCLUSIVE_WEIGHT, descending = true)
            controller.updateCallStackDirection(CallStackDirection.INVERTED)
            controller.updateFlameSearch("render,run")

            val state = controller.state.value
            val report = assertIs<ReportLoadState.Ready>(state.loadState).report
            assertEquals(1L, report.overview.sampleCount)
            assertEquals(5L, report.overview.totalEventWeight)
            assertEquals(listOf("renderFrame"), report.topFunctions.map { it.symbolName })
            assertEquals("renderFrame", report.callTree.single { it.parentId == null }.symbolName)
            assertEquals(5L, report.flameGraph.totalWeight)
            assertEquals(15L, state.filter.startNanosInclusive)
            assertEquals(setOf(101), state.filter.threadIds)

            assertEquals("render,run", state.flameGraph.query.searchText)
            assertEquals(CallStackDirection.INVERTED, state.callTreeDirection)

            controller.focusCallTreeFunction("renderFrame")

            assertEquals(ReportTab.CALL_TREE, controller.state.value.selectedTab)
            assertEquals("renderFrame", controller.state.value.callTreeSearch)
        }

    @Test
    fun `shows preprocessing failures while retaining the session path`() {
        val controller = ReportController()
        val session = Files.createTempDirectory("aps-report-failure-")
        val error = StudioError(ErrorCategory.CONFIGURATION, "HOST_SIMPLEPERF_NOT_FOUND", "missing")

        controller.showFailure(session, error)

        val failed = assertIs<ReportLoadState.Failed>(controller.state.value.loadState)
        assertEquals(session, failed.sessionDirectory)
        assertEquals(error, failed.error)
    }

    @Test
    fun `suspending report operations return after their corresponding terminal state`() =
        runTest {
            val controller = ReportController()
            val session = indexedSession()

            controller.openSession(session)
            assertIs<ReportLoadState.Ready>(controller.state.value.loadState)

            controller.updateThreads(setOf(101))
            assertIs<ReportLoadState.Ready>(controller.state.value.loadState)

            controller.updateEvents(setOf("cpu-cycles"))
            assertIs<ReportLoadState.Ready>(controller.state.value.loadState)

            val missing = Files.createTempDirectory("aps-report-missing-")
            controller.openSession(missing)
            val failed = assertIs<ReportLoadState.Failed>(controller.state.value.loadState)
            assertEquals(missing, failed.sessionDirectory)
            assertEquals("REPORT_DATABASE_NOT_FOUND", failed.error.code)
        }

    @Test
    fun `session aggregates stay unfiltered while linked aggregates follow the active query`() =
        runTest {
            val controller = ReportController()
            controller.openSession(indexedSession())

            controller.updateThreads(setOf(101))
            controller.updateTimeRange(15, 30)

            val report = assertIs<ReportLoadState.Ready>(controller.state.value.loadState).report
            assertEquals(3L, report.sessionOverview.sampleCount)
            assertEquals(15L, report.sessionOverview.totalEventWeight)
            assertEquals(listOf(101, 102), report.sessionThreads.map { it.threadId }.sorted())
            assertEquals(1L, report.overview.sampleCount)
            assertEquals(5L, report.overview.totalEventWeight)
            assertEquals(listOf(101), report.topThreads.map { it.threadId })
        }

    @Test
    fun `top function search sort direction and limit match the storage query`() =
        runTest {
            val session = indexedSession()
            val controller = ReportController(topFunctionLimit = 1)
            controller.openSession(session)

            controller.updateTopFunctions(
                search = "r",
                sort = TopFunctionSort.EXCLUSIVE_WEIGHT,
                descending = false,
            )

            val expected =
                SQLiteSampleStore.open(session.resolve("profile.sqlite")).use { store ->
                    store.topFunctions(
                        query = ProfileQuery(),
                        limit = 1,
                        search = "r",
                        sort = TopFunctionSort.EXCLUSIVE_WEIGHT,
                        descending = false,
                    )
                }
            val report = assertIs<ReportLoadState.Ready>(controller.state.value.loadState).report
            assertEquals(expected, report.topFunctions)
        }

    @Test
    fun `direction changes call tree and flame graph together`() =
        runTest {
            val controller = ReportController()
            controller.openSession(indexedSession())
            controller.updateThreads(setOf(101))

            controller.updateCallStackDirection(CallStackDirection.INVERTED)

            val report = assertIs<ReportLoadState.Ready>(controller.state.value.loadState).report
            assertEquals("renderFrame", report.callTree.single { it.parentId == null }.symbolName)
            assertEquals(
                report.callTree.map { it.id },
                report.flameGraph.callNodes.ids
                    .toList(),
            )
            assertEquals(CallStackDirection.INVERTED, report.flameGraph.query.direction)
            assertEquals(CallStackDirection.INVERTED, controller.state.value.callTreeDirection)
        }

    @Test
    fun `call tree and flame graph publish one selection without mutating the shared query`() =
        runTest {
            val controller = ReportController()
            controller.openSession(indexedSession())
            val initial = controller.state.value
            val report = assertIs<ReportLoadState.Ready>(initial.loadState).report
            val callTreeNode = report.callTree.single { it.symbolName == "renderFrame" }
            val initialQuery = initial.flameGraph.query

            controller.selectCallNode(FlameCallNodeId(callTreeNode.id))

            val selected = controller.state.value
            assertEquals(FlameCallNodeId(callTreeNode.id), selected.flameGraph.selectedNodeId)
            assertEquals(initialQuery, selected.flameGraph.query)
            assertTrue(
                assertIs<ReportLoadState.Ready>(selected.loadState).report.callTree.any { node ->
                    node.id == selected.flameGraph.selectedNodeId?.value
                },
            )
        }

    @Test
    fun `transforms survive unrelated refresh and removed selection falls back to ancestor`() =
        runTest {
            val controller = ReportController()
            controller.openSession(indexedSession())
            val initial = assertIs<ReportLoadState.Ready>(controller.state.value.loadState).report
            val render = initial.callTree.single { it.symbolName == "renderFrame" }
            val root = initial.callTree.single { it.symbolName == "runLoop" }
            val rootIndex =
                initial.flameGraph.callNodes.ids
                    .indexOf(root.id)
            val rootFrameId = initial.flameGraph.callNodes.frameIds[rootIndex]
            val rootFunction =
                initial.flameGraph.callNodes.framesById
                    .getValue(rootFrameId)
                    .functionId
            controller.selectCallNode(FlameCallNodeId(render.id))
            controller.selectTab(ReportTab.FLAME_GRAPH)

            controller.updateTimeRange(0, 40)
            controller.updateTopFunctions("render", TopFunctionSort.SAMPLE_COUNT, descending = true)
            controller.applyTransform(CallStackTransform.CollapseFunctionSubtree(rootFunction))

            val state = controller.state.value
            assertIs<ReportLoadState.Ready>(state.loadState)
            assertEquals(ReportTab.FLAME_GRAPH, state.selectedTab)
            assertEquals(1, state.flameGraph.query.transforms.size)
            assertEquals(FlameCallNodeId(root.id), state.flameGraph.selectedNodeId)
        }

    @Test
    fun `preview range participates in the semantic projection generation`() =
        runTest {
            val controller = ReportController()
            controller.openSession(indexedSession())

            controller.previewRange(AnalysisTimeRange(15, 25))
            val state =
                controller.state.first { current ->
                    val report = (current.loadState as? ReportLoadState.Ready)?.report
                    report?.flameGraph?.query?.previewRange == AnalysisTimeRange(15, 25)
                }
            val report = assertIs<ReportLoadState.Ready>(state.loadState).report
            assertEquals(AnalysisTimeRange(15, 25), state.flameGraph.query.previewRange)
            assertEquals(5L, report.flameGraph.totalWeight)
            assertEquals(3L, report.overview.sampleCount)
        }

    @Test
    fun `panel transients stay local while commit and undo update authoritative state`() =
        runTest {
            val controller = ReportController()
            controller.openSession(indexedSession())
            val report = assertIs<ReportLoadState.Ready>(controller.state.value.loadState).report
            val selected = report.flameGraph.callNodes.nodeIdAt(0) ?: error("missing root")
            val first = CallStackTransform.CollapseResource("/system/lib64/libui.so")
            val second =
                CallStackTransform.FocusFunction(
                    report.flameGraph.callNodes
                        .frameAt(0)!!
                        .functionId,
                )

            controller.hoverCallNode(selected)
            controller.openCallNodeContext(selected)
            assertEquals(selected, controller.state.value.flameGraph.hoveredNodeId)
            assertEquals(selected, controller.state.value.flameGraph.contextNodeId)

            controller.selectCallNode(selected)
            assertEquals(null, controller.state.value.flameGraph.hoveredNodeId)
            assertEquals(null, controller.state.value.flameGraph.contextNodeId)

            controller.previewRange(AnalysisTimeRange(1, 10))
            runCurrent()
            controller.applyTransform(first)
            controller.applyTransform(second)
            controller.undoLastTransform()
            assertEquals(listOf(first), controller.state.value.flameGraph.query.transforms)

            controller.updateTimeRange(0, 40)
            assertEquals(null, controller.state.value.flameGraph.query.previewRange)
        }

    @Test
    fun `opening another profile clears call stack query and transient state`() =
        runTest {
            val controller = ReportController()
            controller.openSession(indexedSession())
            val report = assertIs<ReportLoadState.Ready>(controller.state.value.loadState).report
            val selected = FlameCallNodeId(report.callTree.first().id)
            val transform = CallStackTransform.CollapseResource("/missing.so")
            controller.updateFlameSearch("render")
            controller.applyTransform(transform)
            controller.selectCallNode(selected)

            controller.openSession(indexedSession())

            val state = controller.state.value
            assertEquals("", state.flameGraph.query.searchText)
            assertEquals(emptyList(), state.flameGraph.query.transforms)
            assertEquals(null, state.flameGraph.selectedNodeId)
            assertEquals(null, state.flameGraph.hoveredNodeId)
            assertEquals(null, state.flameGraph.contextNodeId)
        }

    @Test
    fun `repeated navigation commands advance authoritative selection without recomposition or projection`() =
        runTest {
            val loader = ControlledRequestLoader()
            val workspace = ProfileWorkspaceController(backgroundScope, loader)
            val controller = ReportController(scope = backgroundScope, workspaceController = workspace)
            val opening = async { controller.openSession(Files.createTempDirectory("aps-navigation-open-")) }
            val initial = loader.started.receive()
            initial.succeed(flameSnapshot(initial.request, includeGrandchild = true))
            runCurrent()
            opening.await()
            controller.selectCallNode(FlameCallNodeId(ROOT_NODE_ID))

            val first = controller.navigateCallNode(FlameGraphNavigationCommand.WIDEST_CHILD)
            val second = controller.navigateCallNode(FlameGraphNavigationCommand.WIDEST_CHILD)

            assertEquals(FlameCallNodeId(CHILD_NODE_ID), first)
            assertEquals(FlameCallNodeId(GRANDCHILD_NODE_ID), second)
            assertEquals(second, controller.state.value.flameGraph.selectedNodeId)
            assertTrue(loader.started.tryReceive().isFailure, "local navigation must not request a projection")
        }

    @Test
    fun `retry projection resubmits the unchanged authoritative request`() =
        runTest {
            val loader = ControlledRequestLoader()
            val workspace = ProfileWorkspaceController(backgroundScope, loader)
            val controller = ReportController(scope = backgroundScope, workspaceController = workspace)
            val opening = async { controller.openSession(Files.createTempDirectory("aps-retry-projection-")) }
            val initial = loader.started.receive()
            initial.succeed(flameSnapshot(initial.request))
            runCurrent()
            opening.await()

            val retry = async { controller.retryProjection() }
            val retried = loader.started.receive()
            assertEquals(initial.request, retried.request)
            retried.succeed(flameSnapshot(retried.request))
            runCurrent()
            retry.await()

            assertIs<ReportLoadState.Ready>(controller.state.value.loadState)
        }

    @Test
    fun `leaving flame graph and navigating clear panel-only hover and context`() =
        runTest {
            val loader = ControlledRequestLoader()
            val workspace = ProfileWorkspaceController(backgroundScope, loader)
            val controller = ReportController(scope = backgroundScope, workspaceController = workspace)
            val opening = async { controller.openSession(Files.createTempDirectory("aps-context-lifecycle-")) }
            val initial = loader.started.receive()
            initial.succeed(flameSnapshot(initial.request, includeGrandchild = true))
            runCurrent()
            opening.await()
            val root = FlameCallNodeId(ROOT_NODE_ID)
            val child = FlameCallNodeId(CHILD_NODE_ID)

            controller.selectTab(ReportTab.FLAME_GRAPH)
            controller.hoverCallNode(root)
            controller.openCallNodeContext(root)
            controller.selectTab(ReportTab.TIMELINE)
            assertEquals(null, controller.state.value.flameGraph.hoveredNodeId)
            assertEquals(null, controller.state.value.flameGraph.contextNodeId)

            controller.selectTab(ReportTab.FLAME_GRAPH)
            assertEquals(null, controller.state.value.flameGraph.hoveredNodeId)
            assertEquals(null, controller.state.value.flameGraph.contextNodeId)
            controller.selectCallNode(root)
            controller.hoverCallNode(root)
            controller.openCallNodeContext(root)
            assertEquals(child, controller.navigateCallNode(FlameGraphNavigationCommand.WIDEST_CHILD))
            assertEquals(null, controller.state.value.flameGraph.hoveredNodeId)
            assertEquals(null, controller.state.value.flameGraph.contextNodeId)
        }

    @Test
    fun `preview actor bounds interleaved loads and drains to the latest range`() =
        runTest {
            val loader = ControlledRequestLoader()
            val workspace = ProfileWorkspaceController(backgroundScope, loader)
            val controller = ReportController(scope = backgroundScope, workspaceController = workspace)
            val opening = async { controller.openSession(Files.createTempDirectory("aps-preview-latest-")) }
            val initial = loader.started.receive()
            initial.succeed(flameSnapshot(initial.request))
            runCurrent()
            opening.await()

            controller.previewRange(AnalysisTimeRange(10, 20))
            advanceTimeBy(16)
            runCurrent()
            val first = loader.started.receive()
            assertEquals(AnalysisTimeRange(10, 20), first.request.callStackAnalysis.previewRange)

            controller.previewRange(AnalysisTimeRange(20, 30))
            advanceTimeBy(16)
            runCurrent()
            controller.previewRange(AnalysisTimeRange(30, 40))
            advanceTimeBy(16)
            runCurrent()
            assertTrue(loader.started.tryReceive().isFailure, "only one preview load may be active")

            first.succeed(flameSnapshot(first.request))
            runCurrent()
            advanceTimeBy(16)
            runCurrent()

            val latest = loader.started.receive()
            assertEquals(AnalysisTimeRange(30, 40), latest.request.callStackAnalysis.previewRange)
            assertTrue(loader.started.tryReceive().isFailure, "pending preview values must be conflated")
            latest.succeed(flameSnapshot(latest.request))
            runCurrent()
            assertEquals(AnalysisTimeRange(30, 40), controller.state.value.flameGraph.query.previewRange)
        }

    @Test
    fun `range commit atomically clears preview and wins over a late preview result`() =
        runTest {
            val loader = ControlledRequestLoader()
            val workspace = ProfileWorkspaceController(backgroundScope, loader)
            val controller = ReportController(scope = backgroundScope, workspaceController = workspace)
            val opening = async { controller.openSession(Files.createTempDirectory("aps-preview-commit-")) }
            val initial = loader.started.receive()
            initial.succeed(flameSnapshot(initial.request))
            runCurrent()
            opening.await()

            controller.previewRange(AnalysisTimeRange(10, 20))
            runCurrent()
            val preview = loader.started.receive()
            val commit = async { controller.commitRange(100, 200) }
            runCurrent()
            val committed = loader.started.receive()

            assertEquals(100, committed.request.query.startNanosInclusive)
            assertEquals(200, committed.request.query.endNanosExclusive)
            assertEquals(null, committed.request.callStackAnalysis.previewRange)
            preview.succeed(flameSnapshot(preview.request))
            committed.succeed(flameSnapshot(committed.request))
            runCurrent()
            commit.await()

            assertEquals(100, controller.state.value.filter.startNanosInclusive)
            assertEquals(200, controller.state.value.filter.endNanosExclusive)
            assertEquals(null, controller.state.value.flameGraph.query.previewRange)
        }

    @Test
    fun `preview cancellation atomically clears state and wins over a late preview result`() =
        runTest {
            val loader = ControlledRequestLoader()
            val workspace = ProfileWorkspaceController(backgroundScope, loader)
            val controller = ReportController(scope = backgroundScope, workspaceController = workspace)
            val opening = async { controller.openSession(Files.createTempDirectory("aps-preview-cancel-")) }
            val initial = loader.started.receive()
            initial.succeed(flameSnapshot(initial.request))
            runCurrent()
            opening.await()

            controller.previewRange(AnalysisTimeRange(10, 20))
            runCurrent()
            val preview = loader.started.receive()
            val cancellation = async { controller.cancelPreview() }
            runCurrent()
            val cleared = loader.started.receive()

            assertEquals(null, cleared.request.callStackAnalysis.previewRange)
            preview.succeed(flameSnapshot(preview.request))
            cleared.succeed(flameSnapshot(cleared.request))
            runCurrent()
            cancellation.await()

            assertEquals(null, controller.state.value.flameGraph.query.previewRange)
        }

    @Test
    fun `preview attempt inside range commit critical section is rejected`() =
        runTest {
            assertPreviewAttemptDuringSemanticMutationIsRejected { controller ->
                controller.commitRange(100, 200)
            }
        }

    @Test
    fun `preview attempt inside cancellation critical section is rejected`() =
        runTest {
            assertPreviewAttemptDuringSemanticMutationIsRejected(ReportController::cancelPreview)
        }

    @Test
    fun `opening another profile cancels pending preview work`() =
        runTest {
            val loader = ControlledRequestLoader()
            val workspace = ProfileWorkspaceController(backgroundScope, loader)
            val controller = ReportController(scope = backgroundScope, workspaceController = workspace)
            val opening = async { controller.openSession(Files.createTempDirectory("aps-preview-first-")) }
            val initial = loader.started.receive()
            initial.succeed(flameSnapshot(initial.request))
            runCurrent()
            opening.await()

            controller.previewRange(AnalysisTimeRange(10, 20))
            runCurrent()
            val obsolete = loader.started.receive()
            val nextOpen = async { controller.openSession(Files.createTempDirectory("aps-preview-second-")) }
            runCurrent()
            val next = loader.started.receive()
            assertEquals(null, next.request.callStackAnalysis.previewRange)
            obsolete.succeed(flameSnapshot(obsolete.request))
            next.succeed(flameSnapshot(next.request))
            runCurrent()
            nextOpen.await()

            assertEquals(null, controller.state.value.flameGraph.query.previewRange)
            assertEquals("", controller.state.value.flameGraph.query.searchText)
        }

    @Test
    fun `semantic submission and publication cannot retain invisible hover or context nodes`() =
        runTest {
            val loader = ControlledRequestLoader()
            val workspace = ProfileWorkspaceController(backgroundScope, loader)
            val controller = ReportController(scope = backgroundScope, workspaceController = workspace)
            val opening = async { controller.openSession(Files.createTempDirectory("aps-transient-sanitize-")) }
            val initial = loader.started.receive()
            initial.succeed(flameSnapshot(initial.request, includeChild = true))
            runCurrent()
            opening.await()
            val child = FlameCallNodeId(CHILD_NODE_ID)
            controller.hoverCallNode(child)
            controller.openCallNodeContext(child)

            val refresh = async { controller.updateFlameSearch("root-only") }
            runCurrent()
            val pending = loader.started.receive()
            assertEquals(null, controller.state.value.flameGraph.hoveredNodeId)
            assertEquals(null, controller.state.value.flameGraph.contextNodeId)

            controller.hoverCallNode(child)
            controller.openCallNodeContext(child)
            pending.succeed(flameSnapshot(pending.request, includeChild = false))
            runCurrent()
            refresh.await()

            assertEquals(null, controller.state.value.flameGraph.hoveredNodeId)
            assertEquals(null, controller.state.value.flameGraph.contextNodeId)
        }

    @Test
    fun `close releases the owned workspace and rejects later use`() =
        runTest {
            val controller = ReportController()
            controller.openSession(indexedSession())

            controller.close()

            assertIs<ReportLoadState.Closed>(controller.state.value.loadState)
            assertFailsWith<IllegalStateException> { controller.openSession(indexedSession()) }
        }

    @Test
    fun `superseded suspending load returns by cancellation while the newest load completes`() =
        runTest {
            val started = Channel<PendingProjection>(Channel.UNLIMITED)
            val loader =
                ProfileProjectionLoader { _, query ->
                    PendingProjection(query).also { started.send(it) }.result.await()
                }
            val workspace = ProfileWorkspaceController(backgroundScope, loader)
            val controller = ReportController(scope = backgroundScope, workspaceController = workspace)

            val first = backgroundScope.async { controller.openSession(Files.createTempDirectory("aps-first-")) }
            started.receive()
            val second = backgroundScope.async { controller.openSession(Files.createTempDirectory("aps-second-")) }
            val newest = started.receive()
            runCurrent()

            assertTrue(first.isCancelled, "the superseded suspend call must not wait forever")
            newest.result.complete(workspaceSnapshot(newest.query))
            runCurrent()
            second.await()
            assertIs<ReportLoadState.Ready>(controller.state.value.loadState)
        }

    @Test
    fun `delayed obsolete terminal mapping cannot overwrite the newest report`() =
        runTest {
            val firstSession = Files.createTempDirectory("aps-delayed-first-").toAbsolutePath().normalize()
            val newestSession = Files.createTempDirectory("aps-delayed-newest-").toAbsolutePath().normalize()
            val mappingStarted = CompletableDeferred<Unit>()
            val releaseMapping = CompletableDeferred<Unit>()
            val summaries =
                ReportSessionSummaryLoader { directory ->
                    if (directory == firstSession) {
                        mappingStarted.complete(Unit)
                        releaseMapping.await()
                    }
                    ReportSessionSummary(directory.fileName.toString(), directory, emptyMap(), emptyList())
                }
            val started = Channel<PendingProjection>(Channel.UNLIMITED)
            val loader =
                ProfileProjectionLoader { _, query ->
                    PendingProjection(query).also { started.send(it) }.result.await()
                }
            val workspace = ProfileWorkspaceController(backgroundScope, loader)
            val controller =
                ReportController(
                    scope = backgroundScope,
                    workspaceController = workspace,
                    sessionSummaryLoader = summaries,
                )

            val obsolete = backgroundScope.async { controller.openSession(firstSession) }
            started.receive().result.complete(workspaceSnapshot(ProfileQuery()))
            runCurrent()
            mappingStarted.await()

            val newest = backgroundScope.async { controller.openSession(newestSession) }
            started.receive().result.complete(workspaceSnapshot(ProfileQuery()))
            runCurrent()
            releaseMapping.complete(Unit)
            runCurrent()

            assertTrue(obsolete.isCancelled)
            newest.await()
            val report = assertIs<ReportLoadState.Ready>(controller.state.value.loadState).report
            assertEquals(newestSession, report.session.directory)
        }

    @Test
    fun `overlapping semantic mutations atomically submit the complete newest request`() =
        runTest {
            val loader = ControlledRequestLoader()
            val workspace = ProfileWorkspaceController(backgroundScope, loader)
            val controller = ReportController(scope = backgroundScope, workspaceController = workspace)
            val opening = async { controller.openSession(Files.createTempDirectory("aps-serialized-open-")) }
            val initial = loader.started.receive()
            initial.succeed(flameSnapshot(initial.request))
            runCurrent()
            opening.await()

            val search = async { controller.updateFlameSearch("render") }
            val searchRequest = loader.started.receive()
            assertEquals("render", searchRequest.request.callStackAnalysis.searchText)
            val collapse = CallStackTransform.CollapseResource("/system/lib64/libui.so")
            val transform = async { controller.applyTransform(collapse) }
            runCurrent()

            val transformRequest = loader.started.receive()
            assertEquals("render", transformRequest.request.callStackAnalysis.searchText)
            assertEquals(listOf(collapse), transformRequest.request.callStackAnalysis.transforms)
            assertTrue(search.isCancelled, "the complete newest request must supersede the prior generation")
            transformRequest.succeed(flameSnapshot(transformRequest.request))
            runCurrent()
            transform.await()
        }

    @Test
    fun `selection racing publication always resolves inside the published graph`() =
        runTest {
            val loader = ControlledRequestLoader()
            val workspace = ProfileWorkspaceController(backgroundScope, loader)
            val controller = ReportController(scope = backgroundScope, workspaceController = workspace)
            val opening = async { controller.openSession(Files.createTempDirectory("aps-selection-open-")) }
            val initial = loader.started.receive()
            initial.succeed(flameSnapshot(initial.request, includeChild = true))
            runCurrent()
            opening.await()

            val refresh = async { controller.updateFlameSearch("run") }
            val pending = loader.started.receive()
            controller.selectCallNode(FlameCallNodeId(CHILD_NODE_ID))
            pending.succeed(flameSnapshot(pending.request, includeChild = false))
            runCurrent()
            refresh.await()

            val state = controller.state.value
            val report = assertIs<ReportLoadState.Ready>(state.loadState).report
            val selected = state.flameGraph.selectedNodeId
            assertEquals(FlameCallNodeId(ROOT_NODE_ID), selected)
            assertTrue(
                report.flameGraph.callNodes.ids
                    .contains(checkNotNull(selected).value),
            )
        }

    @Test
    fun `focus function defers tab and query mutation so a racing open wins atomically`() =
        runTest {
            val loader = ControlledRequestLoader()
            val workspace = ProfileWorkspaceController(backgroundScope, loader)
            val controller = ReportController(scope = backgroundScope, workspaceController = workspace)
            val firstOpen = async { controller.openSession(Files.createTempDirectory("aps-focus-first-")) }
            val initial = loader.started.receive()
            initial.succeed(flameSnapshot(initial.request))
            runCurrent()
            firstOpen.await()

            controller.focusFunction("old-profile-only")
            assertEquals(ReportTab.OVERVIEW, controller.state.value.selectedTab)
            assertEquals("", controller.state.value.flameGraph.query.searchText)
            val secondOpen =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.openSession(Files.createTempDirectory("aps-focus-second-"))
                }
            runCurrent()
            val second = loader.started.receive()
            assertEquals("", second.request.callStackAnalysis.searchText)
            second.succeed(flameSnapshot(second.request))
            runCurrent()
            secondOpen.await()
            runCurrent()

            assertEquals("", controller.state.value.flameGraph.query.searchText)
            assertEquals(ReportTab.OVERVIEW, controller.state.value.selectedTab)
        }

    @Test
    fun `opening frame details publishes source details without recomputing the graph`() =
        runTest {
            val loader = ControlledRequestLoader()
            val workspace = ProfileWorkspaceController(backgroundScope, loader)
            val captured = Channel<FlameGraphFrameDetailsRequest>(Channel.UNLIMITED)
            val detail = CompletableDeferred<FlameGraphFrameDetails>()
            val controller =
                ReportController(
                    scope = backgroundScope,
                    workspaceController = workspace,
                    detailsProvider =
                        FlameGraphFrameDetailsProvider { request ->
                            captured.send(request)
                            detail.await()
                        },
                )
            val opening = async { controller.openSession(Files.createTempDirectory("aps-details-open-")) }
            val initial = loader.started.receive()
            initial.succeed(flameSnapshot(initial.request, includeChild = true))
            runCurrent()
            opening.await()

            controller.openFrameDetails(FlameCallNodeId(CHILD_NODE_ID))
            runCurrent()

            val loading = assertIs<FlameGraphDetailsState.Loading>(controller.state.value.flameGraph.details)
            assertEquals(FlameCallNodeId(CHILD_NODE_ID), loading.nodeId)
            val request = captured.receive()
            assertEquals("renderFrame", request.function)
            assertEquals("/system/lib64/libui.so", request.resource)
            assertEquals(0x20, request.address)

            detail.complete(
                FlameGraphFrameDetails.Source(Path.of("Render.cpp"), 2, null, listOf("one", "two")),
            )
            runCurrent()

            val ready = assertIs<FlameGraphDetailsState.Ready>(controller.state.value.flameGraph.details)
            assertIs<FlameGraphFrameDetails.Source>(ready.details)
            assertTrue(loader.started.tryReceive().isFailure, "details lookup must not submit a projection")

            controller.closeFrameDetails()

            assertEquals(FlameGraphDetailsState.Closed, controller.state.value.flameGraph.details)
        }

    @Test
    fun `stale frame details cannot publish after graph generation changes`() =
        runTest {
            val loader = ControlledRequestLoader()
            val workspace = ProfileWorkspaceController(backgroundScope, loader)
            val detail = CompletableDeferred<FlameGraphFrameDetails>()
            val controller =
                ReportController(
                    scope = backgroundScope,
                    workspaceController = workspace,
                    detailsProvider =
                        FlameGraphFrameDetailsProvider {
                            withContext(NonCancellable) { detail.await() }
                        },
                )
            val opening = async { controller.openSession(Files.createTempDirectory("aps-details-stale-")) }
            val initial = loader.started.receive()
            initial.succeed(flameSnapshot(initial.request, includeChild = true))
            runCurrent()
            opening.await()

            controller.openFrameDetails(FlameCallNodeId(CHILD_NODE_ID))
            runCurrent()
            assertIs<FlameGraphDetailsState.Loading>(controller.state.value.flameGraph.details)

            val mutation = async { controller.updateFlameSearch("run") }
            val newestProjection = loader.started.receive()
            detail.complete(
                FlameGraphFrameDetails.Source(Path.of("Render.cpp"), 1, null, listOf("stale")),
            )
            runCurrent()

            assertEquals(FlameGraphDetailsState.Closed, controller.state.value.flameGraph.details)
            newestProjection.succeed(flameSnapshot(newestProjection.request, includeChild = false))
            runCurrent()
            mutation.await()
            assertEquals(FlameGraphDetailsState.Closed, controller.state.value.flameGraph.details)
        }

    @Test
    fun `focus function changes only the tab when its projection request is already active`() =
        runTest {
            val loader = ControlledRequestLoader()
            val workspace = ProfileWorkspaceController(backgroundScope, loader)
            val controller = ReportController(scope = backgroundScope, workspaceController = workspace)
            val opening = async { controller.openSession(Files.createTempDirectory("aps-focus-tab-only-")) }
            val initial = loader.started.receive()
            initial.succeed(flameSnapshot(initial.request))
            runCurrent()
            opening.await()

            val search = async { controller.updateFlameSearch("render") }
            val searchRequest = loader.started.receive()
            searchRequest.succeed(flameSnapshot(searchRequest.request))
            runCurrent()
            search.await()
            val generationBeforeFocus = workspace.state.value.generation

            controller.focusFunction("render")
            runCurrent()

            assertEquals(ReportTab.FLAME_GRAPH, controller.state.value.selectedTab)
            assertEquals("render", controller.state.value.flameGraph.query.searchText)
            assertEquals(generationBeforeFocus, workspace.state.value.generation)
            assertTrue(loader.started.tryReceive().isFailure, "tab-only focus must not submit another projection")
        }

    @Test
    fun `malformed metadata fails refresh without losing last ready report or killing collection`() =
        runTest {
            val session = indexedSession()
            val controller = ReportController()
            controller.openSession(session)
            val lastReady = assertIs<ReportLoadState.Ready>(controller.state.value.loadState).report
            Files.write(session.resolve("session.properties"), byteArrayOf(0xc3.toByte(), 0x28))

            controller.updateThreads(setOf(101))

            val failed = assertIs<ReportLoadState.Failed>(controller.state.value.loadState)
            assertEquals("REPORT_SESSION_READ_FAILED", failed.error.code)
            assertEquals(lastReady, controller.state.value.lastReadyReport)

            session.resolve("session.properties").writeText("status=COMPLETED\n")
            controller.updateThreads(emptySet())
            assertIs<ReportLoadState.Ready>(controller.state.value.loadState)
        }

    private data class PendingProjection(
        val query: ProfileQuery,
        val result: CompletableDeferred<ProfileProjectionSnapshot> = CompletableDeferred(),
    )

    private class ControlledRequestLoader : ProfileProjectionLoader {
        val started = Channel<PendingRequest>(Channel.UNLIMITED)

        override suspend fun load(
            session: PreparedProfileSession,
            query: ProfileQuery,
        ): ProfileProjectionSnapshot = load(session, ProfileProjectionRequest(query = query))

        override suspend fun load(
            session: PreparedProfileSession,
            request: ProfileProjectionRequest,
        ): ProfileProjectionSnapshot = PendingRequest(request).also { started.send(it) }.result.await()
    }

    private suspend fun kotlinx.coroutines.test.TestScope.assertPreviewAttemptDuringSemanticMutationIsRejected(
        mutation: suspend (ReportController) -> Unit,
    ) {
        val loader = ControlledRequestLoader()
        val workspace = ProfileWorkspaceController(backgroundScope, loader)
        val controller = ReportController(scope = backgroundScope, workspaceController = workspace)
        val opening = async { controller.openSession(Files.createTempDirectory("aps-preview-critical-")) }
        val initial = loader.started.receive()
        initial.succeed(flameSnapshot(initial.request))
        runCurrent()
        opening.await()

        controller.previewRange(AnalysisTimeRange(10, 20))
        advanceTimeBy(16)
        runCurrent()
        val activePreview = loader.started.receive()
        activePreview.succeed(flameSnapshot(activePreview.request))
        runCurrent()

        val mutationEntered = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        controller.afterPreviewInvalidatedForTest = {
            mutationEntered.countDown()
            check(releaseMutation.await(5, TimeUnit.SECONDS))
        }
        val semanticMutation = async(Dispatchers.Default) { mutation(controller) }
        assertTrue(mutationEntered.await(5, TimeUnit.SECONDS), "semantic mutation did not reach the deterministic gate")

        val previewAttempt = async(Dispatchers.Default) { controller.previewRange(AnalysisTimeRange(30, 40)) }
        previewAttempt.await()
        releaseMutation.countDown()
        controller.afterPreviewInvalidatedForTest = null

        val authoritative = loader.started.receive()
        assertEquals(null, authoritative.request.callStackAnalysis.previewRange)
        authoritative.succeed(flameSnapshot(authoritative.request))
        runCurrent()
        semanticMutation.await()
        advanceTimeBy(32)
        runCurrent()

        assertEquals(null, controller.state.value.flameGraph.query.previewRange)
        assertTrue(loader.started.tryReceive().isFailure, "rejected preview must not submit after mutation completion")
    }

    private data class PendingRequest(
        val request: ProfileProjectionRequest,
        val result: CompletableDeferred<ProfileProjectionSnapshot> = CompletableDeferred(),
    ) {
        fun succeed(snapshot: ProfileProjectionSnapshot) {
            result.complete(snapshot)
        }
    }

    private fun indexedSession(): java.nio.file.Path {
        val session = Files.createTempDirectory("aps-report-")
        session.resolve("perf.data").writeText("raw")
        session.resolve("simpleperf.protobuf").writeText("protobuf")
        session.resolve("session.properties").writeText("status=COMPLETED\nserial=device-1\n")
        SQLiteSampleStore.open(session.resolve("profile.sqlite")).use { store ->
            store.importRecords(
                sequenceOf(
                    NormalizedProfileRecord.Metadata(
                        ProfileMetadata(
                            eventTypes = listOf("cpu-cycles"),
                            appPackageName = "com.example.app",
                            appType = "profileable",
                            androidSdkVersion = "36",
                            androidBuildType = "userdebug",
                            traceOffCpu = false,
                        ),
                    ),
                    NormalizedProfileRecord.File(
                        ProfileFile(7, "/system/lib64/libui.so", listOf("runLoop", "renderFrame"), emptyList()),
                    ),
                    NormalizedProfileRecord.Thread(ProfileThread(100, 101, "RenderThread")),
                    NormalizedProfileRecord.Thread(ProfileThread(100, 102, "worker")),
                    sample(10, 101, 3, listOf(frame(1, "renderFrame", 0x20), frame(0, "runLoop", 0x10))),
                    sample(20, 101, 5, listOf(frame(1, "renderFrame", 0x20), frame(0, "runLoop", 0x10))),
                    sample(30, 102, 7, listOf(frame(0, "runLoop", 0x10))),
                ),
            )
        }
        return session
    }

    private fun sample(
        timestamp: Long,
        threadId: Int,
        weight: Long,
        frames: List<ProfileFrame>,
    ): NormalizedProfileRecord.Sample =
        NormalizedProfileRecord.Sample(
            NormalizedSample(
                timestamp,
                100,
                threadId,
                if (threadId == 101) "RenderThread" else "worker",
                "cpu-cycles",
                weight,
                frames,
                null,
            ),
        )

    private fun frame(
        symbolId: Int,
        name: String,
        address: Long,
    ): ProfileFrame = ProfileFrame(address, 7, symbolId, "/system/lib64/libui.so", name, ProfileExecutionType.NATIVE)
}

private const val ROOT_NODE_ID = 101L
private const val CHILD_NODE_ID = 202L
private const val GRANDCHILD_NODE_ID = 303L

private fun flameSnapshot(
    request: ProfileProjectionRequest,
    includeChild: Boolean = true,
    includeGrandchild: Boolean = false,
): ProfileProjectionSnapshot {
    val rootFrame = testFlameFrame(frameId = 1, functionId = 11, symbolName = "runLoop", address = 0x10)
    val childFrame = testFlameFrame(frameId = 2, functionId = 22, symbolName = "renderFrame", address = 0x20)
    val grandchildFrame = testFlameFrame(frameId = 3, functionId = 33, symbolName = "drawFrame", address = 0x30)
    val ids =
        when {
            includeGrandchild -> longArrayOf(ROOT_NODE_ID, CHILD_NODE_ID, GRANDCHILD_NODE_ID)
            includeChild -> longArrayOf(ROOT_NODE_ID, CHILD_NODE_ID)
            else -> longArrayOf(ROOT_NODE_ID)
        }
    val nodes =
        CallNodeTable(
            ids = ids,
            parentIndexes = testParentIndexes(ids.size),
            frameIds = LongArray(ids.size) { it + 1L },
            depths = IntArray(ids.size) { it },
            inclusiveWeights = LongArray(ids.size) { index -> 10L - index * 2L },
            selfWeights = LongArray(ids.size) { index -> if (index == ids.lastIndex) 6L else 2L },
            sampleCounts = LongArray(ids.size) { index -> 2L - index.coerceAtMost(1) },
            threadCounts = IntArray(ids.size) { 1 },
            categories = List(ids.size) { "User" },
            framesById = mapOf(1L to rootFrame, 2L to childFrame, 3L to grandchildFrame),
        )
    val query: CallStackAnalysisQuery = request.callStackAnalysis
    return workspaceSnapshot(request.query).copy(
        flameGraph =
            FlameGraphSnapshot(
                query = query,
                callNodes = nodes,
                rows = FlameGraphRowProjector.project(nodes, query.direction),
                totalWeight = 10,
                emptyReason = null,
                invalidTransforms = emptyList(),
            ),
    )
}

private fun testParentIndexes(size: Int): IntArray =
    when (size) {
        3 -> intArrayOf(-1, 0, 1)
        2 -> intArrayOf(-1, 0)
        else -> intArrayOf(-1)
    }

private fun testFlameFrame(
    frameId: Long,
    functionId: Long,
    symbolName: String,
    address: Long,
): CallStackFrame =
    CallStackFrame(
        frameId = frameId,
        functionId = FlameFunctionId(functionId),
        symbolName = symbolName,
        resource = "/system/lib64/libui.so",
        virtualAddress = address,
        implementation = FrameImplementation.NATIVE,
    )
