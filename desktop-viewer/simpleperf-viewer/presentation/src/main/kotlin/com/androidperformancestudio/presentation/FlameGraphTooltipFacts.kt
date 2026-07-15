package com.androidperformancestudio.presentation

import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.profileanalysis.FrameImplementation

internal data class FlameGraphTooltipFacts(
    val function: String,
    val category: String?,
    val implementation: FrameImplementation,
    val resource: String?,
    val inclusiveWeight: Long,
    val selfWeight: Long,
    val sampleCount: Long,
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
    val category = callNodes.categoryAt(index)
    val percentage = if (totalWeight > 0) inclusive.toDouble() / totalWeight * PERCENT_SCALE else 0.0
    return FlameGraphTooltipFacts(
        function = frame.symbolName,
        category = category?.takeIf(String::isNotBlank),
        implementation = frame.implementation,
        resource = frame.resource.takeIf(String::isNotBlank),
        inclusiveWeight = inclusive,
        selfWeight = self,
        sampleCount = samples,
        percentage = percentage.takeIf(Double::isFinite) ?: 0.0,
        previewRangeWeight = inclusive.takeIf { query.previewRange != null },
    )
}

private const val PERCENT_SCALE = 100.0
