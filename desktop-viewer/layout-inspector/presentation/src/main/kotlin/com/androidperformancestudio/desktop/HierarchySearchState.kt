package com.androidperformancestudio.desktop

internal data class HierarchySearchState(
    val query: String = "",
    val currentIndex: Int = -1,
) {
    val isSearching: Boolean get() = query.isNotBlank()

    fun matches(row: TreeRowModel): Boolean {
        if (!isSearching) return false
        val q = query.lowercase()
        return row.label.lowercase().contains(q) ||
            row.resourceLabel?.lowercase()?.contains(q) == true ||
            row.number.lowercase().contains(q)
    }

    fun matchedNodeIds(rows: List<TreeRowModel>): List<String> {
        if (!isSearching) return emptyList()
        return rows.filter { matches(it) }.map { it.id }
    }

    fun withQuery(query: String): HierarchySearchState =
        copy(query = query, currentIndex = if (query.isBlank()) -1 else 0)

    fun navigateNext(matchedIds: List<String>): HierarchySearchState {
        if (matchedIds.isEmpty()) return copy(currentIndex = -1)
        val next = if (currentIndex < 0) 0 else (currentIndex + 1) % matchedIds.size
        return copy(currentIndex = next)
    }

    fun navigatePrevious(matchedIds: List<String>): HierarchySearchState {
        if (matchedIds.isEmpty()) return copy(currentIndex = -1)
        val prev = if (currentIndex <= 0) matchedIds.lastIndex else currentIndex - 1
        return copy(currentIndex = prev)
    }

    fun currentMatchedNodeId(matchedIds: List<String>): String? {
        if (currentIndex < 0 || currentIndex >= matchedIds.size) return null
        return matchedIds[currentIndex]
    }

    fun matchSummary(matchedIds: List<String>): String? {
        if (!isSearching) return null
        if (matchedIds.isEmpty()) return "0/0"
        val displayIndex = (currentIndex + 1).coerceIn(1, matchedIds.size)
        return "$displayIndex/${matchedIds.size}"
    }
}
