package com.androidperformancestudio.capture

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.CapturedProcessText
import com.androidperformancestudio.toolchain.ProcessOutput
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DeviceSimpleperfManagerTest {
    private val asset =
        BundledSimpleperfAsset(
            abi = "arm64-v8a",
            executable = Path.of("/bundle/android/arm64-v8a/simpleperf"),
            sha256 = "a".repeat(64),
        )

    @Test
    fun `uses device simpleperf without probing bundled assets`() =
        runBlocking {
            var invocationCount = 0
            val manager =
                manager { request ->
                    invocationCount += 1
                    completed(request, "unexpected")
                }

            val result =
                assertIs<StudioResult.Success<PreparedSimpleperf>>(
                    manager.prepare(
                        serial = "serial-1",
                        availability = DeviceSimpleperfAvailability("simpleperf 1.0", listOf("arm64-v8a")),
                    ),
                ).value

            assertEquals(SimpleperfSource.DEVICE, result.source)
            assertEquals("simpleperf", result.devicePath)
            assertEquals(0, invocationCount)
        }

    @Test
    fun `reuses a bundled simpleperf with matching remote checksum`() =
        runBlocking {
            val requests = mutableListOf<ProcessRequest>()
            val manager =
                manager { request ->
                    requests += request
                    completed(request, "${asset.sha256}  /data/local/tmp/aps/simpleperf\n")
                }

            val result =
                assertIs<StudioResult.Success<PreparedSimpleperf>>(
                    manager.prepare("serial-1", DeviceSimpleperfAvailability(null, listOf("arm64-v8a"))),
                ).value

            assertEquals(SimpleperfSource.BUNDLED_EXISTING, result.source)
            assertEquals(1, requests.size)
            assertEquals(
                listOf("-s", "serial-1", "shell", "sha256sum", "/data/local/tmp/aps/simpleperf"),
                requests.single().arguments,
            )
        }

    @Test
    fun `pushes chmods and verifies a missing bundled simpleperf`() =
        runBlocking {
            val requests = mutableListOf<ProcessRequest>()
            val manager =
                manager { request ->
                    requests += request
                    when (requests.size) {
                        1 -> failedExit()
                        5 -> completed(request, "simpleperf 1.0")
                        else -> completed(request, "")
                    }
                }

            val result =
                assertIs<StudioResult.Success<PreparedSimpleperf>>(
                    manager.prepare("serial-1", DeviceSimpleperfAvailability(null, listOf("arm64-v8a"))),
                ).value

            assertEquals(SimpleperfSource.BUNDLED_PUSHED, result.source)
            assertEquals(
                listOf(
                    listOf("-s", "serial-1", "shell", "sha256sum", "/data/local/tmp/aps/simpleperf"),
                    listOf("-s", "serial-1", "shell", "mkdir", "-p", "/data/local/tmp/aps"),
                    listOf("-s", "serial-1", "push", asset.executable.toString(), "/data/local/tmp/aps/simpleperf"),
                    listOf("-s", "serial-1", "shell", "chmod", "755", "/data/local/tmp/aps/simpleperf"),
                    listOf("-s", "serial-1", "shell", "/data/local/tmp/aps/simpleperf", "--version"),
                ),
                requests.map(ProcessRequest::arguments),
            )
        }

    @Test
    fun `returns a structured unsupported abi failure`() =
        runBlocking {
            val result =
                manager { request -> completed(request, "") }.prepare(
                    "serial-1",
                    DeviceSimpleperfAvailability(null, listOf("riscv64")),
                )

            val failure = assertIs<StudioResult.Failure>(result)
            assertEquals(ErrorCategory.CONFIGURATION, failure.error.category)
            assertEquals("BUNDLED_SIMPLEPERF_ABI_UNAVAILABLE", failure.error.code)
        }

    private fun manager(invocation: suspend (ProcessRequest) -> ProcessRunResult): DeviceSimpleperfManager =
        DeviceSimpleperfManager(
            adbExecutable = Path.of("adb"),
            assets = listOf(asset),
            processInvocation = { request, _ -> invocation(request) },
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

    private fun failedExit(): ProcessRunResult.Failed =
        ProcessRunResult.Failed(
            com.androidperformancestudio.model.StudioError(
                category = ErrorCategory.PROCESS_EXIT,
                code = "PROCESS_EXIT_1",
                message = "not found",
            ),
        )
}
