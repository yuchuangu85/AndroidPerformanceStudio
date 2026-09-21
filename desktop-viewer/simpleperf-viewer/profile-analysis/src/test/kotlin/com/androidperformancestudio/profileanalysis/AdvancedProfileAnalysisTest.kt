package com.androidperformancestudio.profileanalysis

import com.androidperformancestudio.model.ProfileClockDomain
import com.androidperformancestudio.model.ProfileCounterFact
import com.androidperformancestudio.model.ProfileProcessKey
import com.androidperformancestudio.model.ProfileSampleFact
import com.androidperformancestudio.model.ProfileSourceId
import com.androidperformancestudio.model.ProfileThreadKey
import com.androidperformancestudio.model.ProfileTimePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdvancedProfileAnalysisTest {
    @Test
    fun `builds cpu sample heatmap buckets`() {
        val source = ProfileSourceId("simpleperf")
        val process = ProfileProcessKey(source, 10)
        val thread = ProfileThreadKey(source, process, 11)
        val samples =
            listOf(
                ProfileSampleFact(source, time(10), thread, "cpu-cycles", 2, 1, true, null, emptyList(), null),
                ProfileSampleFact(source, time(90), thread, "cpu-cycles", 3, 1, true, null, emptyList(), null),
                ProfileSampleFact(source, time(110), thread, "cpu-cycles", 7, 2, true, null, emptyList(), null),
            )

        val heatmap = CpuSampleHeatmapAnalyzer.analyze(samples, 100)

        assertEquals(2, heatmap.cells.size)
        assertEquals(5, heatmap.cells.first().eventWeight)
        assertEquals(7, heatmap.maximumWeight)
    }

    @Test
    fun `compares flame paths by symbol identity`() {
        val baseline = table(10, "root", "before")
        val current = table(25, "root", "after")
        val diff = DifferentialFlameGraphAnalyzer.compare(baseline, current)

        assertEquals(15, diff.nodes.first { it.path == listOf("root") }.deltaWeight)
        assertTrue(diff.nodes.any { it.path.last() == "after" && it.deltaWeight > 0 })
    }

    @Test
    fun `normalizes common android thread pools`() {
        assertEquals(ThreadPoolKind.BINDER, ThreadPoolNormalizer.normalize("Binder:123_2").kind)
        assertEquals(ThreadPoolKind.ASYNC_TASK, ThreadPoolNormalizer.normalize("AsyncTask #4").kind)
        assertEquals(ThreadPoolKind.COROUTINE, ThreadPoolNormalizer.normalize("DefaultDispatcher-worker-1").kind)
    }

    @Test
    fun `separates cpu and gpu frequency counters`() {
        val source = ProfileSourceId("perfetto")
        val counters =
            listOf(
                ProfileCounterFact(source, time(1), "cpu0 frequency", "kHz", 1000.0),
                ProfileCounterFact(source, time(2), "cpu0 frequency", "kHz", 2000.0),
                ProfileCounterFact(source, time(2), "gpu frequency", "Hz", 500.0),
            )
        val result = FrequencyGpuAnalyzer.analyze(counters)
        assertEquals(source, result.cpuFrequencies.single().sourceId)
        assertEquals(1500.0, result.cpuFrequencies.single().average)
        assertEquals(500.0, result.gpuFrequencies.single().average)
    }

    @Test
    fun `keeps frequency summaries separate by source and unit`() {
        val first = ProfileSourceId("perfetto-a")
        val second = ProfileSourceId("perfetto-b")
        val counters =
            listOf(
                ProfileCounterFact(first, time(1), "cpu0 frequency", "kHz", 1000.0),
                ProfileCounterFact(second, time(2), "cpu0 frequency", "Hz", 2_000_000.0),
            )

        val result = FrequencyGpuAnalyzer.analyze(counters)

        assertEquals(setOf(first, second), result.cpuFrequencies.map(FrequencySummary::sourceId).toSet())
        assertEquals(setOf("kHz", "Hz"), result.cpuFrequencies.map(FrequencySummary::unit).toSet())
    }

    private fun table(
        weight: Long,
        rootName: String,
        leafName: String,
    ): CallStackTable =
        CallStackTable(
            framesById =
                mapOf(
                    1L to frame(1, rootName),
                    2L to frame(2, leafName),
                ),
            stacks = listOf(WeightedCallStack(1, 0, weight, "t", null, null, listOf(1, 2))),
        )

    private fun frame(
        id: Long,
        name: String,
    ): CallStackFrame = CallStackFrame(id, FlameFunctionId(id), name, "lib.so", 0, FrameImplementation.NATIVE)

    private fun time(value: Long) = ProfileTimePoint(ProfileClockDomain("boot"), value)
}
