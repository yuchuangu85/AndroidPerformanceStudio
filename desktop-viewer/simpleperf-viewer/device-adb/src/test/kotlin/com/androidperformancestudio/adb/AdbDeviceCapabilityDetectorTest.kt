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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class AdbDeviceCapabilityDetectorTest {
    @Test
    fun `root device with simpleperf is ready for any process`() =
        runBlocking {
            val requests = mutableListOf<ProcessRequest>()
            val signals = mutableListOf<ProcessCancellationSignal>()
            val cancellationSignal = ProcessCancellationSignal()
            val detector =
                AdbDeviceCapabilityDetector(Path.of("adb")) { request, signal ->
                    requests += request
                    signals += signal
                    when (request.arguments.takeLast(2)) {
                        listOf("id", "-u") -> completed(request, "0\n")
                        listOf("simpleperf", "--version") -> completed(request, "simpleperf version 1.0\n")
                        listOf("simpleperf", "list") ->
                            completed(
                                request,
                                "List of hardware events:\n  cpu-cycles\n  instructions\n" +
                                    "List of software events:\n  cpu-clock\n",
                            )
                        else -> error("Unexpected command: ${request.arguments}")
                    }
                }

            val result = detector.detect(properties(sdkInt = 35), cancellationSignal)
            val capabilities = assertIs<StudioResult.Success<DeviceCapabilities>>(result).value

            assertEquals(CapabilityReadiness.READY, capabilities.readiness)
            assertEquals(ProfilingScope.ANY_PROCESS, capabilities.profilingScope)
            assertEquals(true, capabilities.isRoot)
            assertEquals("simpleperf version 1.0", capabilities.simpleperfVersion)
            assertEquals(listOf("cpu-cycles", "instructions", "cpu-clock"), capabilities.eventNames)
            assertEquals(emptySet(), capabilities.limitations)
            assertEquals(
                listOf(
                    listOf("-s", "serial-1", "shell", "id", "-u"),
                    listOf("-s", "serial-1", "shell", "simpleperf", "--version"),
                    listOf("-s", "serial-1", "shell", "simpleperf", "list"),
                ),
                requests.map(ProcessRequest::arguments),
            )
            assertEquals(3, signals.size)
            signals.forEach { assertSame(cancellationSignal, it) }
        }

    @Test
    fun `non-root Android Q or newer is limited to profileable or debuggable apps`() =
        runBlocking {
            val detector = detector(uidOutput = "2000", simpleperfOutput = "simpleperf version 1.0")

            val capabilities =
                assertIs<StudioResult.Success<DeviceCapabilities>>(detector.detect(properties(sdkInt = 29))).value

            assertEquals(CapabilityReadiness.LIMITED, capabilities.readiness)
            assertEquals(ProfilingScope.PROFILEABLE_OR_DEBUGGABLE_APPS, capabilities.profilingScope)
            assertContains(capabilities.limitations, DeviceCapabilityLimitation.ROOT_UNAVAILABLE)
            assertContains(
                capabilities.limitations,
                DeviceCapabilityLimitation.APP_MUST_BE_PROFILEABLE_OR_DEBUGGABLE,
            )
            assertEquals(RootAccess.UNAVAILABLE, capabilities.rootAccess)
        }

    @Test
    fun `non-root userdebug reports that adb root can be activated`() =
        runBlocking {
            val detector =
                detector(
                    uidOutput = "2000",
                    simpleperfOutput = "simpleperf version 1.0",
                    buildTypeOutput = "userdebug",
                )

            val capabilities =
                assertIs<StudioResult.Success<DeviceCapabilities>>(detector.detect(properties(sdkInt = 35))).value

            assertEquals(CapabilityReadiness.LIMITED, capabilities.readiness)
            assertEquals(RootAccess.AVAILABLE_AFTER_ADB_ROOT, capabilities.rootAccess)
            assertContains(capabilities.limitations, DeviceCapabilityLimitation.ADB_ROOT_NOT_ACTIVE)
        }

    @Test
    fun `non-root pre-Q device is limited to debuggable apps`() =
        runBlocking {
            val detector = detector(uidOutput = "2000", simpleperfOutput = "simpleperf version 1.0")

            val capabilities =
                assertIs<StudioResult.Success<DeviceCapabilities>>(detector.detect(properties(sdkInt = 28))).value

            assertEquals(CapabilityReadiness.LIMITED, capabilities.readiness)
            assertEquals(ProfilingScope.DEBUGGABLE_APPS, capabilities.profilingScope)
            assertContains(capabilities.limitations, DeviceCapabilityLimitation.DEBUGGABLE_APP_REQUIRED)
        }

    @Test
    fun `missing device simpleperf blocks capture until fallback is installed`() =
        runBlocking {
            val detector =
                AdbDeviceCapabilityDetector(Path.of("adb")) { request, _ ->
                    when (request.arguments.takeLast(2)) {
                        listOf("id", "-u") -> completed(request, "2000\n")
                        listOf("getprop", "ro.build.type") -> completed(request, "user\n")
                        else ->
                            failed(
                                ErrorCategory.PROCESS_EXIT,
                                "PROCESS_EXIT_127",
                                "Process exited with code 127",
                            )
                    }
                }

            val capabilities =
                assertIs<StudioResult.Success<DeviceCapabilities>>(detector.detect(properties(sdkInt = 35))).value

            assertEquals(CapabilityReadiness.BLOCKED, capabilities.readiness)
            assertEquals(null, capabilities.simpleperfVersion)
            assertContains(capabilities.limitations, DeviceCapabilityLimitation.SIMPLEPERF_UNAVAILABLE)
        }

    @Test
    fun `rejects malformed shell uid instead of assuming non-root`() =
        runBlocking {
            val detector = detector(uidOutput = "uid=2000(shell)", simpleperfOutput = "unused")

            val result = assertIs<StudioResult.Failure>(detector.detect(properties(sdkInt = 35)))

            assertEquals(ErrorCategory.DATA_VALIDATION, result.error.category)
            assertEquals("ADB_SHELL_UID_INVALID", result.error.code)
        }

    @Test
    fun `preserves cancellation and timeout failures`() =
        runBlocking {
            val expected =
                StudioError(
                    category = ErrorCategory.PROCESS_TIMEOUT,
                    code = "PROCESS_TIMED_OUT",
                    message = "Process timed out",
                )
            val detector =
                AdbDeviceCapabilityDetector(Path.of("adb")) { _, _ ->
                    ProcessRunResult.Failed(expected)
                }

            val result = assertIs<StudioResult.Failure>(detector.detect(properties(sdkInt = 35)))

            assertSame(expected, result.error)
        }

    private fun detector(
        uidOutput: String,
        simpleperfOutput: String,
        buildTypeOutput: String = "user",
    ): AdbDeviceCapabilityDetector =
        AdbDeviceCapabilityDetector(Path.of("adb")) { request, _ ->
            when (request.arguments.takeLast(2)) {
                listOf("id", "-u") -> completed(request, uidOutput)
                listOf("getprop", "ro.build.type") -> completed(request, buildTypeOutput)
                listOf("simpleperf", "--version") -> completed(request, simpleperfOutput)
                else -> completed(request, "  cpu-clock\n  cpu-cycles\n")
            }
        }

    private fun properties(sdkInt: Int): AndroidDeviceProperties =
        AndroidDeviceProperties(
            serial = "serial-1",
            model = "Pixel",
            abis = listOf("arm64-v8a"),
            sdkInt = sdkInt,
            androidVersion = "15",
        )

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

    private fun failed(
        category: ErrorCategory,
        code: String,
        message: String,
    ): ProcessRunResult.Failed =
        ProcessRunResult.Failed(
            StudioError(
                category = category,
                code = code,
                message = message,
            ),
        )
}
