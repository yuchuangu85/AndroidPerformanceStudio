package com.androidperformancestudio.frame.app

import com.androidperformancestudio.frame.presentation.FrameProcessOption
import com.androidperformancestudio.platform.adb.AdbBinaryResult
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.platform.adb.AdbTextResult
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

class DesktopBoundedFrameTimelineCaptureBackendTest {
    @Test
    fun `captures bounded Android 12 FrameTimeline evidence and cleans remote files`() =
        runBlocking {
            val directory = createTempDirectory("bounded-frame-timeline")
            val adb = FakeAdbClient(apiLevel = 31)
            val backend =
                DesktopBoundedFrameTimelineCaptureBackend(
                    adbLocator = { Path.of("adb") },
                    adbClientFactory = { adb },
                    captureRoot = directory,
                )

            val result = backend.capture("device-serial", PROCESS, 1_250L)

            val capture = assertIs<FrameBackendResult.Success<BoundedFrameTimelineCapture>>(result).value
            assertEquals(31, capture.androidApiLevel)
            assertTrue(Files.size(capture.traceFile) > 0L)
            assertTrue(adb.pushedConfig.contains("duration_ms: 1250"))
            assertTrue(adb.pushedConfig.contains("android.surfaceflinger.frametimeline"))
            assertTrue(
                adb.shellArguments.any { arguments ->
                    arguments.firstOrNull() == "sh" &&
                        arguments.last().contains("perfetto --txt -c -") &&
                        arguments.last().contains("-o /data/misc/perfetto-traces/")
                },
            )
            assertTrue(adb.shellArguments.last().take(3) == listOf("rm", "-f", adb.remoteConfig))
            assertEquals(adb.remoteTrace, adb.pulledRemotePath)
        }

    @Test
    fun `rejects devices below Android 12 before pushing a config`() =
        runBlocking {
            val adb = FakeAdbClient(apiLevel = 30)
            val backend =
                DesktopBoundedFrameTimelineCaptureBackend(
                    adbLocator = { Path.of("adb") },
                    adbClientFactory = { adb },
                    captureRoot = createTempDirectory("bounded-frame-api-gate"),
                )

            val result = backend.capture("device-serial", PROCESS, 1_000L)

            val failure = assertIs<FrameBackendResult.Failure>(result)
            assertTrue(failure.message.contains("Android 12 (API 31)"))
            assertTrue(adb.pushedConfig.isEmpty())
            assertEquals(2, adb.shellArguments.size)
            assertEquals("rm", adb.shellArguments.last().first())
        }

    private class FakeAdbClient(
        private val apiLevel: Int,
    ) : AdbClient {
        val shellArguments = mutableListOf<List<String>>()
        var pushedConfig: String = ""
            private set
        var remoteConfig: String = ""
            private set
        var remoteTrace: String = ""
            private set
        var pulledRemotePath: String? = null
            private set

        override suspend fun listDevices(): List<AdbDevice> = emptyList()

        override suspend fun shell(
            serial: String,
            arguments: List<String>,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult {
            shellArguments += arguments
            if (arguments.take(2) == listOf("getprop", "ro.build.version.sdk")) {
                return textResult(stdout = apiLevel.toString())
            }
            if (arguments.firstOrNull() == "sh") {
                val command = arguments.last()
                remoteTrace = command.substringAfter("-o ").trim()
            }
            return textResult()
        }

        override suspend fun execOut(
            serial: String,
            arguments: List<String>,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbBinaryResult = error("Not used")

        override suspend fun push(
            serial: String,
            localPath: Path,
            remotePath: String,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult {
            pushedConfig = Files.readString(localPath)
            remoteConfig = remotePath
            return textResult()
        }

        override suspend fun pull(
            serial: String,
            remotePath: String,
            localPath: Path,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult {
            pulledRemotePath = remotePath
            Files.writeString(localPath, "perfetto trace")
            return textResult()
        }

        override suspend fun forward(
            serial: String,
            local: String,
            remote: String,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = error("Not used")

        override suspend fun removeForward(
            serial: String,
            local: String,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = error("Not used")

        override suspend fun bugreport(
            serial: String,
            outputPath: Path,
            timeout: Duration,
            maxOutputBytesPerStream: Int,
            isCancellationRequested: () -> Boolean,
        ): AdbTextResult = error("Not used")

        private fun textResult(stdout: String = ""): AdbTextResult =
            AdbTextResult(
                exitCode = 0,
                stdout = stdout,
                stderr = "",
                duration = Duration.ZERO,
            )
    }

    private companion object {
        val PROCESS = FrameProcessOption(pid = 123, name = "dev.example.app", packageName = "dev.example.app")
    }
}
