package com.androidperformancestudio.benchmark.export

import com.androidperformancestudio.benchmark.model.*
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class BenchmarkExportersTest {
    @Test
    fun `exports junit and sarif regressions`() {
        val report =
            RegressionReport(
                "b",
                "c",
                Instant.now(),
                listOf(
                    MetricComparison(
                        "Bench#test",
                        "timeMs",
                        "ms",
                        1.0,
                        2.0,
                        1.0,
                        100.0,
                        RegressionClassification.REGRESSED,
                        EvidenceConfidence.EXACT,
                        emptyList(),
                    ),
                ),
                emptyList(),
            )
        val dir = createTempDirectory()
        val junit = dir.resolve("out.xml")
        val sarif = dir.resolve("out.sarif")
        BenchmarkReportExporter().apply {
            writeJunit(report, junit)
            writeSarif(report, sarif)
        }
        assertTrue(junit.readText().contains("failure"))
        assertTrue(sarif.readText().contains("benchmark-regression"))
    }
}
