package com.androidperformancestudio.benchmark.analysis

import com.androidperformancestudio.benchmark.model.*
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class RegressionAnalyzerTest {
    @Test
    fun `detects compatible slower regression`() {
        val baseline = run(listOf(100.0, 101.0, 99.0))
        val current = run(listOf(120.0, 119.0, 121.0))
        val result = RegressionAnalyzer().compare(baseline, current, RegressionPolicy(relativeThresholdPercent = 5.0))
        assertEquals(RegressionClassification.REGRESSED, result.comparisons.single().classification)
    }

    @Test
    fun `rejects cross device comparison`() {
        val baseline = run(listOf(100.0, 101.0, 99.0))
        val current = run(listOf(120.0, 119.0, 121.0), model = "Other")
        val result = RegressionAnalyzer().compare(baseline, current, RegressionPolicy(relativeThresholdPercent = 5.0))
        assertEquals(RegressionClassification.INCOMPATIBLE, result.comparisons.single().classification)
    }

    private fun run(
        samples: List<Double>,
        model: String = "Pixel",
    ) = BenchmarkRun(
        sourceFile = Path.of("result.json"),
        benchmarkDataVersion = 1,
        benchmarkLibraryVersion = "1.4",
        device = BenchmarkDevice(model, "Google", 35, "15", "arm64-v8a", "build", 8, true),
        build = BenchmarkBuild("dev.app", "1", 1, "benchmark", null, null),
        cases =
            listOf(
                BenchmarkCase(
                    "dev.Bench",
                    "startup",
                    "dev.app",
                    "Partial",
                    "COLD",
                    3,
                    listOf(
                        BenchmarkMetric(
                            "timeToInitialDisplayMs",
                            "ms",
                            MetricDirection.LOWER_IS_BETTER,
                            samples,
                            samples.minOrNull(),
                            samples.sorted()[1],
                            samples.maxOrNull(),
                            EvidenceConfidence.EXACT,
                        ),
                    ),
                    emptyList(),
                ),
            ),
    )
}
