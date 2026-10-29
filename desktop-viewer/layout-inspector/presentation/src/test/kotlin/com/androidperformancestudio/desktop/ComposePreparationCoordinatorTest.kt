package com.androidperformancestudio.desktop

import com.androidperformancestudio.compose.inspection.host.ComposeAgentBundle
import com.androidperformancestudio.compose.inspection.host.ComposeInspectionPreflight
import com.androidperformancestudio.compose.inspection.host.PreparedComposeInspection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Path

@Timeout(10)
class ComposePreparationCoordinatorTest {
    @Test
    fun `manual callers awaiting an in flight preparation reuse its success`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<PreparedComposeInspection>()
            var calls = 0
            var now = 0L
            val coordinator =
                ComposePreparationCoordinator(
                    prepare = { _, _ ->
                        calls += 1
                        entered.complete(Unit)
                        release.await()
                    },
                    nanoTime = { now },
                    retryDelayMillis = 0,
                )
            val automatic = async { coordinator.prepare(SERIAL, PACKAGE) }
            entered.await()
            val manual =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.prepare(SERIAL, PACKAGE, forceRetry = true)
                }
            val anotherManual =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.prepare(SERIAL, PACKAGE, forceRetry = true)
                }
            assertFalse(manual.isCompleted)
            assertFalse(anotherManual.isCompleted)
            val expected = prepared(pid = 101)
            now = 10_000_000_000L
            release.complete(expected)

            assertSame(expected, automatic.await().getOrThrow())
            assertSame(expected, manual.await().getOrThrow())
            assertSame(expected, anotherManual.await().getOrThrow())
            assertEquals(1, calls)
        }

    @Test
    fun `manual caller awaiting a forced preparation reuses its failure`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val failure = IllegalStateException("Native agent preflight failed")
            var calls = 0
            val coordinator =
                ComposePreparationCoordinator(
                    prepare = { _, _ ->
                        calls += 1
                        if (calls == 1) {
                            prepared()
                        } else {
                            entered.complete(Unit)
                            release.await()
                            throw failure
                        }
                    },
                )
            coordinator.prepare(SERIAL, PACKAGE).getOrThrow()
            val firstManual = async { coordinator.prepare(SERIAL, PACKAGE, forceRetry = true) }
            entered.await()
            val secondManual =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.prepare(SERIAL, PACKAGE, forceRetry = true)
                }
            assertFalse(secondManual.isCompleted)
            release.complete(Unit)

            assertSame(failure, firstManual.await().exceptionOrNull())
            assertSame(failure, secondManual.await().exceptionOrNull())
            assertEquals(2, calls)
        }

    @Test
    fun `same package negative cache remains an explicit failure until force retry`() =
        runBlocking {
            val failure = IllegalStateException("Native Compose inspector unavailable")
            val expected = prepared()
            var calls = 0
            val coordinator =
                ComposePreparationCoordinator(
                    prepare = { _, _ ->
                        calls += 1
                        if (calls == 1) throw failure
                        expected
                    },
                    nanoTime = { 0L },
                )

            assertSame(failure, coordinator.prepare(SERIAL, PACKAGE).exceptionOrNull())
            assertSame(failure, coordinator.prepare(SERIAL, PACKAGE).exceptionOrNull())
            assertEquals(1, calls)
            assertSame(expected, coordinator.prepare(SERIAL, PACKAGE, forceRetry = true).getOrThrow())
            assertSame(expected, coordinator.prepare(SERIAL, PACKAGE).getOrThrow())
            assertEquals(2, calls)
        }

    @Test
    fun `automatic retry starts at the default backoff boundary measured from completion`() =
        runBlocking {
            var now = -20_000_000_000L
            var calls = 0
            val failure = IllegalArgumentException("Target not ready")
            val expected = prepared()
            val coordinator =
                ComposePreparationCoordinator(
                    prepare = { _, _ ->
                        calls += 1
                        if (calls == 1) {
                            now += 10_000_000_000L
                            throw failure
                        }
                        expected
                    },
                    nanoTime = { now },
                )

            assertSame(failure, coordinator.prepare(SERIAL, PACKAGE).exceptionOrNull())
            now += 4_999_999_999L
            assertSame(failure, coordinator.prepare(SERIAL, PACKAGE).exceptionOrNull())
            assertEquals(1, calls)
            now += 1
            assertSame(expected, coordinator.prepare(SERIAL, PACKAGE).getOrThrow())
            assertEquals(2, calls)
        }

    @Test
    fun `success also expires after the configured retry delay`() =
        runBlocking {
            var now = 0L
            var calls = 0
            val coordinator =
                ComposePreparationCoordinator(
                    prepare = { _, _ -> prepared(pid = ++calls) },
                    nanoTime = { now },
                    retryDelayMillis = 25,
                )

            assertEquals(
                1,
                coordinator
                    .prepare(SERIAL, PACKAGE)
                    .getOrThrow()
                    .preflight.pid,
            )
            now = 24_999_999L
            assertEquals(
                1,
                coordinator
                    .prepare(SERIAL, PACKAGE)
                    .getOrThrow()
                    .preflight.pid,
            )
            now += 1
            assertEquals(
                2,
                coordinator
                    .prepare(SERIAL, PACKAGE)
                    .getOrThrow()
                    .preflight.pid,
            )
            assertEquals(2, calls)
        }

    @Test
    fun `invalidation and force retry discover a new PID for the same package`() =
        runBlocking {
            var pid = 101
            var calls = 0
            val coordinator =
                ComposePreparationCoordinator(
                    prepare = { _, _ ->
                        calls += 1
                        prepared(pid = pid)
                    },
                    nanoTime = { 0L },
                )

            assertEquals(
                101,
                coordinator
                    .prepare(SERIAL, PACKAGE)
                    .getOrThrow()
                    .preflight.pid,
            )
            pid = 202
            coordinator.invalidate()
            assertEquals(
                202,
                coordinator
                    .prepare(SERIAL, PACKAGE)
                    .getOrThrow()
                    .preflight.pid,
            )
            pid = 303
            assertEquals(
                303,
                coordinator
                    .prepare(SERIAL, PACKAGE, forceRetry = true)
                    .getOrThrow()
                    .preflight.pid,
            )
            assertEquals(3, calls)
        }

    @Test
    fun `switching package or device cannot reuse another target success`() =
        runBlocking {
            val targets = mutableListOf<Pair<String, String>>()
            val coordinator =
                ComposePreparationCoordinator(
                    prepare = { serial, packageName ->
                        targets += serial to packageName
                        prepared(serial = serial, packageName = packageName, pid = targets.size)
                    },
                    nanoTime = { 0L },
                )

            coordinator.prepare(SERIAL, PACKAGE).getOrThrow()
            val otherPackage = coordinator.prepare(SERIAL, "other.package").getOrThrow()
            assertEquals("other.package", otherPackage.preflight.packageName)
            val otherDevice = coordinator.prepare("other-device", "other.package").getOrThrow()
            assertEquals("other-device", otherDevice.preflight.serial)
            assertEquals(
                4,
                coordinator
                    .prepare(SERIAL, PACKAGE)
                    .getOrThrow()
                    .preflight.pid,
            )
            assertEquals(
                listOf(SERIAL to PACKAGE, SERIAL to "other.package", "other-device" to "other.package", SERIAL to PACKAGE),
                targets,
            )
        }

    @Test
    fun `different targets are serialized without sharing a result`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val targets = mutableListOf<String>()
            val coordinator =
                ComposePreparationCoordinator(
                    prepare = { serial, packageName ->
                        targets += packageName
                        if (targets.size == 1) {
                            entered.complete(Unit)
                            release.await()
                        }
                        prepared(serial = serial, packageName = packageName)
                    },
                )
            val first = async { coordinator.prepare(SERIAL, PACKAGE) }
            entered.await()
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.prepare(SERIAL, "other.package")
                }
            assertEquals(listOf(PACKAGE), targets)
            release.complete(Unit)

            assertEquals(
                PACKAGE,
                first
                    .await()
                    .getOrThrow()
                    .preflight.packageName,
            )
            assertEquals(
                "other.package",
                second
                    .await()
                    .getOrThrow()
                    .preflight.packageName,
            )
            assertEquals(listOf(PACKAGE, "other.package"), targets)
        }

    @Test
    fun `invalidation during preparation rejects the stale success and its waiters`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<PreparedComposeInspection>()
            val fresh = prepared(pid = 202)
            var calls = 0
            val coordinator =
                ComposePreparationCoordinator(
                    prepare = { _, _ ->
                        calls += 1
                        if (calls == 1) {
                            entered.complete(Unit)
                            release.await()
                        } else {
                            fresh
                        }
                    },
                )
            val stale = async { coordinator.prepare(SERIAL, PACKAGE) }
            entered.await()
            val staleWaiter =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.prepare(SERIAL, PACKAGE, forceRetry = true)
                }
            coordinator.invalidate()
            val next = async(start = CoroutineStart.UNDISPATCHED) { coordinator.prepare(SERIAL, PACKAGE) }
            release.complete(prepared(pid = 101))

            assertTrue(runCatching { stale.await() }.exceptionOrNull() is CancellationException)
            assertTrue(runCatching { staleWaiter.await() }.exceptionOrNull() is CancellationException)
            assertSame(fresh, next.await().getOrThrow())
            assertSame(fresh, coordinator.prepare(SERIAL, PACKAGE).getOrThrow())
            assertEquals(2, calls)
        }

    @Test
    fun `invalidation during failed preflight throws instead of negative caching`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val coordinator =
                ComposePreparationCoordinator(
                    prepare = { _, _ ->
                        calls += 1
                        if (calls == 1) {
                            entered.complete(Unit)
                            release.await()
                        }
                        error("Native preflight failed")
                    },
                )
            val stale = async { coordinator.prepare(SERIAL, PACKAGE) }
            entered.await()
            coordinator.invalidate()
            release.complete(Unit)

            assertTrue(runCatching { stale.await() }.exceptionOrNull() is CancellationException)
            assertTrue(coordinator.prepare(SERIAL, PACKAGE).isFailure)
            assertEquals(2, calls)
        }

    @Test
    fun `preflight cancellation escapes and is never cached`() =
        runBlocking {
            val cancelled = CancellationException("Preflight cancelled")
            val expected = prepared()
            var calls = 0
            val coordinator =
                ComposePreparationCoordinator(
                    prepare = { _, _ ->
                        calls += 1
                        if (calls == 1) throw cancelled
                        expected
                    },
                )

            assertSame(cancelled, runCatching { coordinator.prepare(SERIAL, PACKAGE) }.exceptionOrNull())
            assertSame(expected, coordinator.prepare(SERIAL, PACKAGE).getOrThrow())
            assertEquals(2, calls)
        }

    @Test
    fun `cancelled owner cannot publish even when preflight returns non cooperatively`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val expected = prepared(pid = 202)
            var calls = 0
            var returnedNormally = false
            val coordinator =
                ComposePreparationCoordinator(
                    prepare = { _, _ ->
                        calls += 1
                        if (calls == 1) {
                            withContext(NonCancellable) {
                                entered.complete(Unit)
                                release.await()
                            }
                        }
                        expected
                    },
                )
            val cancelled =
                async {
                    coordinator.prepare(SERIAL, PACKAGE)
                    returnedNormally = true
                }
            entered.await()
            cancelled.cancel()
            release.complete(Unit)
            cancelled.join()

            assertFalse(returnedNormally)
            assertTrue(cancelled.isCancelled)
            assertSame(expected, coordinator.prepare(SERIAL, PACKAGE).getOrThrow())
            assertEquals(2, calls)
        }

    @Test
    fun `cancelling a waiting manual caller leaves the active preparation usable`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<PreparedComposeInspection>()
            var calls = 0
            val coordinator =
                ComposePreparationCoordinator(
                    prepare = { _, _ ->
                        calls += 1
                        entered.complete(Unit)
                        release.await()
                    },
                )
            val active = async { coordinator.prepare(SERIAL, PACKAGE) }
            entered.await()
            val waiter =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.prepare(SERIAL, PACKAGE, forceRetry = true)
                }
            waiter.cancelAndJoin()
            val expected = prepared()
            release.complete(expected)

            assertSame(expected, active.await().getOrThrow())
            assertSame(expected, coordinator.prepare(SERIAL, PACKAGE).getOrThrow())
            assertEquals(1, calls)
        }

    @Test
    fun `fatal errors escape without being cached`() {
        val fatal = OutOfMemoryError("Synthetic native inspection failure")
        var calls = 0
        val coordinator =
            ComposePreparationCoordinator(
                prepare = { _, _ ->
                    calls += 1
                    if (calls == 1) throw fatal
                    prepared()
                },
            )

        assertSame(
            fatal,
            assertThrows(OutOfMemoryError::class.java) {
                runBlocking { coordinator.prepare(SERIAL, PACKAGE) }
            },
        )
        runBlocking { coordinator.prepare(SERIAL, PACKAGE).getOrThrow() }
        assertEquals(2, calls)
    }

    @Test
    fun `invalidated preparation does not terminate a foreground polling caller`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var attempts = 0
            val coordinator =
                ComposePreparationCoordinator({ _, _ ->
                    attempts += 1
                    if (attempts == 1) {
                        entered.complete(Unit)
                        release.await()
                    }
                    prepared(pid = attempts)
                })
            val poll =
                async {
                    val stale = coordinator.prepareForCapture(SERIAL, PACKAGE)
                    val current = coordinator.prepareForCapture(SERIAL, PACKAGE)
                    stale to current
                }
            entered.await()
            coordinator.invalidate()
            release.complete(Unit)

            val (stale, current) = poll.await()
            assertEquals(null, stale)
            assertEquals(2, current?.getOrThrow()?.preflight?.pid)
            assertFalse(poll.isCancelled)
        }

    @Test
    fun `invalidated manual preparation reports a changed target instead of cancelling the caller`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val coordinator =
                ComposePreparationCoordinator({ _, _ ->
                    entered.complete(Unit)
                    release.await()
                    prepared()
                })
            val manual =
                async {
                    runCatching { coordinator.prepareForCapture(SERIAL, PACKAGE, forceRetry = true) }
                }
            entered.await()
            coordinator.invalidate()
            release.complete(Unit)

            assertTrue(manual.await().exceptionOrNull() is InspectorCaptureTargetChangedException)
            assertFalse(manual.isCancelled)
        }

    private fun prepared(
        serial: String = SERIAL,
        packageName: String = PACKAGE,
        pid: Int = 101,
    ): PreparedComposeInspection {
        val bundle =
            ComposeAgentBundle(
                abi = "x86_64",
                nativeAgent = Path.of("dummy-agent.so"),
                serviceJar = Path.of("dummy-service.jar"),
                payloadJar = Path.of("dummy-payload.jar"),
                viewInspectorJar = Path.of("dummy-view-inspector.jar"),
                fingerprint = "dummy-fingerprint",
            )
        val preflight =
            ComposeInspectionPreflight(
                serial = serial,
                packageName = packageName,
                pid = pid,
                apiLevel = 35,
                abi = bundle.abi,
                bundleFingerprint = bundle.fingerprint,
            )
        // The host owns the internal constructor; fixtures need no bundle loading or ADB.
        return PreparedComposeInspection::class.java
            .getDeclaredConstructor(
                ComposeInspectionPreflight::class.java,
                ComposeAgentBundle::class.java,
                String::class.java,
            ).newInstance(preflight, bundle, "/data/user/0/$packageName")
    }

    private companion object {
        const val SERIAL = "device-1"
        const val PACKAGE = "sample.package"
    }
}
