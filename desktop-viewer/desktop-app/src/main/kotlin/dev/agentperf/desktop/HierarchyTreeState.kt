package dev.agentperf.desktop

internal object HierarchyRowLayout {
    const val BASELINE_HEIGHT_DP = 24
    const val HEIGHT_DP = 16
    const val FONT_SIZE_SP = 10
    const val INDENT_DP = 14
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
