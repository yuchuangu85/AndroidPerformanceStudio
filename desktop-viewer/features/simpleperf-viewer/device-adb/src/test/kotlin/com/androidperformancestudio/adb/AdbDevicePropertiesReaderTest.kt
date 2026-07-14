package com.androidperformancestudio.adb

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
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

class AdbDevicePropertiesReaderTest {
    @Test
    fun `targets the selected serial and parses one bulk getprop command`() =
        runBlocking {
            val adb = Path.of("/sdk/platform-tools/adb")
            val cancellationSignal = ProcessCancellationSignal()
            var capturedRequest: ProcessRequest? = null
            var capturedSignal: ProcessCancellationSignal? = null
            val reader =
                AdbDevicePropertiesReader(adb) { request, signal ->
                    capturedRequest = request
                    capturedSignal = signal
                    completed(
                        request,
                        """
                        [ro.product.model]: [Pixel 8]
                        [ro.product.cpu.abilist]: [arm64-v8a,armeabi-v7a]
                        [ro.build.version.sdk]: [35]
                        [ro.build.version.release]: [15]
                        """.trimIndent(),
                    )
                }

            val result = reader.read("emulator-5554", cancellationSignal)

            assertEquals(adb, capturedRequest?.executable)
            assertEquals(
                listOf("-s", "emulator-5554", "shell", "getprop"),
                capturedRequest?.arguments,
            )
            assertSame(cancellationSignal, capturedSignal)
            assertEquals(
                35,
                assertIs<StudioResult.Success<AndroidDeviceProperties>>(result).value.sdkInt,
            )
        }

    @Test
    fun `preserves structured process failures`() =
        runBlocking {
            val expected =
                StudioError(
                    category = ErrorCategory.PROCESS_EXIT,
                    code = "PROCESS_EXIT_1",
                    message = "Process exited with code 1",
                )
            val reader =
                AdbDevicePropertiesReader(Path.of("adb")) { _, _ ->
                    ProcessRunResult.Failed(expected)
                }

            val result = assertIs<StudioResult.Failure>(reader.read("offline-serial"))

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
