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
import com.androidperformancestudio.storage.CallTreeDirection
import com.androidperformancestudio.storage.ProfileProjectionSnapshot
import com.androidperformancestudio.storage.ProfileQuery
import com.androidperformancestudio.storage.SQLiteSampleStore
import com.androidperformancestudio.storage.TopFunctionSort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
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
                ready.report.flameGraph
                    .single { it.parentId == null }
                    .inclusiveWeight,
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
            controller.updateCallTreeDirection(CallTreeDirection.REVERSE)

            val state = controller.state.value
            val report = assertIs<ReportLoadState.Ready>(state.loadState).report
            assertEquals(1L, report.overview.sampleCount)
            assertEquals(5L, report.overview.totalEventWeight)
            assertEquals(listOf("renderFrame"), report.topFunctions.map { it.symbolName })
            assertEquals("renderFrame", report.callTree.single { it.parentId == null }.symbolName)
            assertEquals(5L, report.flameGraph.single { it.parentId == null }.inclusiveWeight)
            assertEquals(15L, state.filter.startNanosInclusive)
            assertEquals(setOf(101), state.filter.threadIds)

            controller.focusFunction("renderFrame")

            assertEquals(ReportTab.FLAME_GRAPH, controller.state.value.selectedTab)
            assertTrue(
                controller.state.value.highlightedFlameNodeIds
                    .isNotEmpty(),
            )

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
    fun `reverse call tree remains selectable while flame graph remains forward`() =
        runTest {
            val controller = ReportController()
            controller.openSession(indexedSession())
            controller.updateThreads(setOf(101))

            controller.updateCallTreeDirection(CallTreeDirection.REVERSE)

            val report = assertIs<ReportLoadState.Ready>(controller.state.value.loadState).report
            assertEquals("renderFrame", report.callTree.single { it.parentId == null }.symbolName)
            assertEquals("runLoop", report.flameGraph.single { it.parentId == null }.symbolName)
            assertEquals(CallTreeDirection.REVERSE, controller.state.value.callTreeDirection)
        }

    @Test
    fun `selection searches and flame highlights survive report refreshes`() =
        runTest {
            val controller = ReportController()
            controller.openSession(indexedSession())
            controller.focusFunction("renderFrame")
            val highlighted = controller.state.value.highlightedFlameNodeIds
            controller.focusCallTreeFunction("renderFrame")
            controller.selectTab(ReportTab.FLAME_GRAPH)

            controller.updateTimeRange(0, 40)
            controller.updateTopFunctions("render", TopFunctionSort.SAMPLE_COUNT, descending = true)

            val state = controller.state.value
            assertIs<ReportLoadState.Ready>(state.loadState)
            assertEquals(ReportTab.FLAME_GRAPH, state.selectedTab)
            assertEquals("renderFrame", state.flameSearch)
            assertEquals("renderFrame", state.callTreeSearch)
            assertEquals(highlighted, state.highlightedFlameNodeIds)
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
