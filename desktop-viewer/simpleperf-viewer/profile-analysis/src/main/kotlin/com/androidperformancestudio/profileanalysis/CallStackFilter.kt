package com.androidperformancestudio.profileanalysis

data class FilteredCallStacks(
    val table: CallStackTable,
    val inputStackCount: Int,
    val afterPreviewCount: Int,
    val afterSearchCount: Int,
    val afterImplementationCount: Int,
    val incompleteInputStackCount: Int,
)

object CallStackFilter {
    fun apply(
        table: CallStackTable,
        query: CallStackAnalysisQuery,
    ): FilteredCallStacks {
        val afterPreview = filterPreview(table.stacks, query.previewRange)
        val afterImplementation = filterImplementation(table, afterPreview, query.implementation)
        val searchTerms = parseFlameSearchTerms(query.searchText)
        val afterSearch = filterSearch(table, afterImplementation, searchTerms)

        return FilteredCallStacks(
            table = table.withStacks(afterSearch),
            inputStackCount = table.stacks.size,
            afterPreviewCount = afterPreview.size,
            afterSearchCount = afterSearch.size,
            afterImplementationCount = afterImplementation.size,
            incompleteInputStackCount = table.stacks.count { stack -> stack.frameIdsRootToLeaf.isEmpty() },
        )
    }

    private fun filterPreview(
        stacks: List<WeightedCallStack>,
        range: AnalysisTimeRange?,
    ): List<WeightedCallStack> =
        if (range == null) {
            stacks
        } else {
            stacks.filter { stack ->
                stack.timestampNanos >= range.startNanosInclusive &&
                    stack.timestampNanos < range.endNanosExclusive
            }
        }

    private fun filterSearch(
        table: CallStackTable,
        stacks: List<WeightedCallStack>,
        terms: List<String>,
    ): List<WeightedCallStack> =
        if (terms.isEmpty()) {
            stacks
        } else {
            stacks.filter { stack ->
                terms.all { term ->
                    stack.frameIdsRootToLeaf.any { frameId -> table.frame(frameId).matchesStackSearch(term) }
                }
            }
        }

    private fun filterImplementation(
        table: CallStackTable,
        stacks: List<WeightedCallStack>,
        implementation: ImplementationFilter,
    ): List<WeightedCallStack> =
        if (implementation == ImplementationFilter.ALL) {
            stacks
        } else {
            stacks.mapNotNull { stack ->
                val matchingIndexes =
                    stack.frameIdsRootToLeaf.indices.filter { index ->
                        val frameId = stack.frameIdsRootToLeaf[index]
                        table.frame(frameId).implementation.matches(implementation)
                    }
                when {
                    matchingIndexes.isEmpty() -> null
                    matchingIndexes.size == stack.frameIdsRootToLeaf.size -> stack
                    else ->
                        stack.copy(
                            frameIdsRootToLeaf = matchingIndexes.map(stack.frameIdsRootToLeaf::get),
                            categoriesRootToLeaf = matchingIndexes.map(stack.categoriesRootToLeaf::get),
                        )
                }
            }
        }

    private fun FrameImplementation.matches(filter: ImplementationFilter): Boolean =
        when (filter) {
            ImplementationFilter.ALL -> true
            ImplementationFilter.SCRIPT -> this == FrameImplementation.MANAGED
            ImplementationFilter.NATIVE -> this == FrameImplementation.NATIVE || this == FrameImplementation.KERNEL
        }

    private fun CallStackTable.withStacks(filteredStacks: List<WeightedCallStack>): CallStackTable =
        if (stacks.hasSameInstances(filteredStacks)) this else copy(stacks = filteredStacks)

    private fun List<WeightedCallStack>.hasSameInstances(other: List<WeightedCallStack>): Boolean =
        size == other.size && indices.all { index -> this[index] === other[index] }
}

fun CallStackFrame.matchesStackSearch(term: String): Boolean =
    symbolName.contains(term, ignoreCase = true) ||
        resource.contains(term, ignoreCase = true) ||
        sourceFile?.contains(term, ignoreCase = true) == true ||
        originalSourceFile?.contains(term, ignoreCase = true) == true
