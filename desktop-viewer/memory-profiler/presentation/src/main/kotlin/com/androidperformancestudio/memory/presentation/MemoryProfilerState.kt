package com.androidperformancestudio.memory.presentation

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
)
