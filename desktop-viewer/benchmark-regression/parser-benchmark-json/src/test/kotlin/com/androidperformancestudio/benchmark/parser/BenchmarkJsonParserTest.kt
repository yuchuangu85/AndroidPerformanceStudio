package com.androidperformancestudio.benchmark.parser

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
}
