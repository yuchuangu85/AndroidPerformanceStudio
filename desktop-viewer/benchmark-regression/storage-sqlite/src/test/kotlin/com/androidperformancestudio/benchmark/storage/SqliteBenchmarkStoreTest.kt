package com.androidperformancestudio.benchmark.storage

import com.androidperformancestudio.benchmark.model.*
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class SqliteBenchmarkStoreTest {
    @Test
    fun `persists run summary idempotently`() {
        val run = BenchmarkRun(
            sourceFile = Path.of("result.json"), benchmarkDataVersion = 1, benchmarkLibraryVersion = null,
            device = BenchmarkDevice("Pixel", null, 35, null, "arm64", null, null, true), build = BenchmarkBuild(null, null, null, null, null, null),
            cases = listOf(BenchmarkCase("Bench", "test", null, null, null, 1, listOf(BenchmarkMetric("timeMs", "ms", MetricDirection.LOWER_IS_BETTER, listOf(1.0), 1.0, 1.0, 1.0, EvidenceConfidence.EXACT)), emptyList())),
        )
        SqliteBenchmarkStore.open(createTempDirectory().resolve("db.sqlite")).use { store ->
            store.save(run)
            store.save(run)
            assertEquals(1, store.listRecent().size)
        }
    }
}
