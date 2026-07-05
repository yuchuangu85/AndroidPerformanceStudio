package dev.agentperf.desktop

internal object HierarchyRowLayout {
    const val BASELINE_HEIGHT_DP = 24
    const val HEIGHT_DP = 16
    const val FONT_SIZE_SP = 10
    const val INDENT_DP = 14
}

internal enum class HierarchyNavigationDirection {
    UP,
    DOWN,
}

internal object HierarchySelectionScrollPolicy {
    fun targetIndex(
        selectedIndex: Int,
        firstVisibleIndex: Int?,
        lastVisibleIndex: Int?,
    ): Int? {
        if (selectedIndex < 0) return null
        if (firstVisibleIndex == null || lastVisibleIndex == null) return selectedIndex
        return selectedIndex.takeUnless { it in firstVisibleIndex..lastVisibleIndex }
    }
}

internal data class HierarchyTreeState(
    private val collapsedNodeIds: Set<String> = emptySet(),
) {
    fun isExpanded(nodeId: String): Boolean = nodeId !in collapsedNodeIds

    fun toggle(nodeId: String): HierarchyTreeState =
        copy(
            collapsedNodeIds = if (nodeId in collapsedNodeIds) {
                collapsedNodeIds - nodeId
            } else {
                collapsedNodeIds + nodeId
            },
        )

    fun toggleExpandable(
        nodeId: String,
        rows: List<TreeRowModel>,
    ): HierarchyTreeState =
        if (rows.any { it.id == nodeId && it.hasChildren }) {
            toggle(nodeId)
        } else {
            this
        }

    fun adjacentNodeId(
        rows: List<TreeRowModel>,
        selectedNodeId: String?,
        direction: HierarchyNavigationDirection,
    ): String? {
        val visibleRows = visibleRows(rows)
        if (visibleRows.isEmpty()) return null
        val selectedIndex = visibleRows.indexOfFirst { it.id == selectedNodeId }
        if (selectedIndex == -1) return visibleRows.first().id
        val nextIndex = when (direction) {
            HierarchyNavigationDirection.UP -> (selectedIndex - 1).coerceAtLeast(0)
            HierarchyNavigationDirection.DOWN -> (selectedIndex + 1).coerceAtMost(visibleRows.lastIndex)
        }
        return visibleRows[nextIndex].id
    }

    fun displayRows(
        rows: List<TreeRowModel>,
        hideInvisible: Boolean,
    ): List<TreeRowModel> = visibleRows(
        ViewDisplayProjection.hierarchyRows(rows, hideInvisible),
    )

    fun visibleRows(rows: List<TreeRowModel>): List<TreeRowModel> = buildList {
        var collapsedAncestorDepth: Int? = null
        rows.forEach { row ->
            val hiddenDepth = collapsedAncestorDepth
            if (hiddenDepth != null && row.depth > hiddenDepth) {
                return@forEach
            }
            collapsedAncestorDepth = null
            add(row)
            if (row.hasChildren && !isExpanded(row.id)) {
                collapsedAncestorDepth = row.depth
            }
        }
    }
}
