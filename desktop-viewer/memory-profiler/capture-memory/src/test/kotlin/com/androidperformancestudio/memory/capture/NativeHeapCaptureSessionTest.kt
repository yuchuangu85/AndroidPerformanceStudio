@file:Suppress("MaxLineLength")

package com.androidperformancestudio.memory.capture

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.CapturedProcessText
import com.androidperformancestudio.toolchain.ProcessCancellationSignal
import com.androidperformancestudio.toolchain.ProcessOutput
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NativeHeapCaptureSessionTest {
    @Test
    fun `capture pushes config runs perfetto and pulls the native heap trace`() =
        runTest {
            val runner = RecordingRunner(sdkApiLevel = "33")
            val sessionRoot = createTempDirectory("native-heap-session")
            val session = NativeHeapCaptureSession(adbExecutable = Path.of("adb"), processRunner = runner::run)

            val result = session.capture(nativeRequest(sessionRoot))

            val success = assertIs<StudioResult.Success<NativeHeapCaptureResult>>(result)
            assertEquals(sessionRoot.resolve("session-nh/session-nh.native-heap.pb"), success.value.traceFile)
            assertTrue(success.value.traceFile.exists())
            assertEquals(33, success.value.deviceSdkApiLevel)
            assertEquals(
                listOf("getprop", "push", "perfetto", "pull", "rm"),
                runner.commandKinds,
            )
            assertTrue(runner.requests.any { it.arguments.contains("heapprofd") || it.arguments.contains("perfetto") })
        }

    @Test
    fun `capture rejects devices below api 29 without running perfetto`() =
        runTest {
            val runner = RecordingRunner(sdkApiLevel = "28")
            val session = NativeHeapCaptureSession(adbExecutable = Path.of("adb"), processRunner = runner::run)

            val result = session.capture(nativeRequest(createTempDirectory("native-heap-session")))

            val failure = assertIs<StudioResult.Failure>(result)
            assertEquals("NATIVE_HEAP_UNSUPPORTED_API", failure.error.code)
            assertEquals(listOf("getprop"), runner.commandKinds)
        }

    @Test
    fun `perfetto failure is structured and still cleans device files`() =
        runTest {
            val runner = RecordingRunner(sdkApiLevel = "33", failures = mapOf("perfetto" to "heapprofd not available"))
            val session = NativeHeapCaptureSession(adbExecutable = Path.of("adb"), processRunner = runner::run)

            val result = session.capture(nativeRequest(createTempDirectory("native-heap-session")))

            val failure = assertIs<StudioResult.Failure>(result)
            assertEquals("NATIVE_HEAP_CAPTURE_FAILED", failure.error.code)
            assertEquals(listOf("getprop", "push", "perfetto", "rm"), runner.commandKinds)
        }

    @Test
    fun `empty pulled trace is reported as a failure`() =
        runTest {
            val runner = RecordingRunner(sdkApiLevel = "33", traceBytes = ByteArray(0))
            val session = NativeHeapCaptureSession(adbExecutable = Path.of("adb"), processRunner = runner::run)

            val result = session.capture(nativeRequest(createTempDirectory("native-heap-session")))

            val failure = assertIs<StudioResult.Failure>(result)
            assertEquals("NATIVE_HEAP_EMPTY_TRACE", failure.error.code)
        }

    private fun nativeRequest(sessionRoot: Path): NativeHeapCaptureRequest =
        NativeHeapCaptureRequest(
            sessionId = "session-nh",
            sessionRoot = sessionRoot,
            serial = "device-1",
            pid = 42,
            packageName = "com.example.debug",
        )

    private class RecordingRunner(
        private val sdkApiLevel: String = "33",
        private val traceBytes: ByteArray = "fake-perfetto-trace".encodeToByteArray(),
        private val failures: Map<String, String> = emptyMap(),
    ) {
        val requests = mutableListOf<ProcessRequest>()
        val commandKinds = mutableListOf<String>()

        suspend fun run(
            request: ProcessRequest,
            signal: ProcessCancellationSignal,
        ): ProcessRunResult {
            check(!signal.isCancelled)
            requests += request
            val kind = request.commandKind()
            commandKinds += kind
            if (kind == "pull") {
                Files.write(Path.of(request.arguments.last()), traceBytes)
            }
            val failureText = failures[kind]
            return if (failureText == null) completed(request) else failed(request, failureText)
        }

        private fun completed(request: ProcessRequest): ProcessRunResult.Completed =
            ProcessRunResult.Completed(output(request, exitCode = 0))

        private fun failed(
            request: ProcessRequest,
            stderr: String,
        ): ProcessRunResult.Failed =
            ProcessRunResult.Failed(
                error = StudioError(ErrorCategory.PROCESS_EXIT, "PROCESS_EXIT_1", "Process exited with code 1"),
                output = output(request, exitCode = 1, stderr = stderr),
            )

        private fun output(
            request: ProcessRequest,
            exitCode: Int,
            stderr: String = "",
        ): ProcessOutput =
            ProcessOutput(
                pid = 1L,
                command = request.command,
                exitCode = exitCode,
                stdout = CapturedProcessText(if (request.arguments.contains("getprop")) sdkApiLevel else "", truncated = false),
                stderr = CapturedProcessText(stderr, truncated = false),
                startedAt = Instant.EPOCH,
                finishedAt = Instant.EPOCH,
            )
    }
}

private fun ProcessRequest.commandKind(): String =
    when {
        arguments.contains("getprop") -> "getprop"
        arguments.contains("push") -> "push"
        arguments.contains("perfetto") -> "perfetto"
        arguments.contains("pull") -> "pull"
        arguments.contains("rm") -> "rm"
        else -> executable.fileName.toString()
    }
