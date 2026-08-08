package com.androidperformancestudio.desktop

internal data class HierarchyIsolationState(val rootNodeId: String? = null) {
    val active: Boolean get() = rootNodeId != null

    fun isolate(nodeId: String?, rows: List<TreeRowModel>): HierarchyIsolationState =
        copy(rootNodeId = nodeId?.takeIf { id -> rows.any { it.id == id } })

    fun parent(rows: List<TreeRowModel>): HierarchyIsolationState {
        val index = rows.indexOfFirst { it.id == rootNodeId }
        if (index <= 0) return clear()
        val depth = rows[index].depth
        return copy(rootNodeId = rows.subList(0, index).lastOrNull { it.depth < depth }?.id)
    }

    fun clear() = HierarchyIsolationState()

    fun sanitize(rows: List<TreeRowModel>): HierarchyIsolationState = isolate(rootNodeId, rows)

    fun rows(rows: List<TreeRowModel>): List<TreeRowModel> {
        val index = rows.indexOfFirst { it.id == rootNodeId }
        if (index < 0) return rows
        val depth = rows[index].depth
        val end = ((index + 1)..rows.lastIndex).firstOrNull { rows[it].depth <= depth } ?: rows.size
        return rows.subList(index, end)
            .map { it.copy(depth = it.depth - depth) }
    }
}
