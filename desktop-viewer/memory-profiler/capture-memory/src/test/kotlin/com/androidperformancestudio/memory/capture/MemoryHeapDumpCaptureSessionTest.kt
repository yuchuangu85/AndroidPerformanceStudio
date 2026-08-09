@file:Suppress("MaxLineLength")

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
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryHeapDumpCaptureSessionTest {
    @Test
    fun `locates host hprof conv from android home platform tools`() {
        val sdk = createTempDirectory("sdk")
        val tool = sdk.resolve("platform-tools").resolve(executableName())
        Files.createDirectories(tool.parent)
        Files.writeString(tool, "#!/bin/sh\n")
        tool.toFile().setExecutable(true)

        val locator =
            AndroidSdkHprofConvLocator(
                environment = mapOf("ANDROID_HOME" to sdk.toString()),
                defaultSdkRoot = null,
            )

        assertEquals(tool, locator.locate())
    }

    @Test
    fun `dump success executes dump pull convert cleanup and returns local hprof paths`() =
        runTest {
            val sdk = createSdkWithHprofConv()
            val runner = RecordingRunner()
            val sessionRoot = createTempDirectory("memory-session")
            val session = newSession(sdk, runner)

            val result = session.capture(captureRequest(sessionRoot))

            val success = assertIs<StudioResult.Success<MemoryCaptureResult>>(result)
            assertEquals(sessionRoot.resolve("session-1/session-1.raw.hprof"), success.value.rawHprofFile)
            assertEquals(sessionRoot.resolve("session-1/session-1.hprof"), success.value.convertedHprofFile)
            assertEquals(
                listOf("dumpheap", "pull", "getprop", "hprof-conv", "rm"),
                runner.requests.map { request -> request.commandKind() },
            )
            assertContains(runner.requests.first().arguments, "42")
            assertTrue("com.example.debug" !in runner.requests.first().arguments)
        }

    @Test
    fun `dumpheap failure is structured and does not pull or convert`() =
        runTest {
            val sdk = createSdkWithHprofConv()
            val runner = RecordingRunner(failures = mapOf("dumpheap" to "Permission denied"))
            val session = newSession(sdk, runner)

            val result = session.capture(captureRequest(createTempDirectory("memory-session")))

            val failure = assertIs<StudioResult.Failure>(result)
            assertEquals("DUMPHEAP_FAILED", failure.error.code)
            assertContains(failure.error.message, "not be debuggable")
            assertEquals(listOf("dumpheap"), runner.requests.map { it.commandKind() })
        }

    @Test
    fun `pull failure is structured and attempts cleanup without convert`() =
        runTest {
            val sdk = createSdkWithHprofConv()
            val runner = RecordingRunner(failures = mapOf("pull" to "remote object does not exist"))
            val session = newSession(sdk, runner)

            val result = session.capture(captureRequest(createTempDirectory("memory-session")))

            val failure = assertIs<StudioResult.Failure>(result)
            assertEquals("PULL_FAILED", failure.error.code)
            assertEquals(listOf("dumpheap", "pull", "rm"), runner.requests.map { it.commandKind() })
        }

    @Test
    fun `cleanup failure is warning only after successful capture`() =
        runTest {
            val sdk = createSdkWithHprofConv()
            val runner = RecordingRunner(failures = mapOf("rm" to "rm failed"))
            val session = newSession(sdk, runner)

            val result = session.capture(captureRequest(createTempDirectory("memory-session")))

            val success = assertIs<StudioResult.Success<MemoryCaptureResult>>(result)
            assertEquals(
                "DEVICE_CLEANUP_FAILED",
                success.value.warnings
                    .single()
                    .code,
            )
            assertContains(
                success.value.warnings
                    .single()
                    .message,
                success.value.deviceHprofPath,
            )
        }

    @Test
    fun `missing hprof conv keeps raw hprof and returns install warning`() =
        runTest {
            val runner = RecordingRunner()
            val session = newSession(sdkRoot = createTempDirectory("empty-sdk"), runner = runner)

            val result = session.capture(captureRequest(createTempDirectory("memory-session")))

            val success = assertIs<StudioResult.Success<MemoryCaptureResult>>(result)
            assertNull(success.value.convertedHprofFile)
            assertEquals(
                "HPROF_CONV_MISSING",
                success.value.warnings
                    .single()
                    .code,
            )
            assertEquals(listOf("dumpheap", "pull", "getprop", "rm"), runner.requests.map { it.commandKind() })
        }

    @Test
    fun `api level does not bypass hprof conversion`() =
        runTest {
            val sdk = createSdkWithHprofConv()
            val runner = RecordingRunner(sdkApiLevel = "33")
            val session = newSession(sdk, runner)

            val result = session.capture(captureRequest(createTempDirectory("memory-session")))

            val success = assertIs<StudioResult.Success<MemoryCaptureResult>>(result)
            assertNotNull(success.value.convertedHprofFile)
            assertTrue(!success.value.conversionSkipped)
            assertEquals(33, success.value.deviceSdkApiLevel)
            assertTrue(success.value.warnings.none { it.code == "HPROF_CONV_MISSING" })
            assertEquals(listOf("dumpheap", "pull", "getprop", "hprof-conv", "rm"), runner.requests.map { it.commandKind() })
        }

    @Test
    fun `getprop failure falls back to hprof conv`() =
        runTest {
            val sdk = createSdkWithHprofConv()
            val runner = RecordingRunner(failures = mapOf("getprop" to "cmd not found"))
            val session = newSession(sdk, runner)

            val result = session.capture(captureRequest(createTempDirectory("memory-session")))

            val success = assertIs<StudioResult.Success<MemoryCaptureResult>>(result)
            assertNotNull(success.value.convertedHprofFile)
            assertTrue(!success.value.conversionSkipped)
            assertEquals(listOf("dumpheap", "pull", "getprop", "hprof-conv", "rm"), runner.requests.map { it.commandKind() })
        }

    @Test
    fun `hprof conv failure keeps raw dump and still cleans device file`() =
        runTest {
            val sdk = createSdkWithHprofConv()
            val runner = RecordingRunner(failures = mapOf("hprof-conv" to "bad hprof"))
            val session = newSession(sdk, runner)

            val result = session.capture(captureRequest(createTempDirectory("memory-session")))

            val success = assertIs<StudioResult.Success<MemoryCaptureResult>>(result)
            assertNull(success.value.convertedHprofFile)
            assertEquals(
                "HPROF_CONV_FAILED",
                success.value.warnings
                    .single()
                    .code,
            )
            assertEquals(listOf("dumpheap", "pull", "getprop", "hprof-conv", "rm"), runner.requests.map { it.commandKind() })
        }

    private fun newSession(
        sdkRoot: Path,
        runner: RecordingRunner,
    ): MemoryHeapDumpCaptureSession =
        MemoryHeapDumpCaptureSession(
            adbClient = ProcessRunnerAdbClient(runner::run),
            hprofConvLocator = AndroidSdkHprofConvLocator(mapOf("ANDROID_HOME" to sdkRoot.toString()), null),
            hostProcessRunner = runner::run,
        )

    private fun captureRequest(sessionRoot: Path): MemoryCaptureRequest =
        MemoryCaptureRequest(
            sessionId = "session-1",
            sessionRoot = sessionRoot,
            serial = "device-1",
            pid = 42,
            packageName = "com.example.debug",
        )

    private fun createSdkWithHprofConv(): Path {
        val sdk = createTempDirectory("sdk")
        val hprofConv = sdk.resolve("platform-tools").resolve(executableName())
        Files.createDirectories(hprofConv.parent)
        Files.writeString(hprofConv, "#!/bin/sh\n")
        hprofConv.toFile().setExecutable(true)
        assertTrue(hprofConv.exists())
        return sdk
    }

    private fun executableName(): String =
        if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) "hprof-conv.exe" else "hprof-conv"

    private class RecordingRunner(
        private val failures: Map<String, String> = emptyMap(),
        private val sdkApiLevel: String = "25",
    ) {
        val requests = mutableListOf<HostProcessRequest>()

        suspend fun run(
            request: HostProcessRequest,
            signal: HostCancellationSignal,
        ): HostCommandResult {
            check(!signal.isCancelled)
            requests += request
            if (request.arguments.contains("getprop")) {
                val failureText = failures["getprop"]
                return if (failureText == null) completed(request, stdout = sdkApiLevel) else failed(request, failureText)
            }
            val kind = request.commandKind()
            val failureText = failures[kind]
            return if (failureText == null) completed(request) else failed(request, failureText)
        }

        private fun completed(
            request: HostProcessRequest,
            stdout: String = "",
        ): HostCommandResult.Completed = HostCommandResult.Completed(output(request, exitCode = 0, stdout = stdout))

        private fun failed(
            request: HostProcessRequest,
            stderr: String,
        ): HostCommandResult.Failed =
            HostCommandResult.Failed(
                error = StudioError(ErrorCategory.PROCESS_EXIT, "PROCESS_EXIT_1", "Process exited with code 1"),
                output = output(request, exitCode = 1, stderr = stderr),
            )

        private fun output(
            request: HostProcessRequest,
            exitCode: Int,
            stderr: String = "",
            stdout: String = "",
        ): HostCommandOutput =
            HostCommandOutput(
                pid = 1L,
                command = request.command,
                exitCode = exitCode,
                stdout = HostCapturedText(stdout, truncated = false),
                stderr = HostCapturedText(stderr, truncated = false),
                startedAt = Instant.EPOCH,
                finishedAt = Instant.EPOCH,
            )
    }
}

private fun HostProcessRequest.commandKind(): String =
    when {
        arguments.contains("dumpheap") -> "dumpheap"
        arguments.contains("pull") -> "pull"
        arguments.contains("getprop") -> "getprop"
        arguments.contains("rm") -> "rm"
        executable.fileName.toString().startsWith("hprof-conv") -> "hprof-conv"
        else -> executable.fileName.toString()
    }
