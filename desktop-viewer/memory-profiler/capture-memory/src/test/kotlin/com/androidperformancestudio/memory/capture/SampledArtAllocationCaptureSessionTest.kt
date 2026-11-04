package com.androidperformancestudio.memory.capture

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
import com.androidperformancestudio.platform.toolchain.HostCapturedText
import com.androidperformancestudio.platform.toolchain.HostCommandOutput
import com.androidperformancestudio.platform.toolchain.HostCommandResult
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SampledArtAllocationCaptureSessionTest {
    @Test
    fun `api 36 records ART samples into a provenance-marked raw trace`() =
        runTest {
            val runner = RecordingRunner(sdkApiLevel = "36")
            val root = createTempDirectory("art-sampled")
            val session = SampledArtAllocationCaptureSession(ProcessRunnerAdbClient(runner::run))

            val result = session.capture(request(root))

            val captured = assertIs<StudioResult.Success<SampledArtAllocationCaptureResult>>(result).value
            assertEquals(root.resolve("art-1/art-1.art-sampled.pb"), captured.traceFile)
            assertTrue(Files.isRegularFile(captured.traceFile))
            assertEquals("perfetto/android.heapprofd/com.android.art", captured.source)
            assertFalse(captured.hasDeallocationEvents)
            assertFalse(captured.hasGcEvents)
            assertEquals(4096, captured.samplingIntervalBytes)
            assertEquals(36, captured.sdkLevel)
            assertEquals(listOf("getprop", "push", "perfetto", "pull", "rm"), runner.commandKinds)
            val config = Files.readString(captured.configFile)
            assertTrue(config.contains("heaps: \"com.android.art\""))
            assertTrue(config.contains("pid: 42"))
            assertTrue(config.contains("duration_ms: 10000"))
            val pushedConfig =
                runner.requests
                    .first { it.kind() == "push" }
                    .arguments
                    .last()
            val perfettoOutput =
                runner.requests
                    .first { it.kind() == "perfetto" }
                    .arguments
                    .last()
            assertTrue(pushedConfig.startsWith("/data/misc/perfetto-configs/"))
            assertTrue(perfettoOutput.startsWith("/data/misc/perfetto-traces/"))
        }

    @Test
    fun `api below 31 does not begin ART sampling`() =
        runTest {
            val runner = RecordingRunner(sdkApiLevel = "30")
            val session = SampledArtAllocationCaptureSession(ProcessRunnerAdbClient(runner::run))

            val result = session.capture(request(createTempDirectory("art-sampled")))

            assertEquals("ART_SAMPLED_UNSUPPORTED_API", assertIs<StudioResult.Failure>(result).error.code)
            assertEquals(listOf("getprop"), runner.commandKinds)
        }

    @Test
    fun `failed record cleans remote files without publishing a trace`() =
        runTest {
            val runner = RecordingRunner(sdkApiLevel = "36", failedStage = "perfetto")
            val root = createTempDirectory("art-sampled")
            val session = SampledArtAllocationCaptureSession(ProcessRunnerAdbClient(runner::run))

            val result = session.capture(request(root))

            assertEquals("ART_SAMPLED_RECORD_FAILED", assertIs<StudioResult.Failure>(result).error.code)
            assertEquals(listOf("getprop", "push", "perfetto", "rm"), runner.commandKinds)
            assertFalse(Files.exists(root.resolve("art-1/art-1.art-sampled.pb")))
        }

    @Test
    fun `empty transfer leaves no published or partial trace`() =
        runTest {
            val runner = RecordingRunner(sdkApiLevel = "36", traceBytes = ByteArray(0))
            val root = createTempDirectory("art-sampled")
            val session = SampledArtAllocationCaptureSession(ProcessRunnerAdbClient(runner::run))

            val result = session.capture(request(root))

            assertEquals("ART_SAMPLED_EMPTY_TRACE", assertIs<StudioResult.Failure>(result).error.code)
            assertFalse(Files.exists(root.resolve("art-1/art-1.art-sampled.pb")))
            assertFalse(Files.exists(root.resolve("art-1/art-1.art-sampled.pb.partial")))
        }

    private fun request(root: Path): SampledArtAllocationCaptureRequest =
        SampledArtAllocationCaptureRequest("art-1", root, "emulator-5554", 42, "com.example.debug")

    private class RecordingRunner(
        private val sdkApiLevel: String,
        private val failedStage: String? = null,
        private val traceBytes: ByteArray = "fake-perfetto-trace".encodeToByteArray(),
    ) {
        val requests = mutableListOf<HostProcessRequest>()
        val commandKinds = mutableListOf<String>()

        suspend fun run(
            request: HostProcessRequest,
            signal: HostCancellationSignal,
        ): HostCommandResult {
            check(!signal.isCancelled)
            requests += request
            val kind = request.kind()
            commandKinds += kind
            if (kind == "pull") Files.write(Path.of(request.arguments.last()), traceBytes)
            val output =
                HostCommandOutput(
                    pid = 1L,
                    command = request.command,
                    exitCode = if (kind == failedStage) 1 else 0,
                    stdout = HostCapturedText(if (kind == "getprop") sdkApiLevel else "", truncated = false),
                    stderr = HostCapturedText(if (kind == failedStage) "record failed" else "", truncated = false),
                    startedAt = Instant.EPOCH,
                    finishedAt = Instant.EPOCH,
                )
            return if (kind == failedStage) {
                HostCommandResult.Failed(
                    error = StudioError(ErrorCategory.PROCESS_EXIT, "PROCESS_EXIT_1", "Process failed"),
                    output = output,
                )
            } else {
                HostCommandResult.Completed(output)
            }
        }
    }
}

private fun HostProcessRequest.kind(): String =
    when {
        arguments.contains("getprop") -> "getprop"
        arguments.contains("push") -> "push"
        arguments.contains("perfetto") -> "perfetto"
        arguments.contains("pull") -> "pull"
        arguments.contains("rm") -> "rm"
        else -> executable.fileName.toString()
    }
