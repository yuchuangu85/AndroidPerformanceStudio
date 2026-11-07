package com.androidperformancestudio.desktop

import com.androidperformancestudio.protocol.UiNode

internal enum class HiddenLayerVisibility {
    VISIBLE,
    DIRECT,
    INHERITED,
}

internal data class HiddenLayerState(
    val hiddenNodeIds: Set<String> = emptySet(),
) {
    val count: Int get() = hiddenNodeIds.size

    fun isHidden(nodeId: String?): Boolean = nodeId != null && nodeId in hiddenNodeIds

    fun toggle(nodeId: String): HiddenLayerState =
        if (nodeId in hiddenNodeIds) {
            copy(hiddenNodeIds = hiddenNodeIds - nodeId)
        } else {
            copy(hiddenNodeIds = hiddenNodeIds + nodeId)
        }

    fun clear(): HiddenLayerState = HiddenLayerState()

    fun sanitize(rows: List<TreeRowModel>): HiddenLayerState {
        val rowIds = rows.mapTo(mutableSetOf(), TreeRowModel::id)
        return copy(hiddenNodeIds = hiddenNodeIds intersect rowIds)
    }

    fun visibility(root: UiNode?): Map<String, HiddenLayerVisibility> {
        if (root == null || hiddenNodeIds.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, HiddenLayerVisibility>()

        fun visit(node: UiNode, inherited: Boolean) {
            val direct = node.id in hiddenNodeIds
            val effective = direct || inherited
            result[node.id] = when {
                direct -> HiddenLayerVisibility.DIRECT
                effective -> HiddenLayerVisibility.INHERITED
                else -> HiddenLayerVisibility.VISIBLE
            }
            node.children.forEach { child -> visit(child, effective) }
        }

        visit(root, inherited = false)
        return result
    }
}
