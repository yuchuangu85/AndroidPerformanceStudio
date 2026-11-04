package com.androidperformancestudio.memory.app

import com.androidperformancestudio.platform.perfetto.TraceProcessorToolResolver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PerfettoSampledArtArtifactAnalyzerTest {
    @Test
    fun `local emulator ART trace has verified positive samples`() =
        runBlocking {
            val trace = System.getenv("APS_ART_TRACE_FIXTURE")?.let(Path::of) ?: return@runBlocking
            assertTrue(Files.isRegularFile(trace), "Missing ART trace fixture: $trace")
            val tool = Path.of("..", "..", "build", "perfetto-tools", "trace_processor_shell").toAbsolutePath()
            val result =
                PerfettoSampledArtArtifactAnalyzer(
                    TraceProcessorToolResolver(configuredOverride = tool),
                ).analyze(trace)
            val analyzed = assertIs<SampledArtProcessingResult.Success>(result).analysis
            assertTrue(analyzed.sampleRows > 0)
            assertTrue(analyzed.sampledObjectCount > 0)
            assertTrue(analyzed.sampledBytes > 0)
            assertTrue(analyzed.callSites.isNotEmpty())
            println(
                "ART_SAMPLED_ANALYSIS rows=${analyzed.sampleRows} " +
                    "objects=${analyzed.sampledObjectCount} bytes=${analyzed.sampledBytes} " +
                    "sites=${analyzed.callSites.size}",
            )
        }

    @Test
    fun `missing Trace Processor preserves capture with unavailable analysis`() =
        runBlocking {
            val trace = Files.createTempFile("art-sampled-empty", ".pftrace")
            try {
                val resolver =
                    TraceProcessorToolResolver(configuredOverride = trace.resolveSibling("missing-processor"))
                val result = PerfettoSampledArtArtifactAnalyzer(resolver).analyze(trace)
                assertIs<SampledArtProcessingResult.Unavailable>(result)
            } finally {
                Files.deleteIfExists(trace)
            }
            Unit
        }

    @Test
    fun `pinned Trace Processor distinguishes zero ART samples from unavailable analysis`() =
        runBlocking {
            val tool = Path.of("..", "..", "build", "perfetto-tools", "trace_processor_shell").toAbsolutePath()
            assumeTrue(Files.isExecutable(tool), "Pinned Trace Processor binary is not installed")
            val trace = Files.createTempFile("art-sampled-empty", ".pftrace")
            try {
                val result =
                    PerfettoSampledArtArtifactAnalyzer(
                        TraceProcessorToolResolver(configuredOverride = tool),
                    ).analyze(trace)
                val analyzed = assertIs<SampledArtProcessingResult.Success>(result)
                assertEquals(0L, analyzed.analysis.sampleRows)
                assertEquals(0L, analyzed.analysis.sampledBytes)
                assertEquals(emptyList(), analyzed.analysis.callSites)
            } finally {
                Files.deleteIfExists(trace)
            }
        }
}
