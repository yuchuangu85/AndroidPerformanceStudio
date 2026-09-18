@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "MagicNumber",
    "MaxLineLength",
    "TooManyFunctions",
    "CyclomaticComplexMethod",
    "ktlint:standard:function-naming",
    "ktlint:standard:argument-list-wrapping",
    "ktlint:standard:binary-expression-wrapping",
    "ktlint:standard:chain-method-continuation",
    "ktlint:standard:indent",
    "ktlint:standard:import-ordering",
    "ktlint:standard:max-line-length",
    "ktlint:standard:no-unused-imports",
)

package com.androidperformancestudio.memory.presentation

import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ViewerDimensions

import com.androidperformancestudio.ui.ViewerTypography

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.model.ActivityLeakEntry
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapSummary
import com.androidperformancestudio.memory.model.NativeHeapAnalysis
import com.androidperformancestudio.memory.model.NativeHeapTrace
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.activity
import com.androidperformancestudio.memory.presentation.generated.resources.activity_leak_entry
import com.androidperformancestudio.memory.presentation.generated.resources.activity_leaks
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_analysis
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_entry
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_estimated_pixel_memory
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_java_native_memory
import com.androidperformancestudio.memory.presentation.generated.resources.class_histogram
import com.androidperformancestudio.memory.presentation.generated.resources.class_name
import com.androidperformancestudio.memory.presentation.generated.resources.classes
import com.androidperformancestudio.memory.presentation.generated.resources.cancel
import com.androidperformancestudio.memory.presentation.generated.resources.cleanup_warning
import com.androidperformancestudio.memory.presentation.generated.resources.count
import com.androidperformancestudio.memory.presentation.generated.resources.count_value
import com.androidperformancestudio.memory.presentation.generated.resources.class_level_diff
import com.androidperformancestudio.memory.presentation.generated.resources.dominator_tree
import com.androidperformancestudio.memory.presentation.generated.resources.diff_view
import com.androidperformancestudio.memory.presentation.generated.resources.heap_diff
import com.androidperformancestudio.memory.presentation.generated.resources.heap_diff_entry
import com.androidperformancestudio.memory.presentation.generated.resources.heap_size
import com.androidperformancestudio.memory.presentation.generated.resources.import_or_dump_an_hprof_file_to_show_class_histogram
import com.androidperformancestudio.memory.presentation.generated.resources.in_progress
import com.androidperformancestudio.memory.presentation.generated.resources.leak_suspect_summary
import com.androidperformancestudio.memory.presentation.generated.resources.leak_suspect_title
import com.androidperformancestudio.memory.presentation.generated.resources.leak_suspects
import com.androidperformancestudio.memory.presentation.generated.resources.manual_verification
import com.androidperformancestudio.memory.presentation.generated.resources.mapping_loaded_note
import com.androidperformancestudio.memory.presentation.generated.resources.native_heap
import com.androidperformancestudio.memory.presentation.generated.resources.open_native_heap
import com.androidperformancestudio.memory.presentation.generated.resources.native_heap_total
import com.androidperformancestudio.memory.presentation.generated.resources.native_heap_trace_summary
import com.androidperformancestudio.memory.presentation.generated.resources.no_activity_leaks_detected
import com.androidperformancestudio.memory.presentation.generated.resources.no_class_changes_between_the_latest_two_heap_dumps
import com.androidperformancestudio.memory.presentation.generated.resources.no_leak_suspects_detected
import com.androidperformancestudio.memory.presentation.generated.resources.no_native_heap_trace
import com.androidperformancestudio.memory.presentation.generated.resources.objects
import com.androidperformancestudio.memory.presentation.generated.resources.overview
import com.androidperformancestudio.memory.presentation.generated.resources.reference_chain_entry
import com.androidperformancestudio.memory.presentation.generated.resources.retained
import com.androidperformancestudio.memory.presentation.generated.resources.shallow
import com.androidperformancestudio.memory.presentation.generated.resources.unavailable
import com.androidperformancestudio.memory.presentation.generated.resources.warning
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

@Composable
public fun MemoryProfilerScreen(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage = UiLanguage.ENGLISH,
    modifier: Modifier = Modifier,
) {
    val presentedState = MemoryProfilerPresenter.present(state)
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
        ) {
            if (presentedState.artifact != null || presentedState.mappingLoaded) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    presentedState.snapshotSummary?.let { snapshot ->
                        Text(
                            "Snapshot: ${snapshot.id} · ${snapshot.format} · ${snapshot.capabilities.size} capabilities" +
                                snapshot.sourceFileDigest?.let { " · source ${it.take(12)}" }.orEmpty() +
                                snapshot.mappingDigest?.let { " · mapping ${it.take(12)}" }.orEmpty() +
                                if (snapshot.indexFile != null) " · indexed" else "",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = ViewerTypography.bodyCompact.fontSize,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                    presentedState.artifact?.let { artifact ->
                        Text(
                            "Evidence: ${artifact.kind.value} · ${artifact.completeness} · " +
                                "${artifact.availableCapabilities.size} capabilities",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = ViewerTypography.bodyCompact.fontSize,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                    if (presentedState.mappingLoaded) {
                        Text(
                            localizedStringResource(Res.string.mapping_loaded_note, language),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = ViewerTypography.bodyCompact.fontSize,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
            when (presentedState.viewMode) {
                MemoryProfilerViewMode.ClassList ->
                    MemoryProfilerClassListPane(
                        state = presentedState,
                        actions = actions,
                        language = language,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                MemoryProfilerViewMode.Dominators ->
                    MemoryProfilerDominatorPane(
                        rows = presentedState.dominatorRows,
                        actions = actions,
                        language = language,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                MemoryProfilerViewMode.Diff ->
                    MemoryProfilerDiffPane(
                        diff = presentedState.heapDiff,
                        language = language,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                MemoryProfilerViewMode.BitmapDump ->
                    MemoryProfilerBitmapDumpPage(
                        session = presentedState.bitmapDumpSession,
                        comparison = presentedState.bitmapDumpComparison,
                        isLoading = presentedState.isDumping,
                        language = language,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                MemoryProfilerViewMode.NativeHeap ->
                    MemoryProfilerNativeHeapPage(
                        trace = presentedState.nativeHeapTrace,
                        analysis = presentedState.nativeHeapAnalysis,
                        isLoading = presentedState.isDumping,
                        language = language,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                MemoryProfilerViewMode.Dashboard -> {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Overview(summary = presentedState.summary, activityCount = presentedState.activityCount, language = language)
                    Histogram(
                        classes = presentedState.classes,
                        sort = presentedState.sort,
                        actions = actions,
                        highlightedClassName = presentedState.highlightedClassName,
                        language = language,
                    )
                    LeakSuspectsPhaseTwo(presentedState, language)
                    ActivityLeakSection(presentedState.activityLeaks, language)
                    NativeHeapSummarySection(
                        trace = presentedState.nativeHeapTrace,
                        analysis = presentedState.nativeHeapAnalysis,
                        language = language,
                        onOpen = { actions.onChangeViewMode(MemoryProfilerViewMode.NativeHeap) },
                    )
                    HeapDiffSection(presentedState.heapDiff, language)
                    BitmapSection(presentedState.bitmapInstances, language)
                }
                }
            }
            MemoryProfilerStatusBar(
                state = presentedState,
                actions = actions,
                language = language,
            )
        }
    }
}

@Composable
private fun MemoryProfilerDominatorPane(
    rows: List<MemoryDominatorRow>,
    actions: MemoryProfilerActions,
    language: UiLanguage,
    modifier: Modifier,
) {
    val expanded = remember(rows) { mutableStateMapOf<Long, Boolean>() }
    val childrenByParent = remember(rows) { rows.groupBy { it.parentObjectId } }
    val rowsById = remember(rows) { rows.associateBy { it.objectId } }

    fun isVisible(row: MemoryDominatorRow): Boolean {
        var parentId = row.parentObjectId
        while (parentId != null) {
            if (expanded[parentId] == false) return false
            parentId = rowsById[parentId]?.parentObjectId
        }
        return true
    }

    val visibleRows = rows.filter(::isVisible)
    Column(
        modifier = modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(localizedStringResource(Res.string.dominator_tree, language), fontWeight = FontWeight.Bold)
        Text(
            text = "Retained-size evidence; selecting a row opens the object inspector.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = ViewerTypography.secondary.fontSize,
        )
        if (rows.isEmpty()) {
            Text(
                text = localizedStringResource(Res.string.import_or_dump_an_hprof_file_to_show_class_histogram, language),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(visibleRows, key = { _, row -> row.objectId }) { index, row ->
                    val hasChildren = childrenByParent[row.objectId].orEmpty().isNotEmpty()
                    val isExpanded = expanded[row.objectId] != false
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (index % 2 == 0) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
                                    RoundedCornerShape(3.dp),
                                )
                                .clickable { actions.onSelectInstance(row.objectId) }
                                .padding(start = (8 + row.depth * 16).dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text =
                                when {
                                    !hasChildren -> "·"
                                    isExpanded -> "▾"
                                    else -> "▸"
                                },
                            modifier =
                                Modifier.clickable(enabled = hasChildren) {
                                    expanded[row.objectId] = !isExpanded
                                },
                            fontSize = ViewerTypography.bodyCompact.fontSize,
                        )
                        Text(
                            text = "0x${java.lang.Long.toHexString(row.objectId)} · ${row.className}",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = ViewerTypography.bodyCompact.fontSize,
                        )
                        Text(formatBytes(row.shallowSize), fontSize = ViewerTypography.secondary.fontSize)
                        Text(formatBytes(row.retainedSize), fontSize = ViewerTypography.secondary.fontSize)
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryProfilerDiffPane(
    diff: com.androidperformancestudio.memory.model.HeapDiff?,
    language: UiLanguage,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(localizedStringResource(Res.string.diff_view, language), fontWeight = FontWeight.Bold)
        Text(
            localizedStringResource(Res.string.class_level_diff, language),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = ViewerTypography.secondary.fontSize,
        )
        if (diff == null || diff.entries.isEmpty()) {
            Text(localizedStringResource(Res.string.no_class_changes_between_the_latest_two_heap_dumps, language))
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(diff.entries, key = { _, entry -> entry.className }) { index, entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (index % 2 == 0) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(3.dp),
                            ).padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(entry.className, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${entry.beforeCount} → ${entry.afterCount}")
                        Text(entry.countDelta.withSign())
                        Text(formatBytes(entry.shallowSizeDelta))
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryProfilerStatusBar(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    var hasStatus = false
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(ViewerDimensions.footerHeight)
                .background(colors.toolbar)
                .border(
                    ViewerDimensions.hairline,
                    colors.border,
                    RoundedCornerShape(0.dp),
                ).horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .testTag("memory-profiler-status-bar"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.operationMessage?.let { message ->
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = state.loadPhase.name,
                color = colors.mutedText,
                fontSize = ViewerTypography.label.fontSize,
                maxLines = 1,
            )
            MemoryProfilerStatusMessage(
                label = localizedStringResource(Res.string.in_progress, language),
                message = message,
                messageColor = colors.secondaryText,
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.cancel, language),
                onClick = actions.onCancelOperation,
            )
            hasStatus = true
        }
        state.error?.let { error ->
            if (hasStatus) MemoryProfilerStatusDivider()
            MemoryProfilerStatusMessage(
                label = error.title,
                message = error.detail,
                messageColor = MaterialTheme.colorScheme.error,
            )
            ProfilerCompactButton(
                text = error.retryLabel,
                onClick = actions.onRetry,
            )
            hasStatus = true
        }
        state.cleanupWarning?.let { warning ->
            if (hasStatus) MemoryProfilerStatusDivider()
            MemoryProfilerStatusMessage(
                label = localizedStringResource(Res.string.cleanup_warning, language),
                message = warning,
                messageColor = MaterialTheme.colorScheme.tertiary,
            )
            hasStatus = true
        }
        state.warning?.let { warning ->
            if (hasStatus) MemoryProfilerStatusDivider()
            MemoryProfilerStatusMessage(
                label = localizedStringResource(Res.string.warning, language),
                message = warning,
                messageColor = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun MemoryProfilerStatusMessage(
    label: String,
    message: String,
    messageColor: Color,
) {
    val colors = LocalViewerColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label:",
            color = colors.mutedText,
            fontSize = ViewerTypography.label.fontSize,
            maxLines = 1,
        )
        Text(
            text = message,
            color = messageColor,
            fontSize = ViewerTypography.secondary.fontSize,
            lineHeight = ViewerTypography.secondary.lineHeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MemoryProfilerStatusDivider() {
    Text(
        text = "•",
        color = LocalViewerColors.current.mutedText,
        fontSize = ViewerTypography.label.fontSize,
    )
}

@Composable
private fun Overview(
    summary: HeapSummary,
    activityCount: Int,
    language: UiLanguage,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(localizedStringResource(Res.string.overview, language), fontWeight = FontWeight.Bold)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            MetricCard(localizedStringResource(Res.string.heap_size, language), formatBytes(summary.shallowSize), Modifier.weight(1f))
            MetricCard(localizedStringResource(Res.string.objects, language), integer(summary.objectCount), Modifier.weight(1f))
            MetricCard(localizedStringResource(Res.string.classes, language), integer(summary.classCount), Modifier.weight(1f))
            MetricCard(
                localizedStringResource(Res.string.activity, language),
                localizedStringResource(Res.string.count_value, language, integer(activityCount)),
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier,
) {
    Column(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                .padding(8.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Histogram(
    classes: List<ClassStats>,
    sort: MemoryHistogramSort,
    actions: MemoryProfilerActions,
    highlightedClassName: String?,
    language: UiLanguage,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp))
                .testTag("memory-profiler-class-histogram"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(localizedStringResource(Res.string.class_histogram, language), fontWeight = FontWeight.Bold)
        HistogramHeader(sort, actions, language)
        if (classes.isEmpty()) {
            Text(localizedStringResource(Res.string.import_or_dump_an_hprof_file_to_show_class_histogram, language))
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                itemsIndexed(classes, key = { _, stats -> stats.className }) { index, stats ->
                    HistogramRow(
                        stats = stats,
                        rowIndex = index,
                        highlighted = stats.className == highlightedClassName,
                        onClick = { actions.onHighlightClass(stats.className) },
                        language = language,
                    )
                }
            }
        }
    }
}

@Composable
private fun HistogramHeader(
    sort: MemoryHistogramSort,
    actions: MemoryProfilerActions,
    language: UiLanguage,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(localizedStringResource(Res.string.class_name, language), Modifier.weight(1f), fontWeight = FontWeight.Bold)
        SortHeader(
            label = localizedStringResource(Res.string.count, language),
            headerSort = MemoryHistogramSort.Count,
            currentSort = sort,
            actions = actions,
            modifier = Modifier.width(96.dp),
        )
        SortHeader(
            label = localizedStringResource(Res.string.shallow, language),
            headerSort = MemoryHistogramSort.Shallow,
            currentSort = sort,
            actions = actions,
            modifier = Modifier.width(112.dp),
        )
        Text(localizedStringResource(Res.string.retained, language), Modifier.width(140.dp), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SortHeader(
    label: String,
    headerSort: MemoryHistogramSort,
    currentSort: MemoryHistogramSort,
    actions: MemoryProfilerActions,
    modifier: Modifier,
) {
    Text(
        text = if (currentSort == headerSort) "$label ↓" else label,
        modifier = modifier.clickable { actions.onSortHistogram(headerSort) },
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun HistogramRow(
    stats: ClassStats,
    rowIndex: Int,
    highlighted: Boolean,
    onClick: () -> Unit,
    language: UiLanguage,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(
                when {
                    highlighted -> MaterialTheme.colorScheme.primaryContainer
                    rowIndex % 2 == 0 -> MaterialTheme.colorScheme.surfaceContainerLow
                    else -> MaterialTheme.colorScheme.surface
                },
                RoundedCornerShape(3.dp),
            ).clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stats.displayClassName,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(integer(stats.instanceCount), Modifier.width(96.dp))
        Text(formatBytes(stats.shallowSize), Modifier.width(112.dp))
        Text(
            text = stats.retainedSize?.let(::formatBytes) ?: localizedStringResource(Res.string.unavailable, language),
            modifier = Modifier.width(140.dp),
        )
    }
}

@Composable
private fun LeakSuspectsPhaseTwo(
    state: MemoryProfilerState,
    language: UiLanguage,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp))
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(localizedStringResource(Res.string.leak_suspects, language), fontWeight = FontWeight.Bold)
        if (state.leakSuspects.isEmpty()) {
            Text(localizedStringResource(Res.string.no_leak_suspects_detected, language))
        } else {
            state.leakSuspects.forEach { suspect ->
                var expanded by remember(suspect) { mutableStateOf(false) }
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(3.dp),
                            ).clickable { expanded = !expanded }
                            .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        localizedStringResource(
                            Res.string.leak_suspect_title,
                            language,
                            if (expanded) "▾" else "▸",
                            suspect.className,
                            suspect.reason,
                        ),
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        buildString {
                            append(
                                localizedStringResource(
                                    Res.string.leak_suspect_summary,
                                    language,
                                    suspect.retainedSize?.let(::formatBytes) ?: "—",
                                    (suspect.confidence * 100).toInt(),
                                ),
                            )
                            if (suspect.requiresManualVerification) {
                                append(" · ")
                                append(localizedStringResource(Res.string.manual_verification, language))
                            }
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (expanded) {
                        suspect.referenceChain.forEachIndexed { index, reference ->
                            Text(
                                localizedStringResource(
                                    Res.string.reference_chain_entry,
                                    language,
                                    "  ".repeat(index),
                                    reference.fieldName,
                                    reference.targetClassName.ifBlank { reference.targetObjectId.toString() },
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityLeakSection(
    leaks: List<ActivityLeakEntry>,
    language: UiLanguage,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp))
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(localizedStringResource(Res.string.activity_leaks, language), fontWeight = FontWeight.Bold)
        if (leaks.isEmpty()) {
            Text(localizedStringResource(Res.string.no_activity_leaks_detected, language))
        } else {
            leaks.take(10).forEach { entry ->
                Text(
                    localizedStringResource(
                        Res.string.activity_leak_entry,
                        language,
                        entry.className,
                        integer(entry.liveInstanceCount),
                        integer(entry.destroyedInstanceCount),
                        entry.retainedSize?.let(::formatBytes) ?: localizedStringResource(Res.string.unavailable, language),
                    ),
                )
            }
        }
    }
}

@Composable
private fun NativeHeapSummarySection(
    trace: NativeHeapTrace?,
    analysis: NativeHeapAnalysis,
    language: UiLanguage,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(localizedStringResource(Res.string.native_heap, language), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.open_native_heap, language),
                enabled = trace != null,
                onClick = onOpen,
            )
        }
        if (trace == null) {
            Text(localizedStringResource(Res.string.no_native_heap_trace, language), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
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
        }
    }
}

@Composable
private fun HeapDiffSection(
    diff: com.androidperformancestudio.memory.model.HeapDiff?,
    language: UiLanguage,
) {
    if (diff == null) return
    val changedEntries = diff.entries.filter { it.countDelta != 0 || it.shallowSizeDelta != 0L }
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(localizedStringResource(Res.string.heap_diff, language), fontWeight = FontWeight.Bold)
        if (changedEntries.isEmpty()) {
            Text(localizedStringResource(Res.string.no_class_changes_between_the_latest_two_heap_dumps, language))
        } else {
            changedEntries.take(10).forEach { entry ->
                Text(
                    localizedStringResource(
                        Res.string.heap_diff_entry,
                        language,
                        entry.className,
                        entry.beforeCount,
                        entry.afterCount,
                        entry.countDelta.withSign(),
                        entry.shallowSizeDelta.withSign(),
                    ),
                )
            }
        }
    }
}

@Composable
private fun BitmapSection(
    bitmaps: List<com.androidperformancestudio.memory.model.BitmapInstanceStats>,
    language: UiLanguage,
) {
    if (bitmaps.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(localizedStringResource(Res.string.bitmap_analysis, language), fontWeight = FontWeight.Bold)
        bitmaps.take(8).forEach { bitmap ->
            Text(
                localizedStringResource(
                    Res.string.bitmap_entry,
                    language,
                    bitmap.objectId,
                    bitmap.width ?: "?",
                    bitmap.height ?: "?",
                    formatBytes(bitmap.retainedSize),
                ),
            )
            bitmap.estimatedPixelBytes?.let { estimated ->
                Text(
                    localizedStringResource(Res.string.bitmap_estimated_pixel_memory, language, formatBytes(estimated)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = ViewerTypography.bodyCompact.fontSize,
                )
                val nativeSize = bitmap.nativeSizeBytes
                if (bitmap.javaSizeBytes > 0L && nativeSize != null) {
                    Text(
                        localizedStringResource(
                            Res.string.bitmap_java_native_memory,
                            language,
                            formatBytes(bitmap.javaSizeBytes),
                            formatBytes(nativeSize),
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = ViewerTypography.bodyCompact.fontSize,
                    )
                }
            }
        }
    }
}

private fun Int.withSign(): String = if (this >= 0) "+$this" else toString()

private fun Long.withSign(): String = if (this >= 0) "+$this" else toString()
