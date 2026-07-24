package com.androidperformancestudio.memory.presentation

import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapSummary
import com.androidperformancestudio.memory.model.LeakSuspect

public data class MemoryProfilerState(
    val devices: List<MemoryDeviceOption> = emptyList(),
    val selectedDeviceSerial: String? = null,
    val processes: List<MemoryProcessOption> = emptyList(),
    val selectedProcessId: Int? = null,
    val summary: HeapSummary = HeapSummary(),
    val classes: List<ClassStats> = emptyList(),
    val leakSuspects: List<LeakSuspect> = emptyList(),
    val sort: MemoryHistogramSort = MemoryHistogramSort.Count,
    val isDumping: Boolean = false,
    val operationMessage: String? = null,
    val error: MemoryProfilerError? = null,
    val warning: String? = null,
    val cleanupWarning: String? = null,
)

public data class MemoryDeviceOption(
    val serial: String,
    val name: String,
    val online: Boolean = true,
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
    val onSelectDevice: (String) -> Unit = {},
    val onSelectProcess: (Int) -> Unit = {},
    val onDumpHeap: () -> Unit = {},
    val onImportHprof: () -> Unit = {},
    val onSortHistogram: (MemoryHistogramSort) -> Unit = {},
    val onRetry: () -> Unit = {},
)
