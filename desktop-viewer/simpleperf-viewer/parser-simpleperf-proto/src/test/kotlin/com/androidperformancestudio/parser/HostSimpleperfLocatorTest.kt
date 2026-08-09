package com.androidperformancestudio.parser

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.toolchain.HostCapturedText
import com.androidperformancestudio.platform.toolchain.HostCommandOutput
import com.androidperformancestudio.platform.toolchain.HostCommandResult
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
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
            val requests = mutableListOf<HostProcessRequest>()
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
    fun `accepts ndk simpleperf version written to stderr`() =
        runBlocking {
            val executable = Files.createTempFile("simpleperf-ndk-", "")
            executable.writeText("ndk")
            val locator =
                HostSimpleperfLocator(
                    configuredExecutable = executable,
                    bundledExecutable = null,
                    pathDirectories = emptyList(),
                    processInvocation = { request, _ ->
                        completed(request, stdout = "", stderr = "simpleperf version 1.build.11421629")
                    },
                )

            val result = assertIs<StudioResult.Success<HostSimpleperf>>(locator.locate())

            assertEquals("simpleperf version 1.build.11421629", result.value.version)
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
                    processInvocation = { request, _ -> HostCommandResult.Failed(expected, output(request, 1, "")) },
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
        request: HostProcessRequest,
        stdout: String,
        stderr: String = "",
    ): HostCommandResult.Completed = HostCommandResult.Completed(output(request, 0, stdout, stderr))

    private fun output(
        request: HostProcessRequest,
        exitCode: Int,
        stdout: String,
        stderr: String = "",
    ): HostCommandOutput =
        HostCommandOutput(
            pid = 1,
            command = request.command,
            exitCode = exitCode,
            stdout = HostCapturedText(stdout, false),
            stderr = HostCapturedText(stderr, false),
            startedAt = Instant.EPOCH,
            finishedAt = Instant.EPOCH,
        )
}
