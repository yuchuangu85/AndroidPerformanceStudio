@file:Suppress("MagicNumber", "MaxLineLength", "ktlint:standard:max-line-length")

package com.androidperformancestudio.profileanalysis

import com.androidperformancestudio.model.ProfileCounterFact
import com.androidperformancestudio.model.ProfileSampleFact
import com.androidperformancestudio.model.ProfileSliceFact
import com.androidperformancestudio.model.ProfileSourceId

data class CpuHeatmapCell(
    val cpuCore: Int,
    val threadKey: String,
    val bucketStartNanos: Long,
    val bucketEndNanosExclusive: Long,
    val sampleCount: Long,
    val eventWeight: Long,
)

data class CpuSampleHeatmap(
    val bucketSizeNanos: Long,
    val cells: List<CpuHeatmapCell>,
    val maximumWeight: Long,
)

object CpuSampleHeatmapAnalyzer {
    fun analyze(
        samples: List<ProfileSampleFact>,
        bucketSizeNanos: Long,
    ): CpuSampleHeatmap {
        require(bucketSizeNanos > 0) { "bucketSizeNanos must be positive" }
        val cells =
            samples
                .groupBy { sample ->
                    val bucket = sample.time.timestampNanos / bucketSizeNanos * bucketSizeNanos
                    HeatmapKey(sample.cpuCore ?: UNKNOWN_CPU, sample.thread.stableKey(), bucket)
                }.map { (key, bucketSamples) ->
                    CpuHeatmapCell(
                        cpuCore = key.cpuCore,
                        threadKey = key.threadKey,
                        bucketStartNanos = key.bucketStartNanos,
                        bucketEndNanosExclusive = key.bucketStartNanos + bucketSizeNanos,
                        sampleCount = bucketSamples.size.toLong(),
                        eventWeight = bucketSamples.sumOf(ProfileSampleFact::eventCount),
                    )
                }.sortedWith(compareBy(CpuHeatmapCell::bucketStartNanos, CpuHeatmapCell::cpuCore, CpuHeatmapCell::threadKey))
        return CpuSampleHeatmap(bucketSizeNanos, cells, cells.maxOfOrNull(CpuHeatmapCell::eventWeight) ?: 0)
    }

    private data class HeatmapKey(
        val cpuCore: Int,
        val threadKey: String,
        val bucketStartNanos: Long,
    )

    private const val UNKNOWN_CPU = -1
}

data class DifferentialFlameNode(
    val path: List<String>,
    val baselineWeight: Long,
    val currentWeight: Long,
    val deltaWeight: Long,
    val relativeDeltaPercent: Double?,
)

data class DifferentialFlameGraph(
    val nodes: List<DifferentialFlameNode>,
    val baselineTotalWeight: Long,
    val currentTotalWeight: Long,
)

object DifferentialFlameGraphAnalyzer {
    fun compare(
        baseline: CallStackTable,
        current: CallStackTable,
    ): DifferentialFlameGraph {
        val baselineWeights = inclusivePathWeights(baseline)
        val currentWeights = inclusivePathWeights(current)
        val paths = baselineWeights.keys + currentWeights.keys
        val nodes =
            paths
                .map { path ->
                    val before = baselineWeights[path] ?: 0
                    val after = currentWeights[path] ?: 0
                    val delta = after - before
                    DifferentialFlameNode(
                        path = path,
                        baselineWeight = before,
                        currentWeight = after,
                        deltaWeight = delta,
                        relativeDeltaPercent = before.takeIf { it != 0L }?.let { delta.toDouble() / kotlin.math.abs(it) * 100.0 },
                    )
                }.sortedWith(
                    compareByDescending<DifferentialFlameNode> { kotlin.math.abs(it.deltaWeight) }.thenBy { it.path.joinToString() },
                )
        return DifferentialFlameGraph(
            nodes = nodes,
            baselineTotalWeight = baseline.stacks.sumOf(WeightedCallStack::weight),
            currentTotalWeight = current.stacks.sumOf(WeightedCallStack::weight),
        )
    }

    private fun inclusivePathWeights(table: CallStackTable): Map<List<String>, Long> =
        buildMap {
            table.stacks.forEach { stack ->
                val symbols = stack.frameIdsRootToLeaf.map { frameId -> table.frame(frameId).symbolName }
                symbols.indices.forEach { index ->
                    val path = symbols.take(index + 1)
                    put(path, getOrDefault(path, 0L) + stack.weight)
                }
            }
        }
}

enum class ThreadPoolKind { BINDER, ASYNC_TASK, COROUTINE, FORK_JOIN, RENDER, JIT, GENERIC_POOL, DEDICATED }

data class NormalizedThreadGroup(
    val key: String,
    val displayName: String,
    val kind: ThreadPoolKind,
)

object ThreadPoolNormalizer {
    fun normalize(name: String): NormalizedThreadGroup {
        val trimmed = name.trim().ifBlank { "<unnamed>" }
        return when {
            BINDER.containsMatchIn(trimmed) -> NormalizedThreadGroup("binder", "Binder pool", ThreadPoolKind.BINDER)
            ASYNC_TASK.containsMatchIn(trimmed) -> NormalizedThreadGroup("async-task", "AsyncTask pool", ThreadPoolKind.ASYNC_TASK)
            COROUTINE.containsMatchIn(trimmed) -> NormalizedThreadGroup("coroutine", "Coroutine dispatcher", ThreadPoolKind.COROUTINE)
            FORK_JOIN.containsMatchIn(trimmed) -> NormalizedThreadGroup("fork-join", "ForkJoin pool", ThreadPoolKind.FORK_JOIN)
            RENDER.containsMatchIn(trimmed) -> NormalizedThreadGroup("render", "Render threads", ThreadPoolKind.RENDER)
            JIT.containsMatchIn(trimmed) -> NormalizedThreadGroup("jit", "JIT thread pool", ThreadPoolKind.JIT)
            GENERIC_POOL.containsMatchIn(
                trimmed,
            ) -> NormalizedThreadGroup("generic-pool", "Generic worker pool", ThreadPoolKind.GENERIC_POOL)
            else -> NormalizedThreadGroup("thread:$trimmed", trimmed, ThreadPoolKind.DEDICATED)
        }
    }

    private val BINDER = Regex("(?i)^(Binder:|HwBinder:|binder[:_-])")
    private val ASYNC_TASK = Regex("(?i)AsyncTask|ModernAsyncTask")
    private val COROUTINE = Regex("(?i)DefaultDispatcher|kotlinx\\.coroutines|CoroutineScheduler")
    private val FORK_JOIN = Regex("(?i)ForkJoinPool")
    private val RENDER = Regex("(?i)RenderThread|hwuiTask")
    private val JIT = Regex("(?i)Jit thread pool")
    private val GENERIC_POOL = Regex("(?i)pool-\\d+-thread-\\d+|ThreadPoolExecutor")
}

data class FrequencySummary(
    val sourceId: ProfileSourceId,
    val name: String,
    val unit: String,
    val minimum: Double,
    val maximum: Double,
    val average: Double,
    val sampleCount: Int,
)

data class FrequencyGpuAnalysis(
    val cpuFrequencies: List<FrequencySummary>,
    val gpuFrequencies: List<FrequencySummary>,
    val frameSlices: List<ProfileSliceFact>,
)

object FrequencyGpuAnalyzer {
    fun analyze(
        counters: List<ProfileCounterFact>,
        slices: List<ProfileSliceFact> = emptyList(),
    ): FrequencyGpuAnalysis {
        val summaries =
            counters.groupBy { Triple(it.sourceId, it.name, it.unit) }.map { (identity, values) ->
                FrequencySummary(
                    sourceId = identity.first,
                    name = identity.second,
                    unit = identity.third,
                    minimum = values.minOf(ProfileCounterFact::value),
                    maximum = values.maxOf(ProfileCounterFact::value),
                    average = values.map(ProfileCounterFact::value).average(),
                    sampleCount = values.size,
                )
            }
        return FrequencyGpuAnalysis(
            cpuFrequencies = summaries.filter { it.name.contains("cpu", true) && it.name.contains("freq", true) },
            gpuFrequencies = summaries.filter { it.name.contains("gpu", true) && it.name.contains("freq", true) },
            frameSlices = slices.filter { it.name.contains("frame", true) || it.category?.name?.contains("graphics", true) == true },
        )
    }
}

private fun com.androidperformancestudio.model.ProfileThreadKey.stableKey(): String = "${sourceId.value}:${process.processId}:$threadId"
