package com.androidperformancestudio.presentation

import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackWeightType
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.profileanalysis.FrameImplementation
import com.androidperformancestudio.profileanalysis.firefoxCategoryName

internal data class FlameGraphTooltipCategorySamples(
    val category: String,
    val running: Long,
    val self: Long,
    val inclusiveWeight: Long = running,
    val selfWeight: Long = self,
)

internal data class FlameGraphTooltipFacts(
    val function: String,
    val category: String?,
    val implementation: FrameImplementation,
    val resource: String?,
    val inclusiveWeight: Long,
    val selfWeight: Long,
    val sampleCount: Long,
    val selfSampleCount: Long,
    val categorySamples: List<FlameGraphTooltipCategorySamples>,
    val threadCount: Int,
    val percentage: Double,
    val previewRangeWeight: Long?,
    val weightType: CallStackWeightType = CallStackWeightType.NANOSECONDS,
    val tracedInclusiveNanos: Long? = null,
)

@Suppress("ReturnCount")
internal fun FlameGraphSnapshot.tooltipFacts(nodeId: FlameCallNodeId): FlameGraphTooltipFacts? {
    val index = callNodes.indexOf(nodeId) ?: return null
    val frame = callNodes.frameAt(index) ?: return null
    val inclusive = callNodes.inclusiveWeightAt(index) ?: return null
    val self = callNodes.selfWeightAt(index) ?: return null
    val samples = callNodes.sampleCountAt(index) ?: return null
    val threads = callNodes.threadCountAt(index) ?: return null
    val category = callNodes.firefoxCategoryAt(index)
    val selfSamples = callNodes.selfSampleCountAt(index)
    val categorySamples = callNodes.firefoxCategorySamplesAt(index)
    val percentage = if (totalWeight > 0) inclusive.toDouble() / totalWeight * PERCENT_SCALE else 0.0
    return FlameGraphTooltipFacts(
        function = frame.symbolName,
        category = category.takeIf(String::isNotBlank),
        implementation = frame.implementation,
        resource = frame.resource.takeIf(String::isNotBlank),
        inclusiveWeight = inclusive,
        selfWeight = self,
        sampleCount = samples,
        selfSampleCount = selfSamples,
        categorySamples = categorySamples,
        threadCount = threads,
        percentage = percentage.takeIf(Double::isFinite) ?: 0.0,
        previewRangeWeight = inclusive.takeIf { query.previewRange != null },
        weightType = weightType,
        tracedInclusiveNanos = callNodes.tracedInclusiveNanosAt(index),
    )
}

private fun CallNodeTable.firefoxCategorySamplesAt(rootIndex: Int): List<FlameGraphTooltipCategorySamples> {
    val samplesByCategory = LinkedHashMap<String, CategorySampleAccumulator>()
    categoryMetricsAt(rootIndex)?.let { metrics ->
        metrics.forEach { value ->
            val category = value.category ?: FIREFOX_CATEGORY_OTHER
            val total = samplesByCategory.getOrPut(category) { CategorySampleAccumulator() }
            total.running += value.sampleCount
            total.self += value.selfSampleCount
            total.runningWeight += value.inclusiveWeight
            total.selfWeight += value.selfWeight
        }
        return samplesByCategory.categorySamples()
    }
    val pending = ArrayDeque<Int>()
    pending.addLast(rootIndex)
    while (pending.isNotEmpty()) {
        val nodeIndex = pending.removeLast()
        val selfSamples = selfSampleCountAt(nodeIndex)
        if (selfSamples > 0) {
            val category = firefoxCategoryAt(nodeIndex)
            val accumulator = samplesByCategory.getOrPut(category) { CategorySampleAccumulator() }
            accumulator.running += selfSamples
            accumulator.runningWeight += selfWeightAt(nodeIndex) ?: 0L
            if (nodeIndex == rootIndex) accumulator.self += selfSamples
            if (nodeIndex == rootIndex) accumulator.selfWeight += selfWeightAt(nodeIndex) ?: 0L
        }
        childIndexes(nodeIndex).forEach(pending::addLast)
    }
    return samplesByCategory.categorySamples()
}

private fun Map<String, CategorySampleAccumulator>.categorySamples(): List<FlameGraphTooltipCategorySamples> =
    this
        .map { (category, samples) ->
            FlameGraphTooltipCategorySamples(
                category,
                samples.running,
                samples.self,
                samples.runningWeight,
                samples.selfWeight,
            )
        }.sortedWith(
            compareBy(
                { samples -> firefoxCategoryOrder.indexOf(samples.category).takeIf { it >= 0 } ?: Int.MAX_VALUE },
                FlameGraphTooltipCategorySamples::category,
            ),
        )

private fun CallNodeTable.childIndexes(nodeIndex: Int): List<Int> =
    buildList {
        var childIndex = firstChildIndexAt(nodeIndex)
        while (childIndex != null) {
            add(childIndex)
            childIndex = nextSiblingIndexAt(childIndex)
        }
    }

private fun CallNodeTable.firefoxCategoryAt(nodeIndex: Int): String =
    categoryAt(nodeIndex)?.takeIf(String::isNotBlank)
        ?: frameAt(nodeIndex)?.firefoxCategoryName()
        ?: FIREFOX_CATEGORY_OTHER

private data class CategorySampleAccumulator(
    var running: Long = 0,
    var self: Long = 0,
    var runningWeight: Long = 0,
    var selfWeight: Long = 0,
)

private const val PERCENT_SCALE = 100.0
private const val FIREFOX_CATEGORY_USER = "User"
private const val FIREFOX_CATEGORY_KERNEL = "Kernel"
private const val FIREFOX_CATEGORY_NATIVE = "Native"
private const val FIREFOX_CATEGORY_DEX = "DEX"
private const val FIREFOX_CATEGORY_OAT = "OAT"
private const val FIREFOX_CATEGORY_OFF_CPU = "Off-CPU"
private const val FIREFOX_CATEGORY_OTHER = "Other"
private const val FIREFOX_CATEGORY_JIT = "JIT"
private val firefoxCategoryOrder =
    listOf(
        FIREFOX_CATEGORY_USER,
        FIREFOX_CATEGORY_KERNEL,
        FIREFOX_CATEGORY_NATIVE,
        FIREFOX_CATEGORY_DEX,
        FIREFOX_CATEGORY_OAT,
        FIREFOX_CATEGORY_OFF_CPU,
        FIREFOX_CATEGORY_OTHER,
        FIREFOX_CATEGORY_JIT,
    )
