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
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SimpleperfReportConverterTest {
    @Test
    fun `converts perf data to retained protobuf trace with callchains`() =
        runBlocking {
            val directory = Files.createTempDirectory("aps-convert-")
            val perfData = directory.resolve("perf.data").also { it.writeText("perf") }
            val output = directory.resolve("perf.trace")
            val requests = mutableListOf<ProcessRequest>()
            val converter =
                SimpleperfReportConverter(
                    processInvocation = { request, _ ->
                        requests += request
                        output.writeText("protobuf")
                        completed(request, stdout = "converted", stderr = "warning")
                    },
                )

            val result =
                converter.convert(
                    simpleperf = hostSimpleperf(),
                    request = SimpleperfConversionRequest(perfData, output),
                )

            val success = assertIs<StudioResult.Success<SimpleperfConversionResult>>(result)
            assertEquals(output, success.value.protobufTrace)
            assertEquals(
                listOf(
                    "report-sample",
                    "--protobuf",
                    "--show-callchain",
                    "-i",
                    perfData.toString(),
                    "-o",
                    output.toString(),
                ),
                requests.single().arguments,
            )
            assertTrue(output.exists())
            assertEquals("protobuf", output.readText())
            assertEquals("converted", success.value.stdout)
            assertEquals("warning", success.value.stderr)
        }

    @Test
    fun `rejects missing input without invoking simpleperf`() =
        runBlocking {
            var invoked = false
            val converter =
                SimpleperfReportConverter { request, _ ->
                    invoked = true
                    completed(request)
                }

            val failure =
                assertIs<StudioResult.Failure>(
                    converter.convert(
                        hostSimpleperf(),
                        SimpleperfConversionRequest(Path.of("missing-perf.data"), Path.of("output.trace")),
                    ),
                )

            assertEquals(ErrorCategory.IO, failure.error.category)
            assertEquals("PERF_DATA_NOT_FOUND", failure.error.code)
            assertEquals(false, invoked)
        }

    @Test
    fun `propagates process failure and retains its output`() =
        runBlocking {
            val perfData = Files.createTempFile("aps-perf-", ".data")
            val output = perfData.resolveSibling("failed.trace")
            val expected = StudioError(ErrorCategory.PROCESS_EXIT, "PROCESS_EXIT_1", "conversion failed")
            val converter =
                SimpleperfReportConverter { request, _ ->
                    ProcessRunResult.Failed(expected, processOutput(request, 1, "", "bad record"))
                }

            val result = converter.convert(hostSimpleperf(), SimpleperfConversionRequest(perfData, output))

            val failure = assertIs<StudioResult.Failure>(result)
            assertEquals(expected, failure.error)
            assertEquals(false, output.exists())
        }

    private fun hostSimpleperf() =
        HostSimpleperf(
            executable = Path.of("simpleperf"),
            version = "simpleperf 1.0",
            sha256 = "a".repeat(64),
            source = HostSimpleperfSource.CONFIGURED,
        )

    private fun completed(
        request: ProcessRequest,
        stdout: String = "",
        stderr: String = "",
    ): ProcessRunResult.Completed = ProcessRunResult.Completed(processOutput(request, 0, stdout, stderr))

    private fun processOutput(
        request: ProcessRequest,
        exitCode: Int,
        stdout: String,
        stderr: String,
    ): ProcessOutput =
        ProcessOutput(
            pid = 1,
            command = request.command,
            exitCode = exitCode,
            stdout = CapturedProcessText(stdout, false),
            stderr = CapturedProcessText(stderr, false),
            startedAt = Instant.EPOCH,
            finishedAt = Instant.EPOCH,
        )
}
