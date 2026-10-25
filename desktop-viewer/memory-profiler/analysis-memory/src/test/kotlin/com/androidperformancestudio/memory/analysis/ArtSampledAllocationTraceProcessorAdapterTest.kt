package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.platform.perfetto.TraceQueryResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtSampledAllocationTraceProcessorAdapterTest {
    private val adapter = ArtSampledAllocationTraceProcessorAdapter()

    @Test
    fun `maps only sampled ART allocation totals and stack evidence`() {
        val result =
            adapter.mapFixture(
                allocationsCsv =
                    """|callsite_id,sample_rows,sampled_count,sampled_bytes
                    |11,2,3,128
                    |20,1,1,32
                    """.trimMargin(),
                callStacksCsv =
                    """|callsite_id,parent_id,frame_name,symbolized
                    |10,[NULL],com.example.Main.run,1
                    |11,10,com.example.Work.allocate,1
                    """.trimMargin(),
            )

        assertEquals(3, result.sampleRows)
        assertEquals(4, result.sampledObjectCount)
        assertEquals(160, result.sampledBytes)
        assertEquals("perfetto/android.heapprofd/com.android.art", result.source)
        assertEquals(listOf("com.example.Main.run", "com.example.Work.allocate"), result.callSites.first().callStack)
        assertTrue(result.callSites.first().hasSymbolizedFrame)
        assertTrue(
            result.callSites
                .last()
                .callStack
                .isEmpty(),
        )
        assertFalse(result.hasDeallocationEvents)
        assertFalse(result.hasGcEvents)
        assertFalse(result.hasObjectClassNames)
        assertFalse(result.hasFullAllocationCoverage)
        assertTrue(adapter.allocationQuery.sql.contains("heap_name = 'com.android.art'"))
        assertTrue(adapter.allocationQuery.sql.contains("count > 0 AND size > 0"))
    }

    @Test
    fun `empty ART allocation trace remains explicitly empty`() {
        val result =
            adapter.mapFixture(
                "callsite_id,sample_rows,sampled_count,sampled_bytes\n",
                "callsite_id,parent_id,frame_name,symbolized\n",
            )

        assertEquals(0, result.sampleRows)
        assertEquals(0, result.sampledObjectCount)
        assertEquals(0, result.sampledBytes)
        assertTrue(result.callSites.isEmpty())
    }

    @Test
    fun `mapper does not turn negative or zero rows into sampled allocations`() {
        val result =
            adapter.map(
                listOf(
                    ArtSampledAllocationRow(1, 1, -1, -64),
                    ArtSampledAllocationRow(2, 1, 0, 0),
                ),
                emptyList(),
            )

        assertEquals(0, result.sampleRows)
        assertTrue(result.callSites.isEmpty())
    }

    @Test
    fun `cycle in malformed callsite graph cannot hang stack reconstruction`() {
        val result =
            adapter.map(
                listOf(ArtSampledAllocationRow(2, 1, 1, 64)),
                listOf(
                    ArtSampledCallStackRow(1, 2, "first", true),
                    ArtSampledCallStackRow(2, 1, "second", true),
                ),
            )

        assertEquals(listOf("first", "second"), result.callSites.single().callStack)
    }

    /** Opt-in local acceptance: set APS_ART_SAMPLED_TRACE to an API 31+ ART heapprofd trace. */
    @Test
    fun `local ART trace maps through pinned Trace Processor when provided`() {
        val trace = System.getenv("APS_ART_SAMPLED_TRACE")?.let(Path::of) ?: return
        val tool =
            System.getenv("APS_TRACE_PROCESSOR")?.let(Path::of)
                ?: Path.of("../../build/perfetto-tools/trace_processor_shell")
        assertTrue(Files.isRegularFile(trace), "Missing trace: $trace")
        assertTrue(Files.isExecutable(tool), "Missing Trace Processor: $tool")

        val allocations =
            adapter.allocationQuery.map(
                TraceQueryResult.parse(query(tool, trace, adapter.allocationQuery.sql)),
            )
        val stacks =
            adapter.callStackQuery.map(
                TraceQueryResult.parse(query(tool, trace, adapter.callStackQuery.sql)),
            )
        val result = adapter.map(allocations, stacks)

        assertTrue(result.sampleRows > 0, "Trace contains no ART allocation samples")
        assertTrue(result.sampledBytes > 0)
        assertTrue(result.callSites.all { it.sampledBytes > 0 })
        println(
            "ART_SAMPLED rows=${result.sampleRows} objects=${result.sampledObjectCount} " +
                "bytes=${result.sampledBytes}",
        )
    }

    private fun query(
        tool: Path,
        trace: Path,
        sql: String,
    ): String {
        val process = ProcessBuilder(tool.toString(), "query", trace.toString(), sql).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val error = process.errorStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Trace Processor query failed: $error" }
        return output
    }
}
