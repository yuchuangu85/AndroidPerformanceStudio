package com.androidperformancestudio.adb

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.toolchain.CapturedProcessText
import com.androidperformancestudio.toolchain.ProcessCancellationSignal
import com.androidperformancestudio.toolchain.ProcessOutput
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class AdbDeviceRefresherTest {
    @Test
    fun `runs adb devices long format and parses its output`() =
        runBlocking {
            val adb = Path.of("/sdk/platform-tools/adb")
            val cancellationSignal = ProcessCancellationSignal()
            var capturedRequest: ProcessRequest? = null
            var capturedSignal: ProcessCancellationSignal? = null
            val refresher =
                AdbDeviceRefresher(adb) { request, signal ->
                    capturedRequest = request
                    capturedSignal = signal
                    completed(
                        request,
                        "List of devices attached\nemulator-5554 device model:Pixel_9 transport_id:1\n",
                    )
                }

            val result = refresher.refresh(cancellationSignal)

            assertEquals(adb, capturedRequest?.executable)
            assertEquals(listOf("devices", "-l"), capturedRequest?.arguments)
            assertSame(cancellationSignal, capturedSignal)
            assertEquals(
                "Pixel_9",
                assertIs<StudioResult.Success<List<AdbDevice>>>(result).value.single().model,
            )
        }

    @Test
    fun `preserves structured process failures`() =
        runBlocking {
            val expected =
                StudioError(
                    category = ErrorCategory.PROCESS_TIMEOUT,
                    code = "PROCESS_TIMED_OUT",
                    message = "Process timed out",
                )
            val refresher =
                AdbDeviceRefresher(Path.of("adb")) { _, _ ->
                    ProcessRunResult.Failed(expected)
                }

            val result = assertIs<StudioResult.Failure>(refresher.refresh())

            assertSame(expected, result.error)
        }

    private fun completed(
        request: ProcessRequest,
        stdout: String,
    ): ProcessRunResult.Completed =
        ProcessRunResult.Completed(
            ProcessOutput(
                pid = 1,
                command = request.command,
                exitCode = 0,
                stdout = CapturedProcessText(stdout, truncated = false),
                stderr = CapturedProcessText("", truncated = false),
                startedAt = Instant.EPOCH,
                finishedAt = Instant.EPOCH,
            ),
        )
}
