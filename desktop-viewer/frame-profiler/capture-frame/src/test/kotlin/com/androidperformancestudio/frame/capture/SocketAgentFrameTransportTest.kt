package com.androidperformancestudio.frame.capture

import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbCommandCancelledException
import com.androidperformancestudio.platform.adb.AdbCommandFailedException
import com.androidperformancestudio.platform.adb.AdbTextResult
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SocketAgentFrameTransportTest {
    @Test
    fun `explains that the optional agent is not initialized when its session file is missing`() =
        runBlocking {
            val failure = AdbCommandFailedException(SESSION_READ, 1, "$MISSING_SESSION_ERROR\n")

            val error = startFailure(failure)

            assertEquals(
                "Optional FrameMetrics Agent is not initialized in target app dev.example; " +
                    "the Agent session file is missing.",
                error.message,
            )
            assertSame(failure, error.cause)
        }

    @Test
    fun `preserves other typed ADB command failure messages and causes`() =
        runBlocking {
            listOf(
                "run-as: package not debuggable: dev.example",
                "cat: files/agentperf/session.json: Permission denied",
                "cat: files/other/session.json: No such file or directory",
                "error: device offline",
            ).forEach { standardError ->
                val failure = AdbCommandFailedException(SESSION_READ, 1, standardError)

                val error = startFailure(failure)

                assertEquals(failure.message, error.message)
                assertSame(failure, error.cause)
            }
        }

    @Test
    fun `does not reinterpret an untyped failure containing the missing session diagnostic`() =
        runBlocking {
            val failure = IllegalStateException(MISSING_SESSION_ERROR)

            val error = startFailure(failure)

            assertEquals(failure.message, error.message)
            assertSame(failure, error.cause)
        }

    @Test
    fun `preserves the default diagnostic for a runtime failure without a message`() =
        runBlocking {
            val failure = RuntimeException()

            val error = startFailure(failure)

            assertEquals("ADB command failed", error.message)
            assertSame(failure, error.cause)
        }

    @Test
    fun `propagates coroutine cancellation without wrapping it`() =
        runBlocking {
            val cancellation = CancellationException("capture cancelled")
            val client = FailingAdbClient(cancellation)

            val error =
                assertFailsWith<CancellationException> {
                    SocketAgentFrameTransport(client, TARGET).start()
                }

            assertSame(cancellation, error)
            assertEquals(listOf(TARGET.serial to SESSION_READ), client.calls)
        }

    @Test
    fun `propagates typed ADB cancellation without wrapping it`() =
        runBlocking {
            val cancellation = AdbCommandCancelledException(SESSION_READ)
            val client = FailingAdbClient(cancellation)

            val error =
                assertFailsWith<AdbCommandCancelledException> {
                    SocketAgentFrameTransport(client, TARGET).start()
                }

            assertSame(cancellation, error)
            assertEquals(listOf(TARGET.serial to SESSION_READ), client.calls)
        }

    private suspend fun startFailure(failure: RuntimeException): FrameMetricsAgentCaptureException {
        val client = FailingAdbClient(failure)
        val error =
            assertFailsWith<FrameMetricsAgentCaptureException> {
                SocketAgentFrameTransport(client, TARGET).start()
            }
        assertEquals(listOf(TARGET.serial to SESSION_READ), client.calls)
        return error
    }

    private class FailingAdbClient(
        private val failure: RuntimeException,
    ) : AdbClient by DefaultAdbClient(Path.of("unused")) {
        val calls = mutableListOf<Pair<String, List<String>>>()

        override suspend fun shell(
            serial: String,
            arguments: List<String>,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult {
            calls += serial to arguments
            assertEquals(10.seconds, timeout)
            assertEquals(64 * 1024, maxOutputBytesPerStream)
            throw failure
        }
    }

    private companion object {
        val TARGET = GfxInfoCaptureTarget("device", "dev.example", 123)
        val SESSION_READ = listOf("run-as", "dev.example", "cat", "files/agentperf/session.json")
        const val MISSING_SESSION_ERROR = "cat: files/agentperf/session.json: No such file or directory"
    }
}
