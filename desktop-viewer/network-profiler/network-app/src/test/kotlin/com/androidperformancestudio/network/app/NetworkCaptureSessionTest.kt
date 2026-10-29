package com.androidperformancestudio.network.app

import com.androidperformancestudio.network.model.EvidenceCompleteness
import com.androidperformancestudio.network.model.InstrumentationMode
import com.androidperformancestudio.network.model.NETWORK_REDACTION_POLICY_VERSION
import com.androidperformancestudio.network.model.NetworkCaptureResult
import com.androidperformancestudio.network.model.NetworkCoverage
import com.androidperformancestudio.network.model.NetworkEvidenceCompleteness
import com.androidperformancestudio.network.model.NetworkSession
import com.androidperformancestudio.network.model.NetworkSessionStatus
import com.androidperformancestudio.network.model.NetworkTimeDomain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NetworkCaptureSessionTest {
    @Test
    fun `fatal polling errors clean up without publishing successful capture or recovery failure`() = captureTest { capture ->
        val fatal = OutOfMemoryError("synthetic network failure")

        val thrown = assertFailsWith<OutOfMemoryError> { capture.run(poll = { throw fatal }) }

        assertOriginalFailure(fatal, thrown)
        assertEquals(listOf("start", "poll", "stop"), capture.operations)
        assertEquals(listOf("started", "finished"), capture.callbackNames)
        assertTrue(capture.results.isEmpty())
        assertTrue(capture.failures.isEmpty())
        assertFalse(capture.running)
    }

    @Test
    fun `start failure is reported on caller without requiring Main or stopping an unacquired session`() = captureTest { capture ->
        val failure = IllegalStateException("Cannot connect to network agent")

        capture.run(start = { throw failure })

        assertEquals(listOf("start"), capture.operations)
        assertEquals(listOf("failure", "finished"), capture.callbackNames)
        assertOriginalFailure(failure, capture.failures.single())
        assertTrue(capture.events.isEmpty())
        assertTrue(capture.results.isEmpty())
        assertTrue(capture.returnedNormally)
        assertFalse(capture.running)
    }

    @Test
    fun `explicit stop publishes polled capture after non cancellable cleanup exactly once`() = captureTest { capture ->
        val polling = CompletableDeferred<Unit>()
        val stopping = CompletableDeferred<Unit>()
        val releaseStop = CompletableDeferred<Unit>()
        var polls = 0
        capture.publish = false
        val job = launch {
            capture.run(
                poll = {
                    when (++polls) {
                        1 -> 3
                        2 -> 5
                        else -> {
                            polling.complete(Unit)
                            awaitCancellation()
                        }
                    }
                },
                stop = {
                    stopping.complete(Unit)
                    releaseStop.await()
                    capture.result
                },
            )
        }

        try {
            polling.await()
            capture.publish = true
            job.cancel()
            stopping.await()
            assertFalse(job.isCompleted)
            assertTrue(capture.running)
            job.cancel()
        } finally {
            releaseStop.complete(Unit)
        }
        job.join()

        assertEquals(listOf("start", "poll", "poll", "poll", "stop"), capture.operations)
        assertEquals(listOf("started", "events", "events", "completed", "finished"), capture.callbackNames)
        assertEquals(listOf(3, 5), capture.events)
        assertSame(capture.result, capture.results.single())
        assertTrue(capture.failures.isEmpty())
        assertTrue(job.isCancelled)
        assertNotNull(capture.cancellation)
        assertFalse(capture.returnedNormally)
        assertFalse(capture.running)
    }

    @Test
    fun `poll failure stops once and reports original error instead of publishing a result`() = captureTest { capture ->
        val failure = IllegalArgumentException("Invalid network event")

        capture.run(poll = { throw failure })

        assertEquals(listOf("start", "poll", "stop"), capture.operations)
        assertEquals(listOf("started", "failure", "finished"), capture.callbackNames)
        assertOriginalFailure(failure, capture.failures.single())
        assertTrue(capture.events.isEmpty())
        assertTrue(capture.results.isEmpty())
        assertTrue(capture.returnedNormally)
        assertFalse(capture.running)
    }

    @Test
    fun `stop failure is suppressed on original poll failure without duplicate failure callbacks`() = captureTest { capture ->
        val pollFailure = IllegalArgumentException("Invalid network event")
        val stopFailure = IllegalStateException("Agent failed to close capture")

        capture.run(poll = { throw pollFailure }, stop = { throw stopFailure })

        assertEquals(listOf("start", "poll", "stop"), capture.operations)
        assertEquals(listOf("started", "failure", "finished"), capture.callbackNames)
        val reported = capture.failures.single()
        assertOriginalFailure(pollFailure, reported)
        assertOriginalFailure(stopFailure, reported.suppressed.single())
        assertTrue(capture.events.isEmpty())
        assertTrue(capture.results.isEmpty())
        assertTrue(capture.returnedNormally)
        assertFalse(capture.running)
    }

    @Test
    fun `stop failure during user cancellation is reported on caller and still finishes once`() = captureTest { capture ->
        val polling = CompletableDeferred<Unit>()
        val failure = IllegalStateException("Agent failed to stop capture")
        capture.publish = false
        val job = launch {
            capture.run(
                poll = {
                    polling.complete(Unit)
                    awaitCancellation()
                },
                stop = { throw failure },
            )
        }

        polling.await()
        capture.publish = true
        job.cancel()
        job.join()

        assertEquals(listOf("start", "poll", "stop"), capture.operations)
        assertEquals(listOf("started", "failure", "finished"), capture.callbackNames)
        assertOriginalFailure(failure, capture.failures.single())
        assertTrue(capture.events.isEmpty())
        assertTrue(capture.results.isEmpty())
        assertTrue(job.isCancelled)
        assertNotNull(capture.cancellation)
        assertFalse(capture.returnedNormally)
        assertFalse(capture.running)
    }

    @Test
    fun `cancellation during startup closes session acquired before returning to caller`() = captureTest { capture ->
        val starting = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        var acquired = false
        val job = launch {
            capture.run(
                start = {
                    starting.complete(Unit)
                    // A backend can finish acquiring a resource after its caller has cancelled.
                    withContext(NonCancellable) { releaseStart.await() }
                    acquired = true
                    SESSION
                },
            )
        }

        try {
            starting.await()
            job.cancel()
            assertFalse(job.isCompleted)
        } finally {
            releaseStart.complete(Unit)
        }
        job.join()

        assertTrue(acquired)
        assertEquals(listOf("start", "stop"), capture.operations)
        assertEquals(listOf("completed", "finished"), capture.callbackNames)
        assertSame(capture.result, capture.results.single())
        assertTrue(capture.events.isEmpty())
        assertTrue(capture.failures.isEmpty())
        assertTrue(job.isCancelled)
        assertNotNull(capture.cancellation)
        assertFalse(capture.returnedNormally)
        assertFalse(capture.running)
    }

    @Test
    fun `disposal cancellation closes capture without publishing its result`() = captureTest { capture ->
        val polling = CompletableDeferred<Unit>()
        val job = launch {
            capture.run(
                poll = {
                    polling.complete(Unit)
                    awaitCancellation()
                },
            )
        }

        polling.await()
        capture.publish = false
        job.cancel()
        job.join()

        assertEquals(listOf("start", "poll", "stop"), capture.operations)
        assertEquals(listOf("started", "finished"), capture.callbackNames)
        assertTrue(capture.events.isEmpty())
        assertTrue(capture.results.isEmpty())
        assertTrue(capture.failures.isEmpty())
        assertTrue(job.isCancelled)
        assertNotNull(capture.cancellation)
        assertFalse(capture.returnedNormally)
        assertFalse(capture.running)
    }

    @Test
    fun `poll cancellation preserves its cause after cleanup and is never reported as failure`() = captureTest { capture ->
        val cancellation = CancellationException("Polling cancelled")
        val job = launch { capture.run(poll = { throw cancellation }) }

        job.join()

        assertEquals(listOf("start", "poll", "stop"), capture.operations)
        assertEquals(listOf("started", "completed", "finished"), capture.callbackNames)
        assertOriginalFailure(cancellation, capture.cancellation)
        assertSame(capture.result, capture.results.single())
        assertTrue(capture.events.isEmpty())
        assertTrue(capture.failures.isEmpty())
        assertTrue(job.isCancelled)
        assertFalse(capture.returnedNormally)
        assertFalse(capture.running)
    }

    @Test
    fun `start cancellation finishes without stopping or reporting a backend failure`() = captureTest { capture ->
        val cancellation = CancellationException("Startup cancelled")
        val job = launch { capture.run(start = { throw cancellation }) }

        job.join()

        assertEquals(listOf("start"), capture.operations)
        assertEquals(listOf("finished"), capture.callbackNames)
        assertOriginalFailure(cancellation, capture.cancellation)
        assertTrue(capture.events.isEmpty())
        assertTrue(capture.results.isEmpty())
        assertTrue(capture.failures.isEmpty())
        assertTrue(job.isCancelled)
        assertFalse(capture.returnedNormally)
        assertFalse(capture.running)
    }

    @Test
    fun `backend wrapping startup cancellation does not report a false startup failure`() = captureTest { capture ->
        val starting = CompletableDeferred<Unit>()
        var wrappedCancellation = false
        val job = launch {
            capture.run(
                start = {
                    starting.complete(Unit)
                    try {
                        awaitCancellation()
                    } catch (cancelled: CancellationException) {
                        wrappedCancellation = true
                        throw IllegalStateException("Backend wrapped cancellation", cancelled)
                    }
                },
            )
        }

        starting.await()
        job.cancel()
        job.join()

        assertTrue(wrappedCancellation)
        assertEquals(listOf("start"), capture.operations)
        assertEquals(listOf("finished"), capture.callbackNames)
        assertTrue(capture.events.isEmpty())
        assertTrue(capture.results.isEmpty())
        assertTrue(capture.failures.isEmpty())
        assertTrue(job.isCancelled)
        assertNotNull(capture.cancellation)
        assertFalse(capture.returnedNormally)
        assertFalse(capture.running)
    }

    private fun assertOriginalFailure(expected: Throwable, actual: Throwable?) {
        val reported = assertNotNull(actual)
        assertEquals(expected::class, reported::class)
        assertEquals(expected.message, reported.message)
        assertTrue(generateSequence(reported) { it.cause }.any { it === expected })
    }

    private fun captureTest(block: suspend CoroutineScope.(CaptureProbe) -> Unit) {
        Executors.newSingleThreadExecutor { task -> Thread(task, "network-capture-test-caller") }
            .asCoroutineDispatcher().use { callerDispatcher ->
                Executors.newSingleThreadExecutor { task -> Thread(task, "network-capture-test-io") }
                    .asCoroutineDispatcher().use { ioDispatcher ->
                        runBlocking(callerDispatcher) {
                            withTimeout(5_000L) {
                                val callerThread = Thread.currentThread()
                                val ioThread = withContext(ioDispatcher) { Thread.currentThread() }
                                block(CaptureProbe(callerThread, ioThread, ioDispatcher))
                            }
                        }
                    }
            }
    }

    private class CaptureProbe(
        private val callerThread: Thread,
        private val ioThread: Thread,
        private val ioDispatcher: CoroutineDispatcher,
    ) {
        val operations = mutableListOf<String>()
        val callbackNames = mutableListOf<String>()
        val events = mutableListOf<Int>()
        val results = mutableListOf<NetworkCaptureResult>()
        val failures = mutableListOf<Exception>()
        val result = capturedResult()
        var publish = true
        var running = true
        var returnedNormally = false
        var cancellation: CancellationException? = null

        suspend fun run(
            start: suspend () -> String = { SESSION },
            poll: suspend () -> Int = { awaitCancellation() },
            stop: suspend () -> NetworkCaptureResult = { result },
        ) {
            try {
                runNetworkCaptureSession(
                    start = {
                        operation("start")
                        start()
                    },
                    poll = { session ->
                        operation("poll")
                        assertEquals(SESSION, session)
                        poll()
                    },
                    stop = { session ->
                        operation("stop")
                        assertEquals(SESSION, session)
                        stop()
                    },
                    publishResult = {
                        assertSame(callerThread, Thread.currentThread(), "publishResult must run on caller")
                        publish
                    },
                    callbacks = NetworkCaptureCallbacks(
                        onStarted = { callback("started") },
                        onEvents = { count ->
                            callback("events")
                            events += count
                        },
                        onCompleted = { completed ->
                            callback("completed")
                            results += completed
                        },
                        onFailure = { failure ->
                            callback("failure")
                            failures += failure
                        },
                        onFinished = {
                            callback("finished")
                            running = false
                        },
                    ),
                    pollIntervalMillis = 1L,
                    ioDispatcher = ioDispatcher,
                )
                returnedNormally = true
            } catch (cancelled: CancellationException) {
                cancellation = cancelled
                throw cancelled
            }
        }

        private fun operation(name: String) {
            assertSame(ioThread, Thread.currentThread(), "$name must run on IO")
            operations += name
        }

        private fun callback(name: String) {
            assertSame(callerThread, Thread.currentThread(), "$name must run on caller")
            callbackNames += name
        }
    }

    private companion object {
        const val SESSION = "test-network-session"

        fun capturedResult() = NetworkCaptureResult(
            session = NetworkSession(
                id = SESSION,
                deviceSerial = "test-device",
                packageName = "com.example.network",
                startedAt = Instant.EPOCH,
                endedAt = Instant.EPOCH.plusSeconds(1),
                coverage = NetworkCoverage(
                    processIds = emptySet(),
                    observedLibraries = emptySet(),
                    observedInstrumentationIds = emptySet(),
                    instrumentationMode = InstrumentationMode.EXPLICIT_FACTORY,
                    supportedEventKinds = emptySet(),
                    knownLimitations = emptySet(),
                    windowStartedNs = 0L,
                    windowEndedNs = 1_000_000_000L,
                ),
                completeness = NetworkEvidenceCompleteness(
                    status = EvidenceCompleteness.COMPLETE,
                    droppedEvents = 0L,
                    sequenceGaps = 0L,
                    unpairedEvents = 0,
                    skippedRecords = 0,
                ),
                sourceTimeDomain = NetworkTimeDomain.DEVICE_MONOTONIC,
                sourceTimeOriginNs = 0L,
                clockMapping = null,
                status = NetworkSessionStatus.COMPLETE,
                redactionPolicyVersion = NETWORK_REDACTION_POLICY_VERSION,
            ),
            calls = emptyList(),
        )
    }
}
