package com.androidperformancestudio.parser

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.CapturedProcessText
import com.androidperformancestudio.toolchain.ProcessOutput
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HostSimpleperfLocatorTest {
    @Test
    fun `prefers configured executable and verifies version and digest`() =
        runBlocking {
            val executable = Files.createTempFile("simpleperf-configured-", "")
            executable.writeText("configured")
            val requests = mutableListOf<ProcessRequest>()
            val locator =
                HostSimpleperfLocator(
                    configuredExecutable = executable,
                    bundledExecutable = null,
                    pathDirectories = emptyList(),
                    processInvocation = { request, _ ->
                        requests += request
                        completed(request, "simpleperf 1.2.3")
                    },
                )

            val result = assertIs<StudioResult.Success<HostSimpleperf>>(locator.locate())

            assertEquals(executable, result.value.executable)
            assertEquals(HostSimpleperfSource.CONFIGURED, result.value.source)
            assertEquals("simpleperf 1.2.3", result.value.version)
            assertEquals(64, result.value.sha256.length)
            assertEquals(listOf("--version"), requests.single().arguments)
        }

    @Test
    fun `rejects a bundled executable when its manifest digest differs`() =
        runBlocking {
            val executable = Files.createTempFile("simpleperf-bundled-", "")
            executable.writeText("bundled")
            val locator =
                HostSimpleperfLocator(
                    configuredExecutable = null,
                    bundledExecutable = BundledHostSimpleperf(executable, "0".repeat(64)),
                    pathDirectories = emptyList(),
                    processInvocation = { request, _ -> completed(request, "simpleperf") },
                )

            val failure = assertIs<StudioResult.Failure>(locator.locate())

            assertEquals(ErrorCategory.DATA_VALIDATION, failure.error.category)
            assertEquals("HOST_SIMPLEPERF_HASH_MISMATCH", failure.error.code)
        }

    @Test
    fun `falls back to executable on path and propagates version failure`() =
        runBlocking {
            val directory = Files.createTempDirectory("simpleperf-path-")
            val executable = directory.resolve(HostSimpleperfLocator.executableName()).also { it.writeText("path") }
            val expected =
                StudioError(
                    ErrorCategory.PROCESS_EXIT,
                    "PROCESS_EXIT_1",
                    "broken executable",
                )
            val locator =
                HostSimpleperfLocator(
                    configuredExecutable = null,
                    bundledExecutable = null,
                    pathDirectories = listOf(directory),
                    processInvocation = { request, _ -> ProcessRunResult.Failed(expected, output(request, 1, "")) },
                )

            val failure = assertIs<StudioResult.Failure>(locator.locate())

            assertEquals(expected, failure.error)
            assertEquals(executable, locator.candidates().single().executable)
        }

    @Test
    fun `returns configuration error when no candidate exists`() =
        runBlocking {
            val failure =
                assertIs<StudioResult.Failure>(
                    HostSimpleperfLocator(null, null, emptyList()).locate(),
                )

            assertEquals("HOST_SIMPLEPERF_NOT_FOUND", failure.error.code)
        }

    private fun completed(
        request: ProcessRequest,
        stdout: String,
    ): ProcessRunResult.Completed = ProcessRunResult.Completed(output(request, 0, stdout))

    private fun output(
        request: ProcessRequest,
        exitCode: Int,
        stdout: String,
    ): ProcessOutput =
        ProcessOutput(
            pid = 1,
            command = request.command,
            exitCode = exitCode,
            stdout = CapturedProcessText(stdout, false),
            stderr = CapturedProcessText("", false),
            startedAt = Instant.EPOCH,
            finishedAt = Instant.EPOCH,
        )
}
