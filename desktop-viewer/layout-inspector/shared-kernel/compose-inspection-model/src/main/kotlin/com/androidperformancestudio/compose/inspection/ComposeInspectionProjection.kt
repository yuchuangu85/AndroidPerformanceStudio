package com.androidperformancestudio.compose.inspection

import com.androidperformancestudio.protocol.ComposeNode
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.ViewNode
import com.androidperformancestudio.protocol.WindowSnapshot
import com.androidperformancestudio.protocol.effectiveWindows

object ComposeInspectionProjection {
    fun project(frame: ComposeInspectionFrame, hideSystemComposables: Boolean = true): List<ComposeNode> =
        frame.roots.flatMap { root -> root.nodes.flatMap { it.project(hideSystemComposables, emptyMap()) } }
            .filterIsInstance<ComposeNode>()

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
            window.copy(root = window.root.mergeCompose(rootsByViewId, viewsById, hideSystemComposables))
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
        viewsById: Map<Long, ViewNode>,
    ): List<UiNode> {
        val projectedChildren = children.flatMap { it.project(hideSystem, viewsById) } +
            listOfNotNull(hostedViewId?.let(viewsById::get))
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
    ): UiNode {
        val numericId = (this as? ViewNode)?.numericViewId()
        val composeRoot = numericId?.let(rootsByViewId::get)
        val replacedChildren = if (composeRoot != null) {
            val skipped = composeRoot.viewsToSkip.toSet()
            composeRoot.nodes.flatMap { it.project(hideSystem, viewsById) } +
                children.filterNot { child -> (child as? ViewNode)?.numericViewId() in skipped }
        } else {
            children.map { it.mergeCompose(rootsByViewId, viewsById, hideSystem) }
        }
        return when (this) {
            is ViewNode -> copy(children = replacedChildren)
            is ComposeNode -> copy(children = replacedChildren)
        }
    }

    private fun UiNode.flatten(): List<UiNode> = listOf(this) + children.flatMap { it.flatten() }

    private fun ViewNode.numericViewId(): Long? = id.removePrefix(VIEW_ID_PREFIX).toLongOrNull()

    private const val VIEW_ID_PREFIX = "view:"
}
