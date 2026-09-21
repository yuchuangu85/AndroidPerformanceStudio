package com.androidperformancestudio.capture

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibSimpleperfReportAdapterTest {
    @Test
    fun `passes library record and output to bridge backend`() {
        val directory = Files.createTempDirectory("libsimpleperf-report")
        val library = Files.write(directory.resolve("libsimpleperf_report.so"), byteArrayOf(1))
        val bridge = Files.write(directory.resolve("bridge"), byteArrayOf(1))
        val record = Files.write(directory.resolve("perf.data"), byteArrayOf(2))
        val output = directory.resolve("samples.pb")
        var command = emptyList<String>()
        val adapter =
            LibSimpleperfReportAdapter(library, bridge) { arguments, _ ->
                command = arguments
                Files.write(output, byteArrayOf(3))
                LibSimpleperfBridgeResult(0, "converted")
            }

        val result = adapter.convert(record, output)

        assertEquals("converted", result.stdout)
        assertTrue("--library" in command)
        assertTrue("--record" in command)
        assertTrue("--output" in command)
    }

    @Test
    fun `deletes stale output before validating bridge result`() {
        val directory = Files.createTempDirectory("libsimpleperf-stale-output")
        val library = Files.write(directory.resolve("libsimpleperf_report.so"), byteArrayOf(1))
        val bridge = Files.write(directory.resolve("bridge"), byteArrayOf(1))
        val record = Files.write(directory.resolve("perf.data"), byteArrayOf(2))
        val output = Files.write(directory.resolve("samples.pb"), byteArrayOf(9))
        val adapter =
            LibSimpleperfReportAdapter(library, bridge) { _, _ ->
                LibSimpleperfBridgeResult(0, "did not write output")
            }

        assertFailsWith<IllegalStateException> { adapter.convert(record, output) }
        assertFalse(Files.exists(output))
    }
}
