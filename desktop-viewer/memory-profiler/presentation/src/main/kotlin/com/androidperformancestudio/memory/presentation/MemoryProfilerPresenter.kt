package com.androidperformancestudio.memory.presentation

import com.androidperformancestudio.memory.model.ClassStats

public object MemoryProfilerPresenter {
    @Suppress("ktlint:standard:function-expression-body")
    public fun present(input: MemoryProfilerState): MemoryProfilerState {
        return input.copy(classes = sortClasses(input.classes, input.sort))
    }

    public fun sortClasses(
        classes: List<ClassStats>,
        sort: MemoryHistogramSort,
    ): List<ClassStats> =
        when (sort) {
            MemoryHistogramSort.Count ->
                classes.sortedWith(
                    compareByDescending<ClassStats> { it.instanceCount }
                        .thenBy { it.className },
                )
            MemoryHistogramSort.Shallow ->
                classes.sortedWith(
                    compareByDescending<ClassStats> { it.shallowSize }
                        .thenBy { it.className },
                )
        }
}
