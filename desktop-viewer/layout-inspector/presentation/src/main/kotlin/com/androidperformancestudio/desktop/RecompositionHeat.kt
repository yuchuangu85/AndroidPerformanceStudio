package com.androidperformancestudio.desktop

import com.androidperformancestudio.compose.inspection.ComposableNode
import com.androidperformancestudio.compose.inspection.ComposeInspectionDocument
import com.androidperformancestudio.protocol.UiNode

internal class RecompositionHeatTracker {
    private var previous: Sample? = null

    fun sample(document: ComposeInspectionDocument?): Map<String, Float> {
        val observation = document?.frame?.recompositionObservation
        if (document == null || observation?.active != true) {
            previous = null
            return emptyMap()
        }
        val counts = buildMap {
            document.frame.roots.forEach { root -> root.nodes.forEach { it.collectRecomposeCounts(this) } }
        }
        val current = Sample(observation.startedAtEpochMillis, document.capturedAtEpochMillis, counts)
        val baseline = previous
            ?.takeIf { it.startedAtEpochMillis == current.startedAtEpochMillis && it.capturedAtEpochMillis < current.capturedAtEpochMillis }
            ?: Sample(current.startedAtEpochMillis, current.startedAtEpochMillis, emptyMap())
        previous = current
        val elapsedMillis = current.capturedAtEpochMillis - baseline.capturedAtEpochMillis
        if (elapsedMillis <= 0) return emptyMap()
        return counts.mapNotNull { (id, count) ->
            val delta = count - (baseline.counts[id] ?: 0)
            if (delta <= 0) null else id to (delta * 1_000f / elapsedMillis / MAX_RATE_PER_SECOND).coerceIn(0.12f, 1f)
        }.toMap()
    }

    private data class Sample(
        val startedAtEpochMillis: Long,
        val capturedAtEpochMillis: Long,
        val counts: Map<String, Int>,
    )

    private fun ComposableNode.collectRecomposeCounts(target: MutableMap<String, Int>) {
        recomposeCount?.let { target["compose-inspection:$id"] = it }
        children.forEach { it.collectRecomposeCounts(target) }
    }

    private companion object {
        const val MAX_RATE_PER_SECOND = 10f
    }
}

internal data class RecompositionHeatOverlay(
    val bounds: FloatRect,
    val intensity: Float,
)

internal fun mappedRecompositionHeat(
    root: UiNode,
    heatByNodeId: Map<String, Float>,
    source: CropRect,
    destination: FloatRect,
    hiddenNodeIds: Set<String>,
): List<RecompositionHeatOverlay> = buildList {
    fun addNode(node: UiNode, ancestorsVisible: Boolean, ancestorAlpha: Float) {
        if (node.id in hiddenNodeIds) return
        val visible = ancestorsVisible && node.visible
        val alpha = ancestorAlpha * node.alpha
        if (!visible || alpha <= 0f) return
        heatByNodeId[node.id]?.let { intensity ->
            CanvasGeometry.mapBounds(node.bounds, source, destination)?.let { bounds ->
                add(RecompositionHeatOverlay(bounds, intensity))
            }
        }
        node.children.forEach { addNode(it, visible, alpha) }
    }
    addNode(root, ancestorsVisible = true, ancestorAlpha = 1f)
}
