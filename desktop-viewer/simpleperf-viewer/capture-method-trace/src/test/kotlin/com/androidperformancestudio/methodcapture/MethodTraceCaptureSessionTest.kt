package com.androidperformancestudio.methodcapture

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.CapturedProcessText
import com.androidperformancestudio.toolchain.ProcessOutput
import com.androidperformancestudio.toolchain.ProcessRunResult
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/**
 * Verifies the `am profile` command sequence and structured failures via a fake process runner
 * that records every command and materializes the pulled trace file.
 */
class MethodTraceCaptureSessionTest {
    @Test
    fun `captures a method trace in the expected command order`() = runTest {
        val commands = mutableListOf<List<String>>()
        val session = MethodTraceCaptureSession(Path.of("adb"), recordingRunner(commands, "fake trace"))

        val sessionDir = Files.createTempDirectory("mt-session")
        val result =
            session.capture(
                MethodTraceCaptureRequest(
                    sessionId = "capture-1",
                    sessionRoot = sessionDir,
                    serial = "emulator-5554",
                    pid = 42,
                    packageName = "com.example",
                    durationSeconds = 0,
                ),
            )

        val success = assertIs<StudioResult.Success<MethodTraceCaptureResult>>(result)
        assertTrue(Files.size(success.value.traceFile) > 0)
        val flatten = commands.joinToString(" ") { it.joinToString(" ") }
        assertTrue(flatten.contains("getprop ro.build.version.sdk"))
        assertTrue(flatten.contains("am profile start 42"))
        assertTrue(flatten.contains("am profile stop 42"))
        assertTrue(flatten.contains("pull"))
        assertTrue(flatten.contains("rm -f"))
        assertTrue(flatten.contains("aps-capture-1.trace"))
    }

    @Test
    fun `requestStop ends the capture early`() = runTest {
        val commands = mutableListOf<List<String>>()
        val session = MethodTraceCaptureSession(Path.of("adb"), recordingRunner(commands, "fake trace"))
        val sessionDir = Files.createTempDirectory("mt-session")

        val deferred =
            async {
                session.capture(
                    MethodTraceCaptureRequest(
                        sessionId = "capture-2",
                        sessionRoot = sessionDir,
                        serial = "emulator-5554",
                        pid = 42,
                        packageName = "com.example",
                        durationSeconds = 30,
                    ),
                )
            }
        // Wait until the session is between start and stop, then end it early.
        while (!session.isRecording) {
            delay(1)
        }
        session.requestStop()
        val result = deferred.await()

        assertIs<StudioResult.Success<MethodTraceCaptureResult>>(result)
        val flatten = commands.joinToString(" ") { it.joinToString(" ") }
        assertTrue(flatten.contains("am profile stop 42"))
    }

    @Test
    fun `rejects devices below API 21 with only the getprop command`() = runTest {
        val commands = mutableListOf<List<String>>()
        val session = MethodTraceCaptureSession(Path.of("adb"), recordingRunner(commands, "fake", sdk = "19"))

        val sessionDir = Files.createTempDirectory("mt-session")
        val result =
            session.capture(
                MethodTraceCaptureRequest(
                    sessionId = "capture-3",
                    sessionRoot = sessionDir,
                    serial = "emulator-5554",
                    pid = 42,
                    packageName = "com.example",
                ),
            )
        val failure = assertIs<StudioResult.Failure>(result)
        assertEquals("METHOD_TRACE_UNSUPPORTED_API", failure.error.code)
        assertEquals(1, commands.size) // only getprop ran
    }

    @Test
    fun `maps an am profile start failure and cleans up`() = runTest {
        val commands = mutableListOf<List<String>>()
        val session =
            MethodTraceCaptureSession(
                Path.of("adb"),
                recordingRunner(commands, "fake", failStart = true),
            )

        val sessionDir = Files.createTempDirectory("mt-session")
        val result =
            session.capture(
                MethodTraceCaptureRequest(
                    sessionId = "capture-4",
                    sessionRoot = sessionDir,
                    serial = "emulator-5554",
                    pid = 42,
                    packageName = "com.example",
                ),
            )
        val failure = assertIs<StudioResult.Failure>(result)
        assertEquals("METHOD_TRACE_START_FAILED", failure.error.code)
        val flatten = commands.joinToString(" ") { it.joinToString(" ") }
        assertTrue(flatten.contains("rm -f"))
    }

    private fun recordingRunner(
        commands: MutableList<List<String>>,
        traceBytes: String,
        sdk: String = "33",
        failStart: Boolean = false,
    ): MethodTraceCaptureProcessRunner =
        { request, _ ->
            commands += request.arguments
            val arguments = request.arguments
            when {
                arguments.contains("getprop") -> completed(sdk)
                arguments.contains("am") && arguments.contains("start") ->
                    if (failStart) failed("am profile failed") else completed("")
                arguments.contains("am") && arguments.contains("stop") -> completed("")
                arguments.contains("ls") -> completed("-rw-r--r-- 1 shell shell 4 trace")
                arguments.contains("pull") -> {
                    Files.writeString(Path.of(arguments.last()), traceBytes)
                    completed("")
                }
                arguments.contains("rm") -> completed("")
                else -> completed("")
            }
        }

    private fun completed(output: String): ProcessRunResult.Completed {
        val now = Instant.now()
        return ProcessRunResult.Completed(
            ProcessOutput(
                pid = 0L,
                command = emptyList(),
                exitCode = 0,
                stdout = CapturedProcessText(output, false),
                stderr = CapturedProcessText("", false),
                startedAt = now,
                finishedAt = now,
            ),
        )
    }

    private fun failed(message: String): ProcessRunResult.Failed =
        ProcessRunResult.Failed(
            StudioError(
                category = ErrorCategory.PROCESS_EXIT,
                code = "PROCESS_EXIT_1",
                message = message,
            ),
        )
}
