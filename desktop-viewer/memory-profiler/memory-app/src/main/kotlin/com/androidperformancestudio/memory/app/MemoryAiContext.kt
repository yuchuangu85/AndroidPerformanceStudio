package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.presentation.MemoryProfilerState
import com.androidperformancestudio.ui.UiAiContext
import com.androidperformancestudio.ui.UiAiEvidence
import com.androidperformancestudio.ui.uiAiPayload
import com.androidperformancestudio.ui.uiAiEvidenceId

internal fun memoryAiContext(state: MemoryProfilerState): UiAiContext? {

    val snapshot = state.activeSnapshotId ?: return null
    if (state.isDumping || state.isSelectionLoading) return null
    val detail = state.selectedInstanceDetail
    if (detail != null) {
        if (state.selectedClassName != null && detail.className != state.selectedClassName) return null
        val payload = uiAiPayload(
            "scope" to "object-selection", "sizeUnit" to "bytes",
            "objectRef" to uiAiEvidenceId("memory-object", snapshot, detail.objectId.toString()),
            "className" to detail.className,
            "shallowBytes" to if (detail.shallowSizeKnown) detail.shallowSize else null,
            "retainedBytes" to detail.retainedSize, "nativeBytes" to detail.nativeSize,
            "depth" to detail.depth, "isArray" to detail.isArray, "elementCount" to detail.elementCount,
            "referenceChainLength" to detail.referenceChain.size,
            "fieldsIncluded" to false, "runtimeValuesIncluded" to false,
        )
        return UiAiContext("memory", "Memory object evidence", true, listOf(UiAiEvidence(uiAiEvidenceId("memory-selection", snapshot, payload), "memory-selection", "Snapshot-bound object metrics only; runtime values excluded", payload)))
    }
    val selected = state.selectedClassName?.let { name -> state.classes.firstOrNull { it.className == name } ?: return null }
    val rows = if (selected != null) listOf(selected) else state.displayedClasses.take(8)
    val payload = uiAiPayload(
        "scope" to if (selected != null) "class-selection" else "filtered-histogram-summary",
        "sizeUnit" to "bytes", "objectCount" to state.summary.objectCount,
        "classCount" to state.summary.classCount, "shallowBytes" to state.summary.shallowSize,
        "visibleClassCount" to state.displayedClasses.size, "includedClassCount" to rows.size,
        "omittedClassCount" to if (selected == null) (state.displayedClasses.size - rows.size).coerceAtLeast(0) else 0,
        "leakSuspectCount" to state.leakSuspects.size, "leakConfirmed" to false,
        "heapFilterActive" to (state.heapFilter != null), "searchFilterActive" to state.searchText.isNotBlank(),
        "classes" to rows.map { mapOf("className" to it.className, "instances" to it.instanceCount, "shallowBytes" to it.shallowSize, "retainedBytes" to it.retainedSize) },
    )
    val kind = if (selected != null) "memory-selection" else "memory-summary"
    return UiAiContext("memory", "Memory evidence", selected != null, listOf(UiAiEvidence(uiAiEvidenceId(kind, snapshot, payload), kind, "Heap histogram evidence; retention is not proof of a leak", payload)))
}
