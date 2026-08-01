package com.androidperformancestudio.memory.capture

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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BitmapHeapDumpCaptureSessionTest {
    @Test
    fun `captures api 35 bitmap hprof and memory snapshot`() =
        runTest {
            val root = createTempDirectory("bitmap-capture")
            val runner = BitmapRunner()
            val session = BitmapHeapDumpCaptureSession(Path.of("adb"), runner::run)

            val result = session.capture(request(root))

            val capture = assertIs<StudioResult.Success<BitmapCaptureResult>>(result).value
            assertEquals(35, capture.sdkLevel)
            assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(capture.hprofFile))
            assertEquals(30L * 1024L, capture.memorySnapshot?.javaHeapPssBytes)
            assertEquals(40L * 1024L, capture.memorySnapshot?.nativeHeapPssBytes)
            assertEquals(100L * 1024L, capture.memorySnapshot?.totalPssBytes)
            assertEquals(listOf("getprop", "dumpheap", "pull", "meminfo", "rm"), runner.commands)
            val dumpRequest = runner.requests.first { it.kind() == "dumpheap" }
            assertTrue(dumpRequest.arguments.containsAll(listOf("-b", "png", "42")))
        }

    @Test
    fun `rejects devices below api 35 before dumpheap`() =
        runTest {
            val runner = BitmapRunner(sdk = 34)
            val session = BitmapHeapDumpCaptureSession(Path.of("adb"), runner::run)

            val result = session.capture(request(createTempDirectory("bitmap-api")))

            val failure = assertIs<StudioResult.Failure>(result)
            assertEquals("BITMAP_DUMP_UNSUPPORTED_API", failure.error.code)
            assertEquals(listOf("getprop"), runner.commands)
        }

    private fun request(root: Path): BitmapCaptureRequest =
        BitmapCaptureRequest(
            sessionId = "session-1",
            sessionRoot = root,
            serial = "device-1",
            pid = 42,
            packageName = "com.example.app",
        )

    private class BitmapRunner(
        private val sdk: Int = 35,
    ) {
        val requests = mutableListOf<ProcessRequest>()
        val commands = mutableListOf<String>()

        suspend fun run(
            request: ProcessRequest,
            signal: ProcessCancellationSignal,
        ): ProcessRunResult {
            check(!signal.isCancelled)
            requests += request
            val kind = request.kind()
            commands += kind
            if (kind == "pull") Files.write(Path.of(request.arguments.last()), byteArrayOf(1, 2, 3))
            val stdout =
                when (kind) {
                    "getprop" -> sdk.toString()
                    "meminfo" -> "Java Heap: 30 0 0\nNative Heap: 40 0 0\nTOTAL PSS: 100 TOTAL RSS: 200"
                    else -> ""
                }
            return ProcessRunResult.Completed(
                ProcessOutput(
                    pid = 1,
                    command = request.command,
                    exitCode = 0,
                    stdout = CapturedProcessText(stdout, false),
                    stderr = CapturedProcessText("", false),
                    startedAt = Instant.EPOCH,
                    finishedAt = Instant.EPOCH,
                ),
            )
        }
    }
}

private fun ProcessRequest.kind(): String =
    when {
        arguments.contains("getprop") -> "getprop"
        arguments.contains("dumpheap") -> "dumpheap"
        arguments.contains("pull") -> "pull"
        arguments.contains("meminfo") -> "meminfo"
        arguments.contains("rm") -> "rm"
        else -> "unknown"
    }
