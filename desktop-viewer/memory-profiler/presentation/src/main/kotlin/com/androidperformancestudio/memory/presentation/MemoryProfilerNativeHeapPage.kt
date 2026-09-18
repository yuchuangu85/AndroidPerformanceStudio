@file:Suppress("FunctionName", "LongMethod", "MaxLineLength", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.model.NativeHeapAnalysis
import com.androidperformancestudio.memory.model.NativeHeapSample
import com.androidperformancestudio.memory.model.NativeHeapTrace
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.allocated
import com.androidperformancestudio.memory.presentation.generated.resources.allocs
import com.androidperformancestudio.memory.presentation.generated.resources.freed
import com.androidperformancestudio.memory.presentation.generated.resources.frees
import com.androidperformancestudio.memory.presentation.generated.resources.native_function
import com.androidperformancestudio.memory.presentation.generated.resources.native_heap
import com.androidperformancestudio.memory.presentation.generated.resources.native_heap_capture_loading
import com.androidperformancestudio.memory.presentation.generated.resources.native_heap_no_allocations
import com.androidperformancestudio.memory.presentation.generated.resources.native_heap_total
import com.androidperformancestudio.memory.presentation.generated.resources.native_heap_trace_summary
import com.androidperformancestudio.memory.presentation.generated.resources.no_native_heap_trace
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource

@Composable
public fun MemoryProfilerNativeHeapPage(
    trace: NativeHeapTrace?,
    analysis: NativeHeapAnalysis,
    isLoading: Boolean,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(8.dp).testTag("memory-profiler-native-heap-page"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(localizedStringResource(Res.string.native_heap, language), fontWeight = FontWeight.Bold)
        when {
            trace != null -> NativeHeapDetails(trace, analysis, language, Modifier.weight(1f))
            isLoading ->
                Text(
                    localizedStringResource(Res.string.native_heap_capture_loading, language),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else ->
                Text(
                    localizedStringResource(Res.string.no_native_heap_trace, language),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}

@Composable
private fun NativeHeapDetails(
    trace: NativeHeapTrace,
    analysis: NativeHeapAnalysis,
    language: UiLanguage,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            localizedStringResource(
                Res.string.native_heap_trace_summary,
                language,
                trace.fileName,
                formatBytes(trace.fileSizeBytes),
                trace.deviceSdkApiLevel?.toString() ?: "?",
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trace.artifact?.let { artifact ->
            Text(
                "Source: ${trace.evidenceSource}; completeness: ${artifact.completeness}; capabilities: " +
                    artifact.availableCapabilities.joinToString { it.value },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = ViewerTypography.bodyCompact.fontSize,
            )
            trace.fallbackReason?.let { reason ->
                Text(
                    "Best-effort fallback: $reason",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = ViewerTypography.bodyCompact.fontSize,
                )
            }
        }
        Text(
            localizedStringResource(
                Res.string.native_heap_total,
                language,
                formatBytes(analysis.totalAllocatedBytes),
                formatBytes(analysis.totalFreedBytes),
                integer(analysis.sampleCount),
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (analysis.topAllocations.isEmpty()) {
            Text(
                localizedStringResource(Res.string.native_heap_no_allocations, language),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            var sortColumn by remember { mutableStateOf(NativeHeapPageSortColumn.ALLOCATED) }
            var descending by remember { mutableStateOf(true) }
            val rows =
                remember(analysis, sortColumn, descending) {
                    val comparator =
                        when (sortColumn) {
                            NativeHeapPageSortColumn.FUNCTION -> compareBy<NativeHeapSample> { it.functionName }
                            NativeHeapPageSortColumn.ALLOCATED -> compareBy { it.allocatedBytes }
                            NativeHeapPageSortColumn.FREED -> compareBy { it.freedBytes }
                            NativeHeapPageSortColumn.ALLOCS -> compareBy { it.allocCount }
                            NativeHeapPageSortColumn.FREES -> compareBy { it.freeCount }
                        }
                    analysis.topAllocations.sortedWith(if (descending) comparator.reversed() else comparator)
                }
            NativeHeapPageTableHeader(language, sortColumn, descending) { column ->
                if (sortColumn == column) {
                    descending = !descending
                } else {
                    sortColumn = column
                    descending = true
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).heightIn(min = 120.dp)) {
                itemsIndexed(rows) { index, sample -> NativeHeapPageTableRow(sample, index) }
            }
        }
    }
}

private enum class NativeHeapPageSortColumn { FUNCTION, ALLOCATED, FREED, ALLOCS, FREES }

@Composable
private fun NativeHeapPageTableHeader(
    language: UiLanguage,
    sortColumn: NativeHeapPageSortColumn,
    descending: Boolean,
    onSort: (NativeHeapPageSortColumn) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NativeHeapPageHeaderCell(
            localizedStringResource(Res.string.native_function, language),
            Modifier.weight(1f),
            sortColumn == NativeHeapPageSortColumn.FUNCTION,
            descending,
            onSort,
            NativeHeapPageSortColumn.FUNCTION,
        )
        NativeHeapPageHeaderCell(
            localizedStringResource(Res.string.allocated, language),
            Modifier.width(88.dp),
            sortColumn == NativeHeapPageSortColumn.ALLOCATED,
            descending,
            onSort,
            NativeHeapPageSortColumn.ALLOCATED,
        )
        NativeHeapPageHeaderCell(
            localizedStringResource(Res.string.freed, language),
            Modifier.width(80.dp),
            sortColumn == NativeHeapPageSortColumn.FREED,
            descending,
            onSort,
            NativeHeapPageSortColumn.FREED,
        )
        NativeHeapPageHeaderCell(
            localizedStringResource(Res.string.allocs, language),
            Modifier.width(56.dp),
            sortColumn == NativeHeapPageSortColumn.ALLOCS,
            descending,
            onSort,
            NativeHeapPageSortColumn.ALLOCS,
        )
        NativeHeapPageHeaderCell(
            localizedStringResource(Res.string.frees, language),
            Modifier.width(56.dp),
            sortColumn == NativeHeapPageSortColumn.FREES,
            descending,
            onSort,
            NativeHeapPageSortColumn.FREES,
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun NativeHeapPageHeaderCell(
    text: String,
    modifier: Modifier,
    active: Boolean,
    descending: Boolean,
    onSort: (NativeHeapPageSortColumn) -> Unit,
    column: NativeHeapPageSortColumn,
) {
    Text(
        text = if (active) "$text ${if (descending) "↓" else "↑"}" else text,
        modifier = modifier.clickable { onSort(column) },
        fontWeight = FontWeight.Bold,
        fontSize = ViewerTypography.bodyCompact.fontSize,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

@Composable
private fun NativeHeapPageTableRow(
    sample: NativeHeapSample,
    rowIndex: Int,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth().height(28.dp).background(
                if (rowIndex % 2 == 0) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(3.dp),
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            sample.functionName,
            Modifier.weight(1f),
            fontSize = ViewerTypography.bodyCompact.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            formatBytes(sample.allocatedBytes),
            Modifier.width(88.dp),
            fontSize = ViewerTypography.bodyCompact.fontSize,
            textAlign = TextAlign.End,
        )
        Text(
            formatBytes(sample.freedBytes),
            Modifier.width(80.dp),
            fontSize = ViewerTypography.bodyCompact.fontSize,
            textAlign = TextAlign.End,
        )
        Text(integer(sample.allocCount), Modifier.width(56.dp), fontSize = ViewerTypography.bodyCompact.fontSize, textAlign = TextAlign.End)
        Text(integer(sample.freeCount), Modifier.width(56.dp), fontSize = ViewerTypography.bodyCompact.fontSize, textAlign = TextAlign.End)
    }
}
