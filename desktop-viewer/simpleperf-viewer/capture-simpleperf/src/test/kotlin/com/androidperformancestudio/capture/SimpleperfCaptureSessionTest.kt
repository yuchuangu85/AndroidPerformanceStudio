@file:Suppress("MaxLineLength")

package com.androidperformancestudio.capture

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.CapturedProcessText
import com.androidperformancestudio.toolchain.ProcessOutput
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SimpleperfCaptureSessionTest {
    @Test
    fun `records pulls and persists a reproducible session`() =
        runBlocking {
            val root = Files.createTempDirectory("aps-capture-test-")
            val requests = mutableListOf<ProcessRequest>()
            val session =
                SimpleperfCaptureSession(
                    adbExecutable = Path.of("adb"),
                    simpleperfPreparer = preparedDeviceSimpleperf(),
                    processInvocation = { request, _ ->
                        requests += request
                        when (requests.size) {
                            1 -> completed(request, stdout = "recorded", stderr = "record warning")
                            2 -> {
                                Files.writeString(Path.of(request.arguments.last()), "perf-data")
                                completed(request, stdout = "pulled")
                            }
                            else -> completed(request, stdout = "removed")
                        }
                    },
                )
            val request = captureRequest(root)

            val result = session.capture(request)

            val completed = assertIs<CaptureState.Completed>(result)
            assertEquals(root.resolve("session-1"), completed.sessionDirectory)
            assertEquals(completed.sessionDirectory.resolve("perf.data"), completed.perfData)
            assertEquals(3, requests.size)
            assertEquals(
                SimpleperfRecordCommand("serial-1", "simpleperf", request.parameters).adbArguments,
                requests.first().arguments,
            )
            assertEquals(
                listOf(
                    "-s",
                    "serial-1",
                    "pull",
                    "/data/local/tmp/aps/perf.data",
                    completed.perfData.toString(),
                ),
                requests[1].arguments,
            )
            assertEquals(remoteCleanupArguments(), requests.last().arguments)
            assertTrue(completed.sessionDirectory.resolve("capture-command.txt").exists())
            assertTrue(completed.perfData.exists())
            assertEquals("recorded", completed.sessionDirectory.resolve("record.stdout.log").readText())
            assertEquals("record warning", completed.sessionDirectory.resolve("record.stderr.log").readText())
            assertTrue(
                completed.sessionDirectory
                    .resolve("record.properties")
                    .readText()
                    .contains("exitCode=0"),
            )
            assertTrue(
                completed.sessionDirectory
                    .resolve("session.properties")
                    .readText()
                    .contains("status=COMPLETED"),
            )
        }

    @Test
    fun `preserves record failure evidence and enters failed state`() =
        runBlocking {
            val root = Files.createTempDirectory("aps-capture-failure-")
            val expected =
                StudioError(
                    category = ErrorCategory.PROCESS_EXIT,
                    code = "PROCESS_EXIT_1",
                    message = "permission denied",
                )
            val requests = mutableListOf<ProcessRequest>()
            val session =
                SimpleperfCaptureSession(
                    adbExecutable = Path.of("adb"),
                    simpleperfPreparer = preparedDeviceSimpleperf(),
                    processInvocation = { request, _ ->
                        requests += request
                        if (requests.size == 1) {
                            failed(
                                request = request,
                                error = expected,
                                stderr = "failed to open perf event",
                            )
                        } else {
                            completed(request, stdout = "removed")
                        }
                    },
                )

            val result = session.capture(captureRequest(root))

            val failure = assertIs<CaptureState.Failed>(result)
            assertEquals(expected, failure.error)
            assertEquals(remoteCleanupArguments(), requests.last().arguments)
            assertEquals("failed to open perf event", failure.sessionDirectory.resolve("record.stderr.log").readText())
            assertEquals("removed", failure.sessionDirectory.resolve("cleanup.stdout.log").readText())
            assertTrue(
                failure.sessionDirectory
                    .resolve("record.properties")
                    .readText()
                    .contains("exitCode=1"),
            )
            assertTrue(
                failure.sessionDirectory
                    .resolve("session.properties")
                    .readText()
                    .contains("status=FAILED"),
            )
        }

    @Test
    fun `cancels an active record and retains cancellation evidence`() =
        runBlocking {
            val root = Files.createTempDirectory("aps-capture-cancel-")
            val requests = mutableListOf<ProcessRequest>()
            val session =
                SimpleperfCaptureSession(
                    adbExecutable = Path.of("adb"),
                    simpleperfPreparer = preparedDeviceSimpleperf(),
                    processInvocation = { request, signal ->
                        requests += request
                        if (requests.size == 1) {
                            while (!signal.isCancelled) delay(10)
                            failed(
                                request = request,
                                error =
                                    StudioError(
                                        category = ErrorCategory.PROCESS_CANCELLED,
                                        code = "PROCESS_CANCELLED",
                                        message = "cancelled",
                                    ),
                                stderr = "cancelled by user",
                            )
                        } else {
                            completed(request, stdout = "removed after cancellation")
                        }
                    },
                )
            val capture = async { session.capture(captureRequest(root)) }
            while (session.state.value !is CaptureState.Recording) delay(10)

            session.cancel()

            val cancelled = assertIs<CaptureState.Cancelled>(capture.await())
            assertEquals(remoteCleanupArguments(), requests.last().arguments)
            assertEquals("cancelled by user", cancelled.sessionDirectory.resolve("record.stderr.log").readText())
            assertEquals(
                "removed after cancellation",
                cancelled.sessionDirectory.resolve("cleanup.stdout.log").readText(),
            )
        }

    @Test
    fun `stops recording gracefully then pulls the completed profile`() =
        runBlocking {
            val root = Files.createTempDirectory("aps-capture-stop-")
            val requests = mutableListOf<ProcessRequest>()
            var stopRequested = false
            val session =
                SimpleperfCaptureSession(
                    adbExecutable = Path.of("adb"),
                    simpleperfPreparer = preparedDeviceSimpleperf(),
                    processInvocation = { request, _ ->
                        requests += request
                        when {
                            "record" in request.arguments -> {
                                while (!stopRequested) delay(10)
                                completed(request, stdout = "record stopped cleanly")
                            }
                            "pkill" in request.arguments -> {
                                stopRequested = true
                                completed(request, stdout = "signal delivered")
                            }
                            "pull" in request.arguments -> {
                                Files.writeString(Path.of(request.arguments.last()), "perf-data")
                                completed(request, stdout = "pulled")
                            }
                            else -> completed(request, stdout = "removed")
                        }
                    },
                )
            val capture = async { session.capture(captureRequest(root).withManualStop()) }
            while (session.state.value !is CaptureState.Recording) delay(10)

            session.stop()

            val completed = assertIs<CaptureState.Completed>(capture.await())
            assertTrue(completed.perfData.exists())
            assertEquals(
                listOf("-s", "serial-1", "shell", "pkill", "-INT", "simpleperf"),
                requests.first { "pkill" in it.arguments }.arguments,
            )
            assertEquals("signal delivered", completed.sessionDirectory.resolve("stop.stdout.log").readText())
            assertTrue(requests.any { "pull" in it.arguments })
        }

    private fun remoteCleanupArguments(): List<String> = listOf("-s", "serial-1", "shell", "rm", "-f", "/data/local/tmp/aps/perf.data")

    private fun captureRequest(root: Path): CaptureRequest =
        CaptureRequest(
            sessionId = "session-1",
            sessionRoot = root,
            serial = "serial-1",
            availability = DeviceSimpleperfAvailability("simpleperf 1.0", listOf("arm64-v8a")),
            parameters = SamplingTemplate.APP_CPU_BASIC.create(SimpleperfTarget.Process(321)),
        )

    private fun CaptureRequest.withManualStop(): CaptureRequest = copy(parameters = parameters.copy(durationSeconds = null))

    private fun preparedDeviceSimpleperf(): DeviceSimpleperfPreparer =
        DeviceSimpleperfPreparer { _, availability, _ ->
            StudioResult.Success(
                PreparedSimpleperf(
                    source = SimpleperfSource.DEVICE,
                    devicePath = "simpleperf",
                    version = availability.deviceVersion,
                    abi = null,
                ),
            )
        }

    private fun completed(
        request: ProcessRequest,
        stdout: String,
        stderr: String = "",
    ): ProcessRunResult.Completed =
        ProcessRunResult.Completed(
            output(request, exitCode = 0, stdout = stdout, stderr = stderr),
        )

    private fun failed(
        request: ProcessRequest,
        error: StudioError,
        stderr: String,
    ): ProcessRunResult.Failed =
        ProcessRunResult.Failed(
            error = error,
            output = output(request, exitCode = 1, stdout = "", stderr = stderr),
        )

    private fun output(
        request: ProcessRequest,
        exitCode: Int,
        stdout: String,
        stderr: String,
    ): ProcessOutput =
        ProcessOutput(
            pid = 1,
            command = request.command,
            exitCode = exitCode,
            stdout = CapturedProcessText(stdout, truncated = false),
            stderr = CapturedProcessText(stderr, truncated = false),
            startedAt = Instant.EPOCH,
            finishedAt = Instant.EPOCH,
        )
}
