package com.androidperformancestudio.application

import com.androidperformancestudio.storage.DataQualitySummary
import com.androidperformancestudio.storage.ProfileOverview
import com.androidperformancestudio.storage.ProfileProjectionSnapshot
import com.androidperformancestudio.storage.ProfileQuery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileWorkspaceControllerTest {
    @Test
    fun `opening publishes loading before the initial ready projection`() =
        runTest {
            val harness = WorkspaceHarness(backgroundScope)

            harness.controller.openSession(harness.session)

            val loading = assertIs<ProfileWorkspaceLoadState.Loading>(harness.controller.state.value.loadState)
            assertEquals(harness.session, loading.sessionDirectory)
            assertNull(harness.controller.state.value.snapshot)
            val request = harness.awaitRequest()
            request.succeed()
            runCurrent()

            val state = harness.controller.state.value
            assertIs<ProfileWorkspaceLoadState.Ready>(state.loadState)
            assertEquals(ProfileQuery(), state.query)
            assertEquals(ProfileQuery(), state.snapshot?.query)
        }

    @Test
    fun `refresh keeps the last ready snapshot until the replacement is ready`() =
        runTest {
            val harness = WorkspaceHarness(backgroundScope)
            harness.controller.openSession(harness.session)
            val initial = harness.awaitRequest()
            initial.succeed()
            runCurrent()
            val readySnapshot = harness.controller.state.value.snapshot

            val query = ProfileQuery(threadIds = setOf(101))
            harness.controller.updateQuery(query)

            val refreshing = harness.controller.state.value
            assertIs<ProfileWorkspaceLoadState.Refreshing>(refreshing.loadState)
            assertSame(readySnapshot, refreshing.snapshot)
            assertEquals(query, refreshing.query)
            val replacement = harness.awaitRequest()
            replacement.succeed()
            runCurrent()

            assertIs<ProfileWorkspaceLoadState.Ready>(harness.controller.state.value.loadState)
            assertEquals(
                query,
                harness.controller.state.value.snapshot
                    ?.query,
            )
        }

    @Test
    fun `workspace publishes only the newest ready projection`() =
        runTest {
            val harness = WorkspaceHarness(backgroundScope, suppressCancellation = true)
            harness.controller.openSession(harness.session)
            val old = harness.awaitRequest()
            harness.controller.updateQuery(ProfileQuery(threadIds = setOf(101)))
            val current = harness.awaitRequest()

            old.succeed()
            runCurrent()
            assertFalse(harness.controller.state.value.loadState is ProfileWorkspaceLoadState.Ready)

            current.succeed()
            runCurrent()
            val state = harness.controller.state.value
            assertEquals(setOf(101), state.snapshot?.query?.threadIds)
            assertIs<ProfileWorkspaceLoadState.Ready>(state.loadState)
        }

    @Test
    fun `obsolete loader failure cannot replace a newer ready projection`() =
        runTest {
            val harness = WorkspaceHarness(backgroundScope, suppressCancellation = true)
            harness.controller.openSession(harness.session)
            val old = harness.awaitRequest()
            harness.controller.updateQuery(ProfileQuery(threadIds = setOf(101)))
            val current = harness.awaitRequest()

            old.fail(IOException("obsolete failure"))
            current.succeed()
            runCurrent()

            val state = harness.controller.state.value
            assertIs<ProfileWorkspaceLoadState.Ready>(state.loadState)
            assertEquals(setOf(101), state.snapshot?.query?.threadIds)
        }

    @Test
    fun `loader failure publishes failed state for the current generation`() =
        runTest {
            val harness = WorkspaceHarness(backgroundScope)
            harness.controller.openSession(harness.session)
            val request = harness.awaitRequest()

            request.fail(IOException("broken profile"))
            runCurrent()

            val state = harness.controller.state.value
            val failed = assertIs<ProfileWorkspaceLoadState.Failed>(state.loadState)
            assertEquals(harness.session, failed.sessionDirectory)
            assertEquals("REPORT_SESSION_READ_FAILED", failed.error.code)
            assertNull(state.snapshot)
        }

    @Test
    fun `refresh failure preserves the last ready snapshot`() =
        runTest {
            val harness = WorkspaceHarness(backgroundScope)
            harness.controller.openSession(harness.session)
            val initial = harness.awaitRequest()
            initial.succeed()
            runCurrent()
            val readySnapshot = harness.controller.state.value.snapshot

            harness.controller.updateQuery(ProfileQuery(threadIds = setOf(101)))
            val refresh = harness.awaitRequest()
            refresh.fail(IOException("refresh failed"))
            runCurrent()

            assertIs<ProfileWorkspaceLoadState.Failed>(harness.controller.state.value.loadState)
            assertSame(readySnapshot, harness.controller.state.value.snapshot)
            assertEquals(setOf(101), harness.controller.state.value.query.threadIds)
        }

    @Test
    fun `close session cancels the active request and suppresses late completion`() =
        runTest {
            val harness = WorkspaceHarness(backgroundScope, suppressCancellation = true)
            harness.controller.openSession(harness.session)
            val request = harness.awaitRequest()

            harness.controller.closeSession()
            request.succeed()
            runCurrent()

            val state = harness.controller.state.value
            assertIs<ProfileWorkspaceLoadState.Closed>(state.loadState)
            assertNull(state.sessionDirectory)
            assertNull(state.snapshot)
            assertTrue(request.wasCancelled.await())
        }

    @Test
    fun `closing the controller cancels work and rejects future sessions`() =
        runTest {
            val harness = WorkspaceHarness(backgroundScope, suppressCancellation = true)
            harness.controller.openSession(harness.session)
            val request = harness.awaitRequest()

            harness.controller.close()
            request.succeed()
            runCurrent()

            assertIs<ProfileWorkspaceLoadState.Closed>(harness.controller.state.value.loadState)
            assertTrue(request.wasCancelled.await())
            assertFailsWith<IllegalStateException> { harness.controller.openSession(harness.session) }
        }

    private class WorkspaceHarness(
        scope: kotlinx.coroutines.CoroutineScope,
        suppressCancellation: Boolean = false,
    ) {
        val session: Path = Files.createTempDirectory("aps-workspace-").toAbsolutePath().normalize()
        private val loader = ControllableLoader(suppressCancellation)
        val controller = ProfileWorkspaceController(scope, loader)

        suspend fun awaitRequest(): Request = loader.started.receive()
    }

    private class ControllableLoader(
        private val suppressCancellation: Boolean,
    ) : ProfileProjectionLoader {
        val started = Channel<Request>(Channel.UNLIMITED)

        override suspend fun load(
            sessionDirectory: Path,
            query: ProfileQuery,
        ): ProfileProjectionSnapshot {
            val request = Request(query)
            started.send(request)
            return try {
                request.completion.await()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                request.wasCancelled.complete(true)
                if (!suppressCancellation) throw cancelled
                withContext(NonCancellable) { request.completion.await() }
            } finally {
                if (!currentCoroutineContext().isActive) request.wasCancelled.complete(true)
            }
        }
    }

    private class Request(
        val query: ProfileQuery,
    ) {
        val completion = CompletableDeferred<ProfileProjectionSnapshot>()
        val wasCancelled = CompletableDeferred<Boolean>()

        fun succeed() {
            completion.complete(snapshot(query))
        }

        fun fail(error: Throwable) {
            completion.completeExceptionally(error)
        }
    }
}

private fun snapshot(query: ProfileQuery): ProfileProjectionSnapshot =
    ProfileProjectionSnapshot(
        query = query,
        overview =
            ProfileOverview(
                startNanos = null,
                endNanosInclusive = null,
                sampleCount = 0,
                totalEventWeight = 0,
                processCount = 0,
                threadCount = 0,
                eventTypes = emptyList(),
            ),
        quality =
            DataQualitySummary(
                sampleCount = 0,
                reportedSampleCount = 0,
                lostSampleCount = 0,
                unwindErrorSamples = 0,
                unknownSymbolSamples = 0,
                emptyStackSamples = 0,
                unknownRecords = 0,
                unwindErrors = emptyList(),
            ),
        tracks = emptyList(),
        threads = emptyList(),
        timeline = emptyList(),
        topFunctions = emptyList(),
        forwardCallTree = emptyList(),
    )
