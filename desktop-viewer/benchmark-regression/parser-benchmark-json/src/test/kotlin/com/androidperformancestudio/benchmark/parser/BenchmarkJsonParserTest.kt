package com.androidperformancestudio.benchmark.parser

import com.androidperformancestudio.benchmark.model.BaselineProfileStatus
import com.androidperformancestudio.benchmark.model.BenchmarkScenario
import com.androidperformancestudio.benchmark.model.MetricDirection
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchmarkJsonParserTest {
    @Test
    fun `parses benchmark data and preserves unknown metrics`() {
        val file = createTempFile(suffix = "-benchmarkData.json")
        file.writeText(
            """{"benchmarkDataVersion":3,"context":{"deviceModel":"Pixel 9","apiLevel":35,"abi":"arm64-v8a"},"benchmarks":[{"name":"dev.Example.startup","compilationMode":"Partial","metrics":{"timeToInitialDisplayMs":{"minimum":100,"median":110,"maximum":130,"runs":[100,110,130]},"customScore":{"median":42}}}]}""",
        )
        val run = BenchmarkJsonParser().parse(file)
        assertEquals("Pixel 9", run.device.model)
        assertEquals(
            2,
            run.cases
                .single()
                .metrics.size,
        )
        assertEquals(
            MetricDirection.LOWER_IS_BETTER,
            run.cases
                .single()
                .metrics
                .first()
                .direction,
        )
        assertTrue(
            run.cases
                .single()
                .metrics
                .last()
                .sourceFields
                .isNotEmpty(),
        )
    }

    @Test
    fun `preserves scenario profile and trace artifacts`() {
        val directory = kotlin.io.path.createTempDirectory("benchmark-evidence")
        val file = directory.resolve("benchmarkData.json")
        file.writeText(
            """{"benchmarkDataVersion":3,"benchmarks":[{"name":"dev.Example.scroll","scenario":"scroll","baselineProfile":{"status":"verified","path":"profiles/baseline-prof.txt","sha256":"abc"},"tracePaths":["traces/scroll.perfetto-trace"],"metrics":{"frameDurationMs":{"median":12,"runs":[11,12,13]}}}]}""",
        )

        val run = BenchmarkJsonParser().parse(file)
        val benchmarkCase = run.cases.single()

        assertEquals(BenchmarkScenario.SCROLL, benchmarkCase.scenario)
        assertEquals(BaselineProfileStatus.VERIFIED, benchmarkCase.baselineProfile?.status)
        assertEquals(directory.resolve("profiles/baseline-prof.txt"), benchmarkCase.baselineProfile?.artifact)
        assertEquals(
            setOf(
                file.toAbsolutePath().normalize(),
                directory.resolve("traces/scroll.perfetto-trace"),
                directory.resolve("profiles/baseline-prof.txt"),
            ),
            run.evidenceArtifacts.toSet(),
        )
    }
}
