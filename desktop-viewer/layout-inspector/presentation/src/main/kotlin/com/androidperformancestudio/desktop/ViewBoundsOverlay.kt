package com.androidperformancestudio.desktop

import com.androidperformancestudio.protocol.UiNode

internal object ViewBoundsOverlay {
    fun mappedVisibleBounds(
        root: UiNode,
        selectedNodeId: String?,
        source: CropRect,
        destination: FloatRect,
        hiddenNodeIds: Set<String> = emptySet(),
    ): List<FloatRect> = buildList {
        fun addNode(
            node: UiNode,
            ancestorsVisible: Boolean,
            ancestorAlpha: Float,
        ) {
            if (node.id in hiddenNodeIds) return
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
