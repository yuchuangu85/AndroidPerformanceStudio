package com.androidperformancestudio.memory.presentation

import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.memory.model.ActivityLeakEntry
import com.androidperformancestudio.memory.model.BitmapDumpComparison
import com.androidperformancestudio.memory.model.BitmapDumpSession
import com.androidperformancestudio.memory.model.BitmapInstanceStats
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapDiff
import com.androidperformancestudio.memory.model.HeapLoadPhase
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.memory.model.HeapSummary
import com.androidperformancestudio.memory.model.HeapValueKind
import com.androidperformancestudio.memory.model.LeakCanaryLiveSession
import com.androidperformancestudio.memory.model.LeakCanaryReport
import com.androidperformancestudio.memory.model.LeakSuspect
import com.androidperformancestudio.memory.model.NativeHeapAnalysis
import com.androidperformancestudio.memory.model.NativeHeapTrace
import com.androidperformancestudio.memory.model.ObjectReference

public enum class MemoryProfilerViewMode {
    /** Heap overview metrics and class histogram; dedicated analysis views own their details. */
    Dashboard,

    /** Android Studio-style class list → instance → reference drill-down. */
    ClassList,

    /** Retained-size tree derived from the current heap snapshot's dominator analysis. */
    Dominators,

    /** Class-level comparison between the latest two snapshots. */
    Diff,

    /** Dedicated page for captured bitmap images and bitmap-dump comparisons. */
    BitmapDump,

    /** Android Studio-style native allocation trace workspace. */
    NativeHeap,

    /** Raw Perfetto ART allocation sampling, distinct from Studio Full recording. */
    SampledArtAllocations,

    /** LeakCanary-compatible Shark leak trace workspace. */
    LeakCanary,
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
    RETAINED_NATIVE_SIZE,
    RETAINED_SIZE,
    ALLOCATIONS_SIZE,
    DEALLOCATIONS_SIZE,
    SHALLOW_SIZE_CHANGE,
}

/** Default columns for an HPROF class table; allocation metrics are unavailable without a recording. */
public val DEFAULT_MEMORY_CLASSIFIER_COLUMNS: Set<MemoryClassifierColumn> =
    setOf(
        MemoryClassifierColumn.TOTAL_COUNT,
        MemoryClassifierColumn.NATIVE_SIZE,
        MemoryClassifierColumn.SHALLOW_SIZE,
        MemoryClassifierColumn.RETAINED_SIZE,
    )

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
    val retainedNativeSize: Long? = null,
    val retainedSize: Long? = null,
    val allocationsSize: Long? = null,
    val deallocationsSize: Long? = null,
    val shallowSizeChange: Long? = null,
    val depth: Int = 0,
    val children: List<MemoryClassifierRow> = emptyList(),
    val heapNames: Set<String> = emptySet(),
    /** Exact heap scope shared by this row and its descendants; null when unavailable. */
    val heapName: String? = null,
) {
    val isLeaf: Boolean get() = className != null
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
    val shallowSizeKnown: Boolean = true,
    /** True when this object is one of the snapshot-local leak candidates. */
    val isLeakWarning: Boolean = false,
    /** Runtime type of this object, independent of the selected classifier label. */
    val className: String? = null,
    /** Represented type for a java.lang.Class object, distinct from its runtime type. */
    val representedClassName: String? = null,
)

public data class MemoryDominatorRow(
    val objectId: Long,
    val className: String,
    val shallowSize: Long,
    val retainedSize: Long,
    val depth: Int,
    val parentObjectId: Long?,
)

/** ART sampling capture receipt. File size is not an allocation metric. */
public enum class MemorySampledArtOrigin { CAPTURE, IMPORT }

public data class MemorySampledArtTrace(
    val file: String,
    val fileSizeBytes: Long,
    val packageName: String? = null,
    val pid: Int? = null,
    val sdkLevel: Int? = null,
    val samplingIntervalBytes: Int? = null,
    val durationMs: Long? = null,
    val source: String,
    val origin: MemorySampledArtOrigin = MemorySampledArtOrigin.CAPTURE,
)

public data class MemorySampledArtCallSite(
    val callsiteId: Long,
    val sampledObjectCount: Long,
    val sampledBytes: Long,
    val callStack: List<String>,
)

/** Analysis evidence is distinct from the raw trace receipt and from Studio Full recording. */
public sealed interface MemorySampledArtAnalysis {
    public data class Analyzed(
        val sampleRows: Long,
        val sampledObjectCount: Long,
        val sampledBytes: Long,
        val callSites: List<MemorySampledArtCallSite>,
    ) : MemorySampledArtAnalysis

    public data class Unavailable(
        val reason: String,
    ) : MemorySampledArtAnalysis

    public data class Failed(
        val reason: String,
    ) : MemorySampledArtAnalysis
}

/** A single field value of an instance, either a primitive or an object reference. */
public data class MemoryInstanceField(
    val name: String,
    val displayValue: String,
    val targetObjectId: Long?,
    val targetClassName: String?,
    /** Primitive owner depth, object target depth, or referring object depth; null if unavailable. */
    val depth: Int? = null,
    val valueKind: HeapValueKind = HeapValueKind.UNKNOWN,
    val shallowSize: Long? = null,
    val retainedSize: Long? = null,
    val nativeSize: Long? = null,
    val retainedNativeSize: Long? = null,
    val representedClassName: String? = null,
    val stringPreview: String? = null,
    val childCount: Int? = null,
)

public data class MemoryFilterPreset(
    val id: String,
    val label: String,
    val heapFilter: String?,
    val classScope: MemoryClassScope,
    val leakFilter: MemoryLeakFilter,
    val arrangeBy: MemoryArrangeBy,
    val searchText: String,
    val matchCase: Boolean,
    val useRegex: Boolean,
)

/** Aggregate metrics shown in the summary bar above the class list. */
public data class MemoryClassListSummary(
    val classCount: Int = 0,
    val leakCount: Int = 0,
    val duplicateBitmapCount: Int = 0,
    val totalCount: Int = 0,
    /** Null unless every selected class has native-size evidence. */
    val totalNativeSize: Long? = null,
    val totalRetainedNativeSize: Long? = null,
    val totalShallowSize: Long = 0L,
    /** Unavailable while a class filter is active or any selected class lacks retained evidence. */
    val totalRetainedSize: Long? = null,
)

/** Object-level Activity/Fragment lifecycle candidate from one HPROF; IDs are snapshot-local. */
public data class MemoryLifecycleLeakObject(
    val objectId: Long,
    val className: String,
    val heapName: String?,
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
    val arrayStart: Int = 0,
    val arrayPageSize: Int = 200,
    val fields: List<MemoryInstanceField>,
    val referenceChain: List<ObjectReference>,
    val references: List<MemoryInstanceField> = emptyList(),
    val shallowSizeKnown: Boolean = true,
    val nativeSize: Long? = null,
    val valueKind: HeapValueKind = HeapValueKind.UNKNOWN,
    val representedClassName: String? = null,
    val stringPreview: String? = null,
    val retainedNativeSize: Long? = null,
)

public data class MemoryInspectorObjectKey(
    val objectId: Long,
    val arrayStart: Int = 0,
)

public sealed interface MemoryInspectorObjectState {
    public data object Loading : MemoryInspectorObjectState

    public data object Unavailable : MemoryInspectorObjectState

    public data class Loaded(
        val detail: MemoryInstanceDetail,
    ) : MemoryInspectorObjectState
}

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
    val loadPhase: HeapLoadPhase = HeapLoadPhase.IDLE,
    val loadProgress: Int? = null,
    val snapshotSummary: HeapSnapshotSummary? = null,
    val snapshotSummaries: List<HeapSnapshotSummary> = emptyList(),
    val activeSnapshotId: String? = null,
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
    val lifecycleLeakObjects: List<MemoryLifecycleLeakObject> = emptyList(),
    val leakCanaryReport: LeakCanaryReport = LeakCanaryReport(),
    val leakCanaryLiveSession: LeakCanaryLiveSession = LeakCanaryLiveSession(),
    val nativeHeapTrace: NativeHeapTrace? = null,
    val nativeHeapAnalysis: NativeHeapAnalysis = NativeHeapAnalysis(),
    val sampledArtTrace: MemorySampledArtTrace? = null,
    val sampledArtAnalysis: MemorySampledArtAnalysis? = null,
    val artifact: CaptureArtifact? = null,
    val viewMode: MemoryProfilerViewMode = MemoryProfilerViewMode.ClassList,
    val selectedClassName: String? = null,
    val selectedClassifierId: String? = null,
    val selectedClassifierLabel: String? = null,
    val selectedClassInstances: List<MemoryInstanceRow> = emptyList(),
    val selectedInstanceDetail: MemoryInstanceDetail? = null,
    /** A class/instance graph query is running off the UI thread. */
    val isSelectionLoading: Boolean = false,
    val dominatorRows: List<MemoryDominatorRow> = emptyList(),
    /** Object IDs visited in the active snapshot; IDs never cross snapshot boundaries. */
    val instanceHistory: List<Long> = emptyList(),
    val instanceHistoryIndex: Int = -1,
    val pinnedInstanceIds: Set<Long> = emptySet(),
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
    val savedFilterPresets: List<MemoryFilterPreset> = emptyList(),
    val activeFilterPresetId: String? = null,
    /** Base class table for the current [heapFilter] (null = all heaps), before scope/leak/search filters. */
    val heapBaseClasses: List<ClassStats> = emptyList(),
    /** Independent per-heap histograms; never split all-heap totals using class-name membership. */
    val perHeapClasses: Map<String, List<ClassStats>> = emptyMap(),
    /** Class rows shown in the class-list table after all filters are applied. */
    val displayedClasses: List<ClassStats> = emptyList(),
    /** Summary metrics derived from [displayedClasses] by the presenter. */
    val classListSummary: MemoryClassListSummary = MemoryClassListSummary(),
    /** Classifier tree; unlike [displayedClasses] this preserves package/callstack hierarchy. */
    val classifierRows: List<MemoryClassifierRow> = emptyList(),
    val classifierSortColumn: MemoryClassifierColumn = MemoryClassifierColumn.RETAINED_SIZE,
    val classifierSortDirection: MemorySortDirection = MemorySortDirection.DESCENDING,
    val visibleClassifierColumns: Set<MemoryClassifierColumn> = DEFAULT_MEMORY_CLASSIFIER_COLUMNS,
    val inspectorObjects: Map<MemoryInspectorObjectKey, MemoryInspectorObjectState> = emptyMap(),
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
    val onCompareSnapshots: (String, String) -> Unit = { _, _ -> },
    val onSortHistogram: (MemoryHistogramSort) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onCancelOperation: () -> Unit = {},
    val onHighlightClass: (String) -> Unit = {},
    val onChangeViewMode: (MemoryProfilerViewMode) -> Unit = {},
    val onSelectClass: (String) -> Unit = {},
    val onSelectInstance: (Long) -> Unit = {},
    val onCopyObjectId: (Long) -> Unit = {},
    val onLoadArrayRange: (Long, Int) -> Unit = { _, _ -> },
    val onNavigateInstanceBack: () -> Unit = {},
    val onNavigateInstanceForward: () -> Unit = {},
    val onTogglePinnedInstance: (Long) -> Unit = {},
    val onHeapFilterChange: (String?) -> Unit = {},
    val onClassScopeChange: (MemoryClassScope) -> Unit = {},
    val onLeakFilterChange: (MemoryLeakFilter) -> Unit = {},
    val onArrangeByChange: (MemoryArrangeBy) -> Unit = {},
    val onSearchChange: (String) -> Unit = {},
    val onMatchCaseChange: (Boolean) -> Unit = {},
    val onUseRegexChange: (Boolean) -> Unit = {},
    val onSaveFilterPreset: () -> Unit = {},
    val onApplyFilterPreset: (MemoryFilterPreset) -> Unit = {},
    val onDeleteFilterPreset: () -> Unit = {},
    val onToggleClassifierColumn: (MemoryClassifierColumn) -> Unit = {},
    val onClassifierSort: (MemoryClassifierColumn) -> Unit = {},
    val onSelectClassifier: (MemoryClassifierRow) -> Unit = {},
    val onAnalyzeLeaks: () -> Unit = {},
    val onStartLiveLeakMonitoring: () -> Unit = {},
    val onStopLiveLeakMonitoring: () -> Unit = {},
    val onLoadInspectorObject: (Long, Int) -> Unit = { _, _ -> },
    val onClearInstanceSelection: () -> Unit = {},
)
