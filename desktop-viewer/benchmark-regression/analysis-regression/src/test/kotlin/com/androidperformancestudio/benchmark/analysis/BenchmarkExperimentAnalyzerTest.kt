package com.androidperformancestudio.benchmark.analysis

import com.androidperformancestudio.benchmark.model.BaselineProfileEvidence
import com.androidperformancestudio.benchmark.model.BaselineProfileStatus
import com.androidperformancestudio.benchmark.model.BenchmarkBuild
import com.androidperformancestudio.benchmark.model.BenchmarkCase
import com.androidperformancestudio.benchmark.model.BenchmarkDevice
import com.androidperformancestudio.benchmark.model.BenchmarkMetric
import com.androidperformancestudio.benchmark.model.BenchmarkRun
import com.androidperformancestudio.benchmark.model.EvidenceConfidence
import com.androidperformancestudio.benchmark.model.MetricDirection
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchmarkExperimentAnalyzerTest {
    @Test
    fun `compares profile state and pairs traces by case`() {
        fun run(
            status: BaselineProfileStatus,
            metric: Double,
            trace: String,
        ) = BenchmarkRun(
            sourceFile = Path.of("result.json"),
            benchmarkDataVersion = 1,
            benchmarkLibraryVersion = "1",
            device = BenchmarkDevice("Pixel", "Google", 35, null, "arm64", null, 8, true),
            build = BenchmarkBuild("com.example.app", null, null, "release", null, null),
            cases =
                listOf(
                    BenchmarkCase(
                        "ExampleBenchmark",
                        "scroll",
                        "com.example.app",
                        "speed-profile",
                        null,
                        5,
                        listOf(
                            BenchmarkMetric(
                                "frameDurationMs",
                                "ms",
                                MetricDirection.LOWER_IS_BETTER,
                                listOf(metric),
                                metric,
                                metric,
                                metric,
                                EvidenceConfidence.EXACT,
                            ),
                        ),
                        listOf(Path.of(trace)),
                        baselineProfile = BaselineProfileEvidence(status = status),
                    ),
                ),
        )
        val result = BenchmarkExperimentAnalyzer().compareBaselineProfile(
            run(BaselineProfileStatus.MISSING, 20.0, "before.pftrace"),
            run(BaselineProfileStatus.VERIFIED, 15.0, "after.pftrace"),
        )
        assertEquals(BaselineProfileStatus.MISSING, result.baselineStatus)
        assertEquals(BaselineProfileStatus.VERIFIED, result.profiledStatus)
        assertEquals(listOf(Path.of("before.pftrace")), result.tracePairs.single().before)
        assertTrue(result.report.comparisons.isNotEmpty())
    }

    @Test
    fun `does not report a partially verified run as verified`() {
        fun benchmarkCase(name: String, status: BaselineProfileStatus) =
            BenchmarkCase(
                "ExampleBenchmark",
                name,
                "com.example.app",
                "speed-profile",
                null,
                5,
                listOf(
                    BenchmarkMetric(
                        "frameDurationMs",
                        "ms",
                        MetricDirection.LOWER_IS_BETTER,
                        listOf(10.0),
                        10.0,
                        10.0,
                        10.0,
                        EvidenceConfidence.EXACT,
                    ),
                ),
                emptyList(),
                baselineProfile = BaselineProfileEvidence(status = status),
            )

        fun run(cases: List<BenchmarkCase>) =
            BenchmarkRun(
                sourceFile = Path.of("result.json"),
                benchmarkDataVersion = 1,
                benchmarkLibraryVersion = "1",
                device = BenchmarkDevice("Pixel", "Google", 35, null, "arm64", null, 8, true),
                build = BenchmarkBuild("com.example.app", null, null, "release", null, null),
                cases = cases,
            )

        val result =
            BenchmarkExperimentAnalyzer().compareBaselineProfile(
                run(
                    listOf(
                        benchmarkCase("startup", BaselineProfileStatus.VERIFIED),
                        benchmarkCase("scroll", BaselineProfileStatus.MISSING),
                    ),
                ),
                run(
                    listOf(
                        benchmarkCase("startup", BaselineProfileStatus.VERIFIED),
                        benchmarkCase("scroll", BaselineProfileStatus.VERIFIED),
                    ),
                ),
            )

        assertEquals(BaselineProfileStatus.MISSING, result.baselineStatus)
        assertEquals(BaselineProfileStatus.VERIFIED, result.profiledStatus)
    }
}
