@file:Suppress("MaxLineLength", "ktlint:standard:max-line-length")

package com.androidperformancestudio.application

import com.androidperformancestudio.storage.TopFunction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AutomationServicesTest {
    @Test
    fun `batch executor supports fail fast`() =
        kotlinx.coroutines.test.runTest {
            val directories =
                listOf(Files.createTempDirectory("batch-a"), Files.createTempDirectory("batch-b"), Files.createTempDirectory("batch-c"))
            val executor =
                BatchProfileExecutor { directory ->
                    BatchProfileResult(directory, directory != directories[1], directory.fileName.toString())
                }
            val result = executor.execute(BatchProfilePlan(directories, failFast = true))
            assertEquals(2, result.size)
            assertEquals(false, result.last().success)
        }

    @Test
    fun `ci comparator classifies hotspot regressions`() {
        val baseline = listOf(function("foo", 100))
        val current = listOf(function("foo", 140), function("bar", 10))
        val result = CiProfileComparator.compare(baseline, current, thresholdPercent = 10.0)
        assertEquals(1, result.regressionCount)
        assertEquals(CiRegressionClassification.NEW, result.functions.first { it.symbolName == "bar" }.classification)
    }

    @Test
    fun `symbol resolver caches file based symbols`() {
        val source = Files.createTempDirectory("symbol-source")
        val cache = Files.createTempDirectory("symbol-cache")
        val buildId = "0123456789abcdef"
        val symbolDir = Files.createDirectories(source.resolve(buildId))
        Files.writeString(symbolDir.resolve("libfoo.so"), "symbols")
        val resolved = SymbolServerResolver(listOf(source.toUri()), cache).resolve(buildId, "libfoo.so")
        assertTrue(Files.isRegularFile(resolved))
        assertEquals("symbols", Files.readString(resolved))
    }

    @Test
    fun `symbol resolver rejects unsafe filenames`() {
        val cache = Files.createTempDirectory("symbol-cache")
        val resolver = SymbolServerResolver(emptyList(), cache)

        assertFailsWith<IllegalArgumentException> {
            resolver.resolve("0123456789abcdef", "../outside.so")
        }
    }

    private fun function(
        name: String,
        weight: Long,
    ) = TopFunction(name, "lib.so", weight, weight, 1, 1)

    @Test
    fun `batch capture repeats targets and stops on failure`() =
        kotlinx.coroutines.test.runTest {
            val executor =
                BatchCaptureExecutor { target, iteration ->
                    BatchCaptureArtifact(
                        targetId = target.id,
                        iteration = iteration,
                        sessionDirectory = null,
                        success = iteration < 2,
                        message = "iteration-$iteration",
                    )
                }
            val result =
                executor.execute(
                    BatchCapturePlan(
                        targets = listOf(BatchCaptureTarget("pixel", repeatCount = 3)),
                        failFast = true,
                    ),
                )
            assertEquals(2, result.size)
            assertEquals(false, result.last().success)
        }
}
