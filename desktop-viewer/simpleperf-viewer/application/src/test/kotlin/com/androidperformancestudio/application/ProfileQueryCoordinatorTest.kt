package com.androidperformancestudio.application

import com.androidperformancestudio.storage.DataQualitySummary
import com.androidperformancestudio.storage.ProfileOverview
import com.androidperformancestudio.storage.ProfileProjectionRequest
import com.androidperformancestudio.storage.ProfileProjectionSnapshot
import com.androidperformancestudio.storage.ProfileQuery
import com.androidperformancestudio.storage.SQLiteSampleStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

            coordinator.submit(PREPARED_SESSION, ProfileGeneration(1), ProfileQuery(threadIds = setOf(1)))
            runCurrent()
            loader.awaitStarted(1)

            coordinator.submit(PREPARED_SESSION, ProfileGeneration(2), ProfileQuery(threadIds = setOf(2)))
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

            coordinator.submit(PREPARED_SESSION, ProfileGeneration(1), ProfileQuery(threadIds = setOf(1)))
            runCurrent()
            loader.awaitStarted(1)
            coordinator.submit(PREPARED_SESSION, ProfileGeneration(2), ProfileQuery(threadIds = setOf(2)))
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
                PREPARED_SESSION,
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

            coordinator.submit(PREPARED_SESSION, ProfileGeneration(1), ProfileQuery(threadIds = setOf(1)))
            runCurrent()
            loader.awaitStarted(1)
            coordinator.cancel()
            loader.complete(1, snapshotFor(1))
            runCurrent()
            loader.awaitCancelled(1)
            assertFalse(result.isCompleted)

            coordinator.submit(PREPARED_SESSION, ProfileGeneration(2), ProfileQuery(threadIds = setOf(2)))
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

            coordinator.submit(PREPARED_SESSION, ProfileGeneration(1), ProfileQuery(threadIds = setOf(1)))
            runCurrent()
            loader.awaitStarted(1)
            coordinator.close()
            loader.complete(1, snapshotFor(1))
            runCurrent()
            loader.awaitCancelled(1)

            assertFalse(result.isCompleted)
            assertFailsWith<IllegalStateException> {
                coordinator.submit(PREPARED_SESSION, ProfileGeneration(2), ProfileQuery(threadIds = setOf(2)))
            }
        }

    @Test
    fun `cancel remains responsive while an Unconfined loader begins inline`() {
        assertLifecycleResponsive("cancel") { coordinator -> coordinator.cancel() }
    }

    @Test
    fun `close remains responsive while an Unconfined loader begins inline`() {
        assertLifecycleResponsive("close") { coordinator -> coordinator.close() }
    }

    @Test
    fun `sqlite loader projects on the injected dispatcher`() =
        runTest {
            val session = Files.createTempDirectory("aps-projection-loader-")
            val database = session.resolve("profile.sqlite")
            SQLiteSampleStore.open(database).use { }
            val dispatcher = RecordingDispatcher(StandardTestDispatcher(testScheduler))
            val query = ProfileQuery(threadIds = setOf(42))

            val prepared =
                PreparedProfileSession(
                    database,
                    database,
                    ProfileSessionMode.READ_WRITE_V2,
                    schemaVersion = 2,
                )
            val snapshot = sqliteProjectionLoader(dispatcher).load(prepared, query)

            assertTrue(dispatcher.dispatchCount > 0)
            assertEquals(query, snapshot.query)
        }

    @Test
    fun `new sqlite submission interrupts obsolete query closes its store and publishes only latest`() =
        runTest {
            val obsolete = BlockingProjectionStore()
            val latest = ImmediateProjectionStore()
            val stores = Channel<InterruptibleProjectionStore>(Channel.UNLIMITED)
            stores.trySend(obsolete).getOrThrow()
            stores.trySend(latest).getOrThrow()
            val loader = sqliteProjectionLoader(Dispatchers.IO) { stores.tryReceive().getOrThrow() }
            val coordinator = ProfileQueryCoordinator(backgroundScope, loader)
            val result =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.results.first()
                }

            coordinator.submit(PREPARED_SESSION, ProfileGeneration(1), ProfileQuery(threadIds = setOf(1)))
            runCurrent()
            assertTrue(obsolete.started.await(1, SECONDS), "obsolete SQLite projection did not start")

            coordinator.submit(PREPARED_SESSION, ProfileGeneration(2), ProfileQuery(threadIds = setOf(2)))

            assertTrue(obsolete.interrupted.await(1, SECONDS), "obsolete SQLite projection was not interrupted")
            assertTrue(obsolete.closed.await(1, SECONDS), "interrupted SQLite store was not closed")
            assertEquals(1, obsolete.interruptCount.get())
            assertEquals(GeneratedProjection(ProfileGeneration(2), snapshotFor(2)), result.await())
            assertTrue(latest.closed.await(1, SECONDS), "latest SQLite store was not closed")
        }

    @Test
    fun `sqlite cancellation during open closes store without starting query`() =
        runTest {
            val openStarted = CountDownLatch(1)
            val releaseOpen = CountDownLatch(1)
            val store = ImmediateProjectionStore()
            val loader =
                sqliteProjectionLoader(Dispatchers.IO) {
                    openStarted.countDown()
                    check(releaseOpen.await(1, SECONDS)) { "test did not release SQLite open" }
                    store
                }
            val load =
                backgroundScope.async {
                    loader.load(PREPARED_SESSION, ProfileQuery(threadIds = setOf(1)))
                }

            runCurrent()
            assertTrue(openStarted.await(1, SECONDS), "SQLite open did not start")
            load.cancel()
            releaseOpen.countDown()
            load.cancelAndJoin()

            assertTrue(store.closed.await(1, SECONDS), "store opened after cancellation was not closed")
            assertEquals(0, store.projectCount.get(), "cancelled open must not start a projection")
        }

    @Test
    fun `sqlite cancellation before first statement remains visible to the projection`() =
        assertPersistentCancellationAt(ProjectionRacePoint.BEFORE_FIRST_STATEMENT)

    @Test
    fun `sqlite cancellation between statements aborts the next projection statement`() =
        assertPersistentCancellationAt(ProjectionRacePoint.BETWEEN_STATEMENTS)

    @Test
    fun `sqlite projection failure retains handler and store cleanup failures as suppressed`() =
        runTest {
            val loader = sqliteProjectionLoader(Dispatchers.Unconfined) { CleanupFailingProjectionStore() }

            val failure =
                assertFailsWith<ProfileProjectionLoadException> {
                    loader.load(PREPARED_SESSION, ProfileQuery(threadIds = setOf(1)))
                }

            assertEquals("REPORT_QUERY_FAILED", failure.error.code)
            val queryFailure = assertIs<SQLException>(failure.cause)
            assertEquals(
                listOf("handler clear failure", "store close failure"),
                queryFailure.suppressedExceptions.map(Throwable::message),
            )
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
            session: PreparedProfileSession,
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

    private class BlockingProjectionStore : InterruptibleProjectionStore {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val interruptCount = AtomicInteger()

        override fun installCancellationHandler(cancellationRequested: () -> Boolean): AutoCloseable = AutoCloseable { }

        override fun projectCore(request: ProfileProjectionRequest): ProfileProjectionSnapshot {
            started.countDown()
            check(interrupted.await(1, SECONDS)) { "projection was not interrupted" }
            throw SQLException("interrupted")
        }

        override fun interrupt() {
            interruptCount.incrementAndGet()
            interrupted.countDown()
        }

        override fun close() {
            closed.countDown()
        }
    }

    private class ImmediateProjectionStore : InterruptibleProjectionStore {
        val projectCount = AtomicInteger()
        val closed = CountDownLatch(1)

        override fun installCancellationHandler(cancellationRequested: () -> Boolean): AutoCloseable = AutoCloseable { }

        override fun projectCore(request: ProfileProjectionRequest): ProfileProjectionSnapshot {
            projectCount.incrementAndGet()
            return snapshotFor(request.query)
        }

        override fun interrupt() = Unit

        override fun close() {
            closed.countDown()
        }
    }

    private class StatementRaceProjectionStore(
        private val racePoint: ProjectionRacePoint,
    ) : InterruptibleProjectionStore {
        val raceReached = CountDownLatch(1)
        val releaseRace = CountDownLatch(1)
        val cancellationObserved = CountDownLatch(1)
        val handlerCleared = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val interruptCount = AtomicInteger()
        val statementCount = AtomicInteger()
        private val cancellationRequested = AtomicReference<() -> Boolean>()

        override fun installCancellationHandler(cancellationRequested: () -> Boolean): AutoCloseable {
            this.cancellationRequested.set(cancellationRequested)
            return AutoCloseable { handlerCleared.countDown() }
        }

        override fun projectCore(request: ProfileProjectionRequest): ProfileProjectionSnapshot {
            if (racePoint == ProjectionRacePoint.BEFORE_FIRST_STATEMENT) awaitRace()
            executeStatement()
            if (racePoint == ProjectionRacePoint.BETWEEN_STATEMENTS) awaitRace()
            executeStatement()
            return snapshotFor(request.query)
        }

        override fun interrupt() {
            interruptCount.incrementAndGet()
        }

        override fun close() {
            check(handlerCleared.count == 0L) { "cancellation handler must clear before store close" }
            closed.countDown()
        }

        private fun awaitRace() {
            raceReached.countDown()
            check(releaseRace.await(1, SECONDS)) { "test did not release projection race" }
        }

        private fun executeStatement() {
            if (checkNotNull(cancellationRequested.get()).invoke()) {
                cancellationObserved.countDown()
                throw SQLException("persistent cancellation")
            }
            statementCount.incrementAndGet()
        }
    }

    private class CleanupFailingProjectionStore : InterruptibleProjectionStore {
        override fun installCancellationHandler(cancellationRequested: () -> Boolean): AutoCloseable =
            AutoCloseable { throw IOException("handler clear failure") }

        override fun projectCore(request: ProfileProjectionRequest) = throw SQLException("query failure")

        override fun interrupt() = Unit

        override fun close() = throw IOException("store close failure")
    }

    private fun assertLifecycleResponsive(
        actionName: String,
        action: (ProfileQueryCoordinator) -> Unit,
    ) {
        val loaderStarted = CountDownLatch(1)
        val releaseLoader = CountDownLatch(1)
        val actionCompleted = CountDownLatch(1)
        val submissionFailure = AtomicReference<Throwable?>()
        val actionFailure = AtomicReference<Throwable?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator =
            ProfileQueryCoordinator(scope) { _, query ->
                loaderStarted.countDown()
                releaseLoader.await()
                snapshotFor(query)
            }
        val submitThread =
            thread(name = "projection-submit") {
                runCatching {
                    coordinator.submit(PREPARED_SESSION, ProfileGeneration(1), ProfileQuery(threadIds = setOf(1)))
                }.exceptionOrNull()?.let(submissionFailure::set)
            }
        var actionThread: Thread? = null

        try {
            assertTrue(loaderStarted.await(1, SECONDS), "the Unconfined loader did not begin inline")
            actionThread =
                thread(name = "projection-$actionName") {
                    runCatching { action(coordinator) }.exceptionOrNull()?.let(actionFailure::set)
                    actionCompleted.countDown()
                }

            assertTrue(
                actionCompleted.await(1, SECONDS),
                "$actionName must not wait for the inline loader to return",
            )
        } finally {
            releaseLoader.countDown()
            submitThread.join(2_000)
            actionThread?.join(2_000)
            scope.cancel()
        }

        assertFalse(submitThread.isAlive, "submit thread did not finish")
        assertFalse(actionThread.isAlive, "$actionName thread did not finish")
        submissionFailure.get()?.let { throw it }
        actionFailure.get()?.let { throw it }
    }

    private fun assertPersistentCancellationAt(racePoint: ProjectionRacePoint) =
        runTest {
            val store = StatementRaceProjectionStore(racePoint)
            val loader = sqliteProjectionLoader(Dispatchers.IO) { store }
            val load =
                backgroundScope.async {
                    loader.load(PREPARED_SESSION, ProfileQuery(threadIds = setOf(1)))
                }
            runCurrent()
            assertTrue(store.raceReached.await(1, SECONDS), "projection did not reach $racePoint")

            load.cancel()
            store.releaseRace.countDown()
            load.cancelAndJoin()

            assertTrue(store.cancellationObserved.await(1, SECONDS), "persistent cancellation was not observed")
            assertTrue(store.handlerCleared.await(1, SECONDS), "cancellation handler was not cleared")
            assertTrue(store.closed.await(1, SECONDS), "cancelled store was not closed")
            assertEquals(1, store.interruptCount.get())
            val expectedStatements = if (racePoint == ProjectionRacePoint.BETWEEN_STATEMENTS) 1 else 0
            assertEquals(expectedStatements, store.statementCount.get())
        }

    private companion object {
        val SESSION: Path = Path.of("session")
        val PREPARED_SESSION =
            PreparedProfileSession(
                database = SESSION,
                originalDatabase = SESSION,
                mode = ProfileSessionMode.READ_WRITE_V2,
                schemaVersion = 2,
            )
    }

    private enum class ProjectionRacePoint {
        BEFORE_FIRST_STATEMENT,
        BETWEEN_STATEMENTS,
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
