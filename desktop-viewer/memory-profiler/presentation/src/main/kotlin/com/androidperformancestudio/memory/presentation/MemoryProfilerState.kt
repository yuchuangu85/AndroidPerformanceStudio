package com.androidperformancestudio.memory.presentation

import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.memory.model.ActivityLeakEntry
import com.androidperformancestudio.memory.model.BitmapDumpComparison
import com.androidperformancestudio.memory.model.BitmapDumpSession
import com.androidperformancestudio.memory.model.BitmapInstanceStats
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapDiff
import com.androidperformancestudio.memory.model.HeapSummary
import com.androidperformancestudio.memory.model.LeakSuspect
import com.androidperformancestudio.memory.model.NativeHeapAnalysis
import com.androidperformancestudio.memory.model.NativeHeapTrace
import com.androidperformancestudio.memory.model.ObjectReference

public enum class MemoryProfilerViewMode {
    /** Current one-page dashboard: overview cards + leak suspects + native heap + bitmaps. */
    Dashboard,

    /** Android Studio-style class list → instance → reference drill-down. */
    ClassList,
}

/** Class-list scope filter: every class, or only application/project vs framework/system classes. */
public enum class MemoryClassScope {
    ALL,
    PROJECT,
    SYSTEM,
}

/** Leak-analysis filter for the class list. */
public enum class MemoryLeakFilter {
    NONE,
    ALL_ISSUE,
    ACTIVITY_FRAGMENT_LEAK,
    DUPLICATE_BITMAPS,
}

/** Class-list ordering: by class name or by package. */
public enum class MemoryArrangeBy {
    CLASS,
    PACKAGE,
    CALLSTACK,
    ALLOCATION_METHOD,
}

public enum class MemoryClassifierColumn {
    NAME,
    MODULE_NAME,
    ALLOCATIONS,
    DEALLOCATIONS,
    TOTAL_COUNT,
    NATIVE_SIZE,
    SHALLOW_SIZE,
    RETAINED_SIZE,
    ALLOCATIONS_SIZE,
    DEALLOCATIONS_SIZE,
    SHALLOW_SIZE_CHANGE,
}

public enum class MemorySortDirection { ASCENDING, DESCENDING }

/** One node in the classifier tree shown by the class list. Leaf nodes represent classes. */
public data class MemoryClassifierRow(
    val id: String,
    val label: String,
    val className: String? = null,
    val moduleName: String? = null,
    val allocations: Long? = null,
    val deallocations: Long? = null,
    val totalCount: Long = 0L,
    val nativeSize: Long? = null,
    val shallowSize: Long = 0L,
    val retainedSize: Long? = null,
    val allocationsSize: Long? = null,
    val deallocationsSize: Long? = null,
    val shallowSizeChange: Long? = null,
    val depth: Int = 0,
    val children: List<MemoryClassifierRow> = emptyList(),
) {
    val isLeaf: Boolean get() = children.isEmpty()
}

/** One row of the instance list shown when a class is selected in [MemoryProfilerViewMode.ClassList]. */
public data class MemoryInstanceRow(
    val objectId: Long,
    val index: Int,
    val shallowSize: Long,
    val retainedSize: Long?,
    val depth: Int?,
    val reachable: Boolean,
    /** Estimated native footprint of this instance (Bitmap pixel buffer); null when unknown. */
    val nativeSize: Long? = null,
)

/** A single field value of an instance, either a primitive or an object reference. */
public data class MemoryInstanceField(
    val name: String,
    val displayValue: String,
    val targetObjectId: Long?,
    val targetClassName: String?,
)

/** Aggregate metrics shown in the summary bar above the class list. */
public data class MemoryClassListSummary(
    val classCount: Int = 0,
    val leakCount: Int = 0,
    val duplicateBitmapCount: Int = 0,
    val totalCount: Int = 0,
    val totalNativeSize: Long = 0L,
    val totalShallowSize: Long = 0L,
    val totalRetainedSize: Long = 0L,
)

/** Full detail view of one selected heap object. */
public data class MemoryInstanceDetail(
    val objectId: Long,
    val className: String,
    val shallowSize: Long,
    val retainedSize: Long?,
    val depth: Int?,
    val isArray: Boolean,
    val elementCount: Int?,
    val fields: List<MemoryInstanceField>,
    val referenceChain: List<ObjectReference>,
    val references: List<MemoryInstanceField> = emptyList(),
)

public data class MemoryProfilerState(
    val devices: List<MemoryDeviceOption> = emptyList(),
    val selectedDeviceSerial: String? = null,
    val processes: List<MemoryProcessOption> = emptyList(),
    val selectedProcessId: Int? = null,
    val summary: HeapSummary = HeapSummary(),
    val activityCount: Int = 0,
    val classes: List<ClassStats> = emptyList(),
    val leakSuspects: List<LeakSuspect> = emptyList(),
    val sort: MemoryHistogramSort = MemoryHistogramSort.Count,
    val isDumping: Boolean = false,
    val operationMessage: String? = null,
    val error: MemoryProfilerError? = null,
    val warning: String? = null,
    val cleanupWarning: String? = null,
    val heapDiff: HeapDiff? = null,
    val bitmapInstances: List<BitmapInstanceStats> = emptyList(),
    val bitmapDumpSession: BitmapDumpSession? = null,
    val bitmapDumpComparison: BitmapDumpComparison? = null,
    val highlightedClassName: String? = null,
    val mappingLoaded: Boolean = false,
    val activityLeaks: List<ActivityLeakEntry> = emptyList(),
    val nativeHeapTrace: NativeHeapTrace? = null,
    val nativeHeapAnalysis: NativeHeapAnalysis = NativeHeapAnalysis(),
    val artifact: CaptureArtifact? = null,
    val viewMode: MemoryProfilerViewMode = MemoryProfilerViewMode.Dashboard,
    val selectedClassName: String? = null,
    val selectedClassifierId: String? = null,
    val selectedClassifierLabel: String? = null,
    val selectedClassInstances: List<MemoryInstanceRow> = emptyList(),
    val selectedInstanceDetail: MemoryInstanceDetail? = null,
    val availableHeaps: List<String> = emptyList(),
    val heapFilter: String? = null,
    val classScope: MemoryClassScope = MemoryClassScope.ALL,
    val leakFilter: MemoryLeakFilter = MemoryLeakFilter.NONE,
    val arrangeBy: MemoryArrangeBy = MemoryArrangeBy.CLASS,
    /** Groupings supported by the loaded source; heap dumps do not invent allocation stacks. */
    val availableArrangeBy: List<MemoryArrangeBy> =
        listOf(MemoryArrangeBy.CLASS, MemoryArrangeBy.PACKAGE),
    val searchText: String = "",
    val matchCase: Boolean = false,
    val useRegex: Boolean = false,
    /** Base class table for the current [heapFilter] (null = all heaps), before scope/leak/search filters. */
    val heapBaseClasses: List<ClassStats> = emptyList(),
    /** Class rows shown in the class-list table after all filters are applied. */
    val displayedClasses: List<ClassStats> = emptyList(),
    /** Summary metrics derived from [displayedClasses] by the presenter. */
    val classListSummary: MemoryClassListSummary = MemoryClassListSummary(),
    /** Classifier tree; unlike [displayedClasses] this preserves package/callstack hierarchy. */
    val classifierRows: List<MemoryClassifierRow> = emptyList(),
    val classifierSortColumn: MemoryClassifierColumn = MemoryClassifierColumn.NAME,
    val classifierSortDirection: MemorySortDirection = MemorySortDirection.ASCENDING,
)

public data class MemoryDeviceOption(
    val serial: String,
    val name: String,
    val online: Boolean = true,
    val apiLevel: Int? = null,
    val supportsBitmapDump: Boolean = true,
)

public data class MemoryProcessOption(
    val pid: Int,
    val name: String,
    val packageName: String = name,
)

public data class MemoryProfilerError(
    val title: String,
    val detail: String,
    val retryLabel: String = "Retry",
)

public enum class MemoryHistogramSort {
    Count,
    Shallow,
}

public data class MemoryProfilerActions(
    val onSortHistogram: (MemoryHistogramSort) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onHighlightClass: (String) -> Unit = {},
    val onChangeViewMode: (MemoryProfilerViewMode) -> Unit = {},
    val onSelectClass: (String) -> Unit = {},
    val onSelectInstance: (Long) -> Unit = {},
    val onHeapFilterChange: (String?) -> Unit = {},
    val onClassScopeChange: (MemoryClassScope) -> Unit = {},
    val onLeakFilterChange: (MemoryLeakFilter) -> Unit = {},
    val onArrangeByChange: (MemoryArrangeBy) -> Unit = {},
    val onSearchChange: (String) -> Unit = {},
    val onMatchCaseChange: (Boolean) -> Unit = {},
    val onUseRegexChange: (Boolean) -> Unit = {},
    val onClassifierSort: (MemoryClassifierColumn) -> Unit = {},
    val onSelectClassifier: (MemoryClassifierRow) -> Unit = {},
)
