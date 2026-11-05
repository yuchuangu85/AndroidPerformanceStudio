package com.androidperformancestudio.perfetto.uiserver

import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.perfetto.TraceProcessorTool
import com.androidperformancestudio.platform.toolchain.HostProcessBinaryResult
import com.androidperformancestudio.platform.toolchain.HostProcessLaunchRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRunner
import com.androidperformancestudio.platform.toolchain.HostProcessTextResult
import com.androidperformancestudio.platform.toolchain.RunningHostProcess
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class NativeTraceProcessorServerTest {
    private val traceFile = Files.createTempFile("native-server-test", ".perfetto-trace")
    private val tool =
        TraceProcessorTool(
            path = Files.createTempFile("trace-processor-shell", "").also { Files.write(it, "#!/bin/sh\n".toByteArray()) },
            version = "v57.2",
            sha256 = "a".repeat(64),
        )

    @Test
    fun `start launches the server with the pinned arguments`() = runTest {
        val runner = FakeRunner(FakeProcess(isAlive = true))
        val server = NativeTraceProcessorServer(processRunner = runner, isReady = { true })
        try {
            val started = server.start(tool, traceFile)

            assertIs<StudioResult.Success<Unit>>(started)
            assertEquals(
                listOf(
                    "server",
                    "http",
                    "--ip-address",
                    "127.0.0.1",
                    "--port",
                    "9001",
                    "--idle-timeout",
                    "30m",
                    traceFile.toString(),
                ),
                runner.launchedRequest?.arguments,
            )
            assertEquals(tool.path, runner.launchedRequest?.executable)
            assertTrue(server.isRunning)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `start is idempotent while running`() = runTest {
        val runner = FakeRunner(FakeProcess(isAlive = true))
        val server = NativeTraceProcessorServer(processRunner = runner, isReady = { true })
        try {
            assertIs<StudioResult.Success<Unit>>(server.start(tool, traceFile))
            val second = server.start(tool, traceFile)

            assertIs<StudioResult.Success<Unit>>(second)
            assertEquals(1, runner.launchCount)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `start fails when the trace file does not exist`() = runTest {
        val runner = FakeRunner(FakeProcess(isAlive = true))
        val server = NativeTraceProcessorServer(processRunner = runner, isReady = { true })

        val started = server.start(tool, Files.createTempDirectory("missing").resolve("nope.pftrace"))

        assertIs<StudioResult.Failure>(started)
        assertEquals("TRACE_FILE_NOT_FOUND", started.error.code)
        assertEquals(0, runner.launchCount)
        assertFalse(server.isRunning)
    }

    @Test
    fun `start surfaces not-ready and stops the process when the process exits before readiness`() = runTest {
        val process = FakeProcess(isAlive = false)
        val runner = FakeRunner(process)
        val server = NativeTraceProcessorServer(processRunner = runner, isReady = { false })

        val started = server.start(tool, traceFile)

        assertIs<StudioResult.Failure>(started)
        assertEquals("TRACE_PROCESSOR_NOT_READY", started.error.code)
        assertFalse(server.isRunning)
        assertTrue(process.terminateCount > 0)
    }

    @Test
    fun `stop is idempotent`() = runTest {
        val runner = FakeRunner(FakeProcess(isAlive = true))
        val server = NativeTraceProcessorServer(processRunner = runner, isReady = { true })
        assertIs<StudioResult.Success<Unit>>(server.start(tool, traceFile))

        server.stop()
        server.stop()

        assertFalse(server.isRunning)
        assertEquals(1, runner.process.terminateCount)
    }

    private class FakeRunner(
        val process: FakeProcess,
    ) : HostProcessRunner {
        var launchedRequest: HostProcessLaunchRequest? = null
            private set
        var launchCount: Int = 0
            private set

        override suspend fun executeText(request: HostProcessRequest): HostProcessTextResult = throw UnsupportedOperationException()

        override suspend fun executeBinary(request: HostProcessRequest): HostProcessBinaryResult = throw UnsupportedOperationException()

        override fun launch(request: HostProcessLaunchRequest): RunningHostProcess {
            launchCount++
            launchedRequest = request
            return process
        }
    }

    private class FakeProcess(
        isAlive: Boolean,
    ) : RunningHostProcess {
        private var alive = isAlive
        var terminateCount: Int = 0
            private set

        override val pid: Long = 12345L

        override val isAlive: Boolean
            get() = alive

        override fun terminate() {
            terminateCount++
            alive = false
        }
    }
}
