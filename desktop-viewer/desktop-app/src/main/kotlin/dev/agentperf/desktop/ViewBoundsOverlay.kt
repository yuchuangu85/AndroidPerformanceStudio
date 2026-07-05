package dev.agentperf.desktop

import dev.agentperf.protocol.UiNode

internal object ViewBoundsOverlay {
    fun mappedVisibleBounds(
        root: UiNode,
        selectedNodeId: String?,
        source: CropRect,
        destination: FloatRect,
    ): List<FloatRect> = buildList {
        fun addNode(
            node: UiNode,
            ancestorsVisible: Boolean,
            ancestorAlpha: Float,
        ) {
            val effectivelyVisible = ancestorsVisible && node.visible
            val effectiveAlpha = ancestorAlpha * node.alpha
            if (!effectivelyVisible || !(effectiveAlpha > 0f)) return

            if (
                node.id != selectedNodeId &&
                node.bounds.width > 0 &&
                node.bounds.height > 0
            ) {
                CanvasGeometry.mapBounds(
                    bounds = node.bounds,
                    source = source,
                    destination = destination,
                )?.let(::add)
            }

            node.children.forEach { child ->
                addNode(
                    node = child,
                    ancestorsVisible = effectivelyVisible,
                    ancestorAlpha = effectiveAlpha,
                )
            }
        }

        addNode(
            node = root,
            ancestorsVisible = true,
            ancestorAlpha = 1f,
        )
    }
}
