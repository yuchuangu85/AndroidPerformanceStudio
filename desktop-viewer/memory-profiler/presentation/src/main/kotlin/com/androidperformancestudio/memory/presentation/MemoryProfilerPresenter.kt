@file:Suppress("MaxLineLength", "TooManyFunctions")

package com.androidperformancestudio.memory.presentation

import com.androidperformancestudio.memory.model.ClassStats

public object MemoryProfilerPresenter {
    @Suppress("ktlint:standard:function-expression-body")
    public fun present(input: MemoryProfilerState): MemoryProfilerState {
        val sortedAll = sortClasses(input.classes, input.sort)
        val leakClasses = leakClassNames(input)
        val activityFragmentLeakClasses = activityFragmentLeakClassNames(input)
        val duplicateClasses = duplicateBitmapClasses(input)
        val displayed = buildDisplayedClasses(input, leakClasses, activityFragmentLeakClasses, duplicateClasses)
        return input.copy(
            classes = sortedAll,
            displayedClasses = displayed,
            classListSummary = buildSummary(displayed, leakClasses, duplicateClasses),
        )
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

    /** Class names flagged as leaks (leak suspects or Activity leaks). */
    public fun leakClassNames(input: MemoryProfilerState): Set<String> =
        buildSet {
            input.leakSuspects.forEach { add(it.className) }
            input.activityLeaks.forEach { add(it.className) }
        }

    private fun activityFragmentLeakClassNames(input: MemoryProfilerState): Set<String> =
        buildSet {
            input.activityLeaks.forEach { add(it.className) }
            input.leakSuspects.filter { it.activityOrFragmentLeak }.forEach { add(it.className) }
        }

    /** Bitmap classes sharing Perfetto storage identity, or dimensions when that identity is absent. */
    public fun duplicateBitmapClasses(input: MemoryProfilerState): Set<String> {
        val signatures =
            input.bitmapInstances.mapNotNull { bitmap ->
                bitmap.bitmapId?.let { return@mapNotNull bitmap.className to "${bitmap.bitmapSourceId}:$it" }
                val width = bitmap.width ?: return@mapNotNull null
                val height = bitmap.height ?: return@mapNotNull null
                bitmap.className to "$width:$height"
            }
        val counts = signatures.groupingBy { it }.eachCount()
        return signatures.filter { (counts[it] ?: 0) > 1 }.map { it.first }.toSet()
    }

    private fun buildDisplayedClasses(
        input: MemoryProfilerState,
        leakClasses: Set<String>,
        activityFragmentLeakClasses: Set<String>,
        duplicateClasses: Set<String>,
    ): List<ClassStats> {
        val nativeSizeByClass = nativeSizeByClass(input)
        return input.heapBaseClasses
            .filter { scopeMatches(input.classScope, it.className) }
            .filter { leakMatches(input.leakFilter, it.className, leakClasses, activityFragmentLeakClasses, duplicateClasses) }
            .filter { searchMatches(input.searchText, input.matchCase, input.useRegex, it.className) }
            .map { it.copy(nativeSize = nativeSizeByClass[it.className]) }
            .sortedWith(arrangeComparator(input.arrangeBy))
    }

    private fun buildSummary(
        displayed: List<ClassStats>,
        leakClasses: Set<String>,
        duplicateClasses: Set<String>,
    ): MemoryClassListSummary =
        MemoryClassListSummary(
            classCount = displayed.size,
            leakCount = displayed.count { it.className in leakClasses },
            duplicateBitmapCount = displayed.count { it.className in duplicateClasses },
            totalCount = displayed.sumOf { it.instanceCount },
            totalNativeSize = displayed.sumOf { it.nativeSize ?: 0L },
            totalShallowSize = displayed.sumOf { it.shallowSize },
            totalRetainedSize = displayed.sumOf { it.retainedSize ?: 0L },
        )

    private fun nativeSizeByClass(input: MemoryProfilerState): Map<String, Long> {
        val result =
            input.heapBaseClasses
                .mapNotNull { stats -> stats.nativeSize?.let { stats.className to it } }
                .toMap()
                .toMutableMap()
        input.bitmapInstances.forEach { bitmap ->
            val native = bitmap.nativeSizeBytes ?: return@forEach
            result[bitmap.className] = (result[bitmap.className] ?: 0L) + native
        }
        return result
    }

    private fun scopeMatches(
        scope: MemoryClassScope,
        className: String,
    ): Boolean =
        when (scope) {
            MemoryClassScope.ALL -> true
            MemoryClassScope.PROJECT -> !isSystemClass(className)
            MemoryClassScope.SYSTEM -> isSystemClass(className)
        }

    private fun leakMatches(
        filter: MemoryLeakFilter,
        className: String,
        leakClasses: Set<String>,
        activityFragmentLeakClasses: Set<String>,
        duplicateClasses: Set<String>,
    ): Boolean =
        when (filter) {
            MemoryLeakFilter.NONE -> true
            MemoryLeakFilter.ALL_ISSUE -> className in leakClasses || className in duplicateClasses
            MemoryLeakFilter.ACTIVITY_FRAGMENT_LEAK -> className in activityFragmentLeakClasses
            MemoryLeakFilter.DUPLICATE_BITMAPS -> className in duplicateClasses
        }

    private fun searchMatches(
        searchText: String,
        matchCase: Boolean,
        useRegex: Boolean,
        className: String,
    ): Boolean {
        if (searchText.isBlank()) return true
        return if (useRegex) {
            val options = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
            runCatching { Regex(searchText, options).containsMatchIn(className) }.getOrDefault(false)
        } else {
            if (matchCase) {
                className.contains(searchText)
            } else {
                className.lowercase().contains(searchText.lowercase())
            }
        }
    }

    private fun arrangeComparator(arrangeBy: MemoryArrangeBy): Comparator<ClassStats> =
        when (arrangeBy) {
            MemoryArrangeBy.CLASS ->
                compareBy<ClassStats> { it.className.lowercase() }
                    .thenByDescending { it.instanceCount }
            MemoryArrangeBy.PACKAGE ->
                compareBy<ClassStats> { packageOf(it.className).lowercase() }
                    .thenBy { it.className.lowercase() }
        }

    private fun packageOf(className: String): String {
        val separator = className.lastIndexOf('.')
        return if (separator > 0) className.substring(0, separator) else ""
    }

    /** Framework/VM/system classes by package prefix; everything else is treated as app/project code. */
    public fun isSystemClass(className: String): Boolean {
        val lower = className.lowercase()
        return SYSTEM_PREFIXES.any(lower::startsWith)
    }

    private val SYSTEM_PREFIXES =
        listOf(
            "android.",
            "androidx.",
            "java.",
            "javax.",
            "kotlin.",
            "kotlinx.",
            "com.android.",
            "dalvik.",
            "sun.",
            "jdk.",
            "org.json.",
            "org.xml.",
            "org.w3c.",
            "org.apache.harmony.",
            "junit.",
            "groovy.",
            "scala.",
        )
}
