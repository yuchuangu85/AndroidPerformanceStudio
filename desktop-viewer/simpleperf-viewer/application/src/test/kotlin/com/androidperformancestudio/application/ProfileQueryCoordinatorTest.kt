package com.androidperformancestudio.application

import com.androidperformancestudio.storage.DataQualitySummary
import com.androidperformancestudio.storage.ProfileOverview
import com.androidperformancestudio.storage.ProfileProjectionSnapshot
import com.androidperformancestudio.storage.ProfileQuery
import com.androidperformancestudio.storage.SQLiteSampleStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileQueryCoordinatorTest {
    @Test
    fun `new query cancels the previous load and only publishes its generation`() =
        runTest {
            val loader = ControllableProjectionLoader()
            val coordinator = ProfileQueryCoordinator(this, loader)
            val result =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.results.first()
                }

            coordinator.submit(SESSION, ProfileGeneration(1), ProfileQuery(threadIds = setOf(1)))
            runCurrent()
            loader.awaitStarted(1)

            coordinator.submit(SESSION, ProfileGeneration(2), ProfileQuery(threadIds = setOf(2)))
            runCurrent()
            loader.awaitCancelled(1)
            loader.awaitStarted(2)

            loader.complete(1, snapshotFor(1))
            loader.complete(2, snapshotFor(2))
            runCurrent()

            assertEquals(ProfileGeneration(2), result.await().generation)
            assertEquals(
                setOf(2),
                result
                    .await()
                    .snapshot.query.threadIds,
            )
        }

    @Test
    fun `cancelled load that suppresses cancellation cannot publish after a newer submission`() =
        runTest {
            val loader = ControllableProjectionLoader(ignoreCancellationFor = setOf(1))
            val coordinator = ProfileQueryCoordinator(this, loader)
            val result =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.results.first()
                }

            coordinator.submit(SESSION, ProfileGeneration(1), ProfileQuery(threadIds = setOf(1)))
            runCurrent()
            loader.awaitStarted(1)
            coordinator.submit(SESSION, ProfileGeneration(2), ProfileQuery(threadIds = setOf(2)))
            runCurrent()
            loader.awaitStarted(2)

            loader.complete(1, snapshotFor(1))
            runCurrent()
            loader.awaitCancelled(1)
            assertFalse(result.isCompleted, "the obsolete generation must not reach the active collector")

            loader.complete(2, snapshotFor(2))
            runCurrent()

            assertEquals(GeneratedProjection(ProfileGeneration(2), snapshotFor(2)), result.await())
        }

    @Test
    fun `submit freezes mutable query collections before asynchronous loading`() =
        runTest {
            val threadIds = mutableSetOf(1)
            val eventTypes = mutableSetOf("cpu-cycles")
            val loader = ControllableProjectionLoader()
            val coordinator = ProfileQueryCoordinator(this, loader)
            val result =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.results.first()
                }

            coordinator.submit(
                SESSION,
                ProfileGeneration(1),
                ProfileQuery(threadIds = threadIds, eventTypes = eventTypes),
            )
            threadIds += 2
            eventTypes += "instructions"
            runCurrent()

            val submittedQuery = loader.awaitStarted(1)
            assertEquals(setOf(1), submittedQuery.threadIds)
            assertEquals(setOf("cpu-cycles"), submittedQuery.eventTypes)

            loader.complete(1, snapshotFor(submittedQuery))
            runCurrent()
            assertEquals(submittedQuery, result.await().snapshot.query)
        }

    @Test
    fun `cancel suppresses the active result and permits a later submission`() =
        runTest {
            val loader = ControllableProjectionLoader(ignoreCancellationFor = setOf(1))
            val coordinator = ProfileQueryCoordinator(this, loader)
            val result =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.results.first()
                }

            coordinator.submit(SESSION, ProfileGeneration(1), ProfileQuery(threadIds = setOf(1)))
            runCurrent()
            loader.awaitStarted(1)
            coordinator.cancel()
            loader.complete(1, snapshotFor(1))
            runCurrent()
            loader.awaitCancelled(1)
            assertFalse(result.isCompleted)

            coordinator.submit(SESSION, ProfileGeneration(2), ProfileQuery(threadIds = setOf(2)))
            runCurrent()
            loader.awaitStarted(2)
            loader.complete(2, snapshotFor(2))
            runCurrent()

            assertEquals(ProfileGeneration(2), result.await().generation)
        }

    @Test
    fun `close suppresses the active result and rejects later submissions`() =
        runTest {
            val loader = ControllableProjectionLoader(ignoreCancellationFor = setOf(1))
            val coordinator = ProfileQueryCoordinator(this, loader)
            val result =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.results.first()
                }

            coordinator.submit(SESSION, ProfileGeneration(1), ProfileQuery(threadIds = setOf(1)))
            runCurrent()
            loader.awaitStarted(1)
            coordinator.close()
            loader.complete(1, snapshotFor(1))
            runCurrent()
            loader.awaitCancelled(1)

            assertFalse(result.isCompleted)
            assertFailsWith<IllegalStateException> {
                coordinator.submit(SESSION, ProfileGeneration(2), ProfileQuery(threadIds = setOf(2)))
            }
        }

    @Test
    fun `sqlite loader projects on the injected dispatcher and closes its store`() =
        runTest {
            val session = Files.createTempDirectory("aps-projection-loader-")
            val database = session.resolve("profile.sqlite")
            SQLiteSampleStore.open(database).use { }
            val dispatcher = RecordingDispatcher(StandardTestDispatcher(testScheduler))
            val query = ProfileQuery(threadIds = setOf(42))

            val snapshot = sqliteProjectionLoader(dispatcher).load(session, query)

            assertTrue(dispatcher.dispatchCount > 0)
            assertEquals(query, snapshot.query)
            SQLiteSampleStore.open(database).use { reopened ->
                assertEquals(snapshot, reopened.projectCore(query))
            }
        }

    private class ControllableProjectionLoader(
        private val ignoreCancellationFor: Set<Int> = emptySet(),
    ) : ProfileProjectionLoader {
        private data class Request(
            val id: Int,
            val query: ProfileQuery,
            val completion: CompletableDeferred<ProfileProjectionSnapshot>,
        )

        private val started = Channel<Request>(Channel.UNLIMITED)
        private val cancelled = Channel<Int>(Channel.UNLIMITED)
        private val requests = mutableMapOf<Int, Request>()

        override suspend fun load(
            sessionDirectory: Path,
            query: ProfileQuery,
        ): ProfileProjectionSnapshot {
            val id = query.threadIds.single()
            val request = Request(id, query, CompletableDeferred())
            requests[id] = request
            started.send(request)
            return try {
                request.completion.await()
            } catch (cancelledException: kotlinx.coroutines.CancellationException) {
                if (id !in ignoreCancellationFor) throw cancelledException
                withContext(NonCancellable) { request.completion.await() }
            } finally {
                if (!currentCoroutineContext().isActive) cancelled.send(id)
            }
        }

        suspend fun awaitStarted(id: Int): ProfileQuery {
            val request = started.receive()
            assertEquals(id, request.id)
            return request.query
        }

        suspend fun awaitCancelled(id: Int) {
            assertEquals(id, cancelled.receive())
        }

        fun complete(
            id: Int,
            snapshot: ProfileProjectionSnapshot,
        ) {
            checkNotNull(requests[id]).completion.complete(snapshot)
        }
    }

    private class RecordingDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        var dispatchCount: Int = 0
            private set

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            dispatchCount++
            delegate.dispatch(context, block)
        }
    }

    private companion object {
        val SESSION: Path = Path.of("session")
    }
}

private fun snapshotFor(id: Int): ProfileProjectionSnapshot = snapshotFor(ProfileQuery(threadIds = setOf(id)))

private fun snapshotFor(query: ProfileQuery): ProfileProjectionSnapshot =
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
