package com.androidperformancestudio.compose.inspection

import com.androidperformancestudio.protocol.ComposeNode
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.ViewNode
import com.androidperformancestudio.protocol.WindowSnapshot
import com.androidperformancestudio.protocol.effectiveWindows

object ComposeInspectionProjection {
    fun project(frame: ComposeInspectionFrame, hideSystemComposables: Boolean = true): List<ComposeNode> =
        frame.roots.flatMap { root ->
            root.nodes.flatMap { it.project(hideSystemComposables, emptyMap(), emptyMap(), mutableSetOf()) }
        }.filterIsInstance<ComposeNode>()

    fun mergeInto(
        snapshot: LayoutSnapshot,
        frame: ComposeInspectionFrame,
        hideSystemComposables: Boolean = true,
    ): LayoutSnapshot {
        val rootsByViewId = frame.roots.associateBy(ComposableRoot::viewId)
        val viewsById = snapshot.effectiveWindows
            .flatMap { it.root.flatten() }
            .filterIsInstance<ViewNode>()
            .mapNotNull { node -> node.numericViewId()?.let { it to node } }
            .toMap()
        val windows = snapshot.effectiveWindows.map { window ->
            window.copy(root = window.root.mergeCompose(rootsByViewId, viewsById, hideSystemComposables, mutableSetOf()))
        }
        return snapshot.copy(
            root = windows.firstOrNull { it.id == snapshot.defaultWindowId }?.root
                ?: windows.firstOrNull()?.root
                ?: snapshot.root,
            windows = windows,
        )
    }

    private fun ComposableNode.project(
        hideSystem: Boolean,
        rootsByViewId: Map<Long, ComposableRoot>,
        viewsById: Map<Long, ViewNode>,
        activeViewIds: MutableSet<Long>,
    ): List<UiNode> {
        val projectedChildren = children.flatMap { it.project(hideSystem, rootsByViewId, viewsById, activeViewIds) } +
            listOfNotNull(hostedViewId?.let(viewsById::get)?.mergeCompose(rootsByViewId, viewsById, hideSystem, activeViewIds))
        if (hideSystem && systemCreated) return projectedChildren
        return listOf(
            ComposeNode(
                id = "compose-inspection:$id",
                className = name,
                bounds = bounds,
                children = projectedChildren,
            ),
        )
    }

    private fun UiNode.mergeCompose(
        rootsByViewId: Map<Long, ComposableRoot>,
        viewsById: Map<Long, ViewNode>,
        hideSystem: Boolean,
        activeViewIds: MutableSet<Long>,
    ): UiNode {
        val numericId = (this as? ViewNode)?.numericViewId()
        // Hosted views may point back to any native ancestor, not just another Compose host.
        if (numericId != null) {
            require(activeViewIds.add(numericId)) { "Cyclic Compose hosted view reference to view $numericId" }
        }
        return try {
            val composeRoot = numericId?.let(rootsByViewId::get)
            val replacedChildren = if (composeRoot != null) {
                val skipped = composeRoot.viewsToSkip.toSet()
                composeRoot.nodes.flatMap { it.project(hideSystem, rootsByViewId, viewsById, activeViewIds) } +
                    children.filterNot { child -> (child as? ViewNode)?.numericViewId() in skipped }
                        .map { it.mergeCompose(rootsByViewId, viewsById, hideSystem, activeViewIds) }
            } else {
                children.map { it.mergeCompose(rootsByViewId, viewsById, hideSystem, activeViewIds) }
            }
            when (this) {
                is ViewNode -> copy(children = replacedChildren)
                is ComposeNode -> copy(children = replacedChildren)
            }
        } finally {
            if (numericId != null) activeViewIds.remove(numericId)
        }
    }

    private fun UiNode.flatten(): List<UiNode> = listOf(this) + children.flatMap { it.flatten() }

    private fun ViewNode.numericViewId(): Long? = id.removePrefix(VIEW_ID_PREFIX).toLongOrNull()

    private const val VIEW_ID_PREFIX = "view:"
}
