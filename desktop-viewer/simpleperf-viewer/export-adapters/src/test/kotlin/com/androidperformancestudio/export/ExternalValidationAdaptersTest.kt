package com.androidperformancestudio.export

import com.androidperformancestudio.platform.toolchain.HostCapturedText
import com.androidperformancestudio.platform.toolchain.HostCommandOutput
import com.androidperformancestudio.platform.toolchain.HostCommandResult
import com.androidperformancestudio.storage.TopFunction
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExternalValidationAdaptersTest {
    @Test
    fun `simpleperf report adapter builds safe argument list and parses rows`() =
        runTest {
            val executable = Files.createTempFile("simpleperf", "")
            val perfData = Files.createTempFile("perf", ".data").also { it.writeText("raw") }
            var command = emptyList<String>()
            val runner =
                ExternalProcessRunner { request, _ ->
                    command = request.command
                    completed("Overhead  Command  Shared Object  Symbol\n 60.00% app lib.so renderFrame\n")
                }

            val result = SimpleperfReportAdapter(executable, runner).generate(perfData)

            assertEquals(
                listOf(executable.toString(), "report", "-i", perfData.toString(), "--sort", "symbol"),
                command,
            )
            val success = assertIs<ExternalValidationResult.Success>(result)
            assertEquals("renderFrame", success.reportRows.single().symbolName)
            assertEquals(60.0, success.reportRows.single().overheadPercent)
        }

    @Test
    fun `html adapter verifies generated output`() =
        runTest {
            val python = Files.createTempFile("python", "")
            val script = Files.createTempFile("report_html", ".py")
            val perfData = Files.createTempFile("perf", ".data").also { it.writeText("raw") }
            val output = Files.createTempFile("report", ".html")
            val runner =
                ExternalProcessRunner { request, _ ->
                    request.arguments
                        .windowed(2)
                        .first { it.first() == "-o" }[1]
                        .let {
                            java.nio.file.Path
                                .of(it)
                                .writeText("html")
                        }
                    completed("")
                }

            val result = ReportHtmlAdapter(python, script, runner).generate(perfData, output)

            assertIs<ExternalValidationResult.Success>(result)
            assertTrue(output.exists())
        }

    @Test
    fun `compares local exclusive weights with simpleperf overhead using an explicit tolerance`() {
        val local =
            listOf(
                TopFunction("renderFrame", "libui.so", 80, 60, 8, 1),
                TopFunction("worker", "libapp.so", 50, 40, 5, 2),
            )
        val external =
            listOf(
                SimpleperfReportRow(59.5, "libui.so", "renderFrame"),
                SimpleperfReportRow(40.5, "libapp.so", "worker"),
            )

        val comparison = ExternalStatisticsComparator.compare(local, external, tolerancePercentagePoints = 1.0)

        assertTrue(comparison.withinTolerance)
        assertEquals(0.5, comparison.maximumDeviationPercentagePoints)
        assertEquals(emptyList(), comparison.mismatchedSymbols)
    }

    private fun completed(stdout: String): HostCommandResult.Completed =
        HostCommandResult.Completed(
            HostCommandOutput(
                1,
                emptyList(),
                0,
                HostCapturedText(stdout, false),
                HostCapturedText("", false),
                Instant.EPOCH,
                Instant.EPOCH,
            ),
        )
}
