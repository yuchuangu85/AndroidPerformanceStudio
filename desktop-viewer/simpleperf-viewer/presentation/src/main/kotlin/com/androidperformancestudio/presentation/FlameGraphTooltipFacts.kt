package com.androidperformancestudio.presentation

import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.profileanalysis.FrameImplementation

internal data class FlameGraphTooltipCategorySamples(
    val category: String,
    val running: Long,
    val self: Long,
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
    )
}

private fun CallNodeTable.firefoxCategorySamplesAt(rootIndex: Int): List<FlameGraphTooltipCategorySamples> {
    val samplesByCategory = LinkedHashMap<String, CategorySampleAccumulator>()
    val pending = ArrayDeque<Int>()
    pending.addLast(rootIndex)
    while (pending.isNotEmpty()) {
        val nodeIndex = pending.removeLast()
        val selfSamples = selfSampleCountAt(nodeIndex)
        if (selfSamples > 0) {
            val category = firefoxCategoryAt(nodeIndex)
            val accumulator = samplesByCategory.getOrPut(category) { CategorySampleAccumulator() }
            accumulator.running += selfSamples
            if (nodeIndex == rootIndex) accumulator.self += selfSamples
        }
        childIndexes(nodeIndex).forEach(pending::addLast)
    }
    return samplesByCategory
        .map { (category, samples) ->
            FlameGraphTooltipCategorySamples(category, samples.running, samples.self)
        }.sortedWith(
            compareBy(
                { samples -> firefoxCategoryOrder.indexOf(samples.category).takeIf { it >= 0 } ?: Int.MAX_VALUE },
                FlameGraphTooltipCategorySamples::category,
            ),
        )
}

private fun CallNodeTable.selfSampleCountAt(nodeIndex: Int): Long {
    var remaining = sampleCountAt(nodeIndex)?.coerceAtLeast(0) ?: return 0
    childIndexes(nodeIndex).forEach { childIndex ->
        remaining = (remaining - (sampleCountAt(childIndex) ?: 0)).coerceAtLeast(0)
    }
    return remaining
}

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
        ?: frameAt(nodeIndex)?.firefoxSimpleperfCategory()
        ?: FIREFOX_CATEGORY_OTHER

private fun com.androidperformancestudio.profileanalysis.CallStackFrame.firefoxSimpleperfCategory(): String =
    when {
        resource.contains("kallsyms") || resource.endsWith(".ko") ->
            if (symbolName.startsWith("__schedule ")) FIREFOX_CATEGORY_OFF_CPU else FIREFOX_CATEGORY_KERNEL
        resource.endsWith(".so") -> FIREFOX_CATEGORY_NATIVE
        resource.endsWith(".vdex") -> FIREFOX_CATEGORY_DEX
        resource.endsWith(".oat") -> FIREFOX_CATEGORY_OAT
        resource == "[JIT app cache]" -> FIREFOX_CATEGORY_JIT
        else -> FIREFOX_CATEGORY_USER
    }

private data class CategorySampleAccumulator(
    var running: Long = 0,
    var self: Long = 0,
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
