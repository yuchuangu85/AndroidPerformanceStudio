@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.model.HeapSummary
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.classes
import com.androidperformancestudio.memory.presentation.generated.resources.count
import com.androidperformancestudio.memory.presentation.generated.resources.duplicate_bitmaps
import com.androidperformancestudio.memory.presentation.generated.resources.heap_size
import com.androidperformancestudio.memory.presentation.generated.resources.leak_suspects
import com.androidperformancestudio.memory.presentation.generated.resources.lifecycle_leaks
import com.androidperformancestudio.memory.presentation.generated.resources.memory_composition
import com.androidperformancestudio.memory.presentation.generated.resources.memory_distribution
import com.androidperformancestudio.memory.presentation.generated.resources.memory_no_snapshot
import com.androidperformancestudio.memory.presentation.generated.resources.native_size
import com.androidperformancestudio.memory.presentation.generated.resources.objects
import com.androidperformancestudio.memory.presentation.generated.resources.retained
import com.androidperformancestudio.memory.presentation.generated.resources.retained_native_size
import com.androidperformancestudio.memory.presentation.generated.resources.shallow
import com.androidperformancestudio.memory.presentation.generated.resources.unavailable
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioCard
import com.androidperformancestudio.ui.studio.StudioTokens

@Composable
internal fun MemoryInvestigationWorkspace(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    Column(
        modifier.padding(StudioTokens.workspacePadding).testTag("memory-investigation-workspace"),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
    ) {
        if (state.viewMode == MemoryProfilerViewMode.ClassList) {
            MemoryPrimaryFilterBar(state, actions, language)
        }
        MemorySnapshotSummary(state, language)
        if (state.summary.heapSegments.isNotEmpty() || state.classes.isNotEmpty()) {
            StudioCard(
                modifier = Modifier.fillMaxWidth().testTag("memory-distribution-card"),
                contentPadding = 0.dp,
                contentSpacing = 0.dp,
            ) {
                MemoryHeapDistribution(state, language)
            }
        }
        if (state.viewMode == MemoryProfilerViewMode.ClassList) {
            StudioCard(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("memory-class-analysis-card"),
                contentPadding = 0.dp,
                contentSpacing = 0.dp,
            ) {
                MemoryInvestigationContent(state, actions, language, Modifier.fillMaxSize(), content)
            }
        } else {
            MemoryInvestigationContent(state, actions, language, Modifier.fillMaxWidth().weight(1f), content)
        }
    }
}

@Composable
private fun MemoryInvestigationContent(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val scroll = rememberScrollState()
        val classList = state.viewMode == MemoryProfilerViewMode.ClassList
        val minimumWidth = if (classList) 650.dp else 1120.dp
        val contentWidth = maxOf(maxWidth, minimumWidth)
        val scrollbarInset = if (classList && contentWidth <= maxWidth) 0.dp else 10.dp
        Row(
            Modifier
                .fillMaxSize()
                .padding(bottom = scrollbarInset)
                .horizontalScroll(scroll)
                .requiredWidth(contentWidth),
            horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
        ) {
            if (!classList) {
                StudioCard(
                    modifier = Modifier.width(190.dp).fillMaxHeight().testTag("memory-filters-card"),
                    contentPadding = 0.dp,
                    contentSpacing = 0.dp,
                ) {
                    MemoryFiltersPane(
                        state,
                        actions,
                        language,
                        Modifier.fillMaxSize().testTag("memory-workspace-filters"),
                    )
                }
            }
            if (classList) {
                content(Modifier.weight(1f).fillMaxHeight())
            } else {
                StudioCard(
                    modifier = Modifier.weight(1f).fillMaxHeight().testTag("memory-analysis-card"),
                    contentPadding = 0.dp,
                    contentSpacing = 0.dp,
                ) {
                    content(Modifier.fillMaxSize())
                }
                StudioCard(
                    modifier = Modifier.width(330.dp).fillMaxHeight().testTag("memory-inspector-card"),
                    contentPadding = 0.dp,
                    contentSpacing = 0.dp,
                ) {
                    MemoryObjectInspector(state, actions, language, Modifier.fillMaxSize())
                }
            }
        }
        if (scrollbarInset > 0.dp) {
            HorizontalScrollbar(
                rememberScrollbarAdapter(scroll),
                Modifier.align(Alignment.BottomStart).fillMaxWidth().testTag("memory-analysis-horizontal-scrollbar"),
            )
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod")
private fun MemorySnapshotSummary(
    state: MemoryProfilerState,
    language: UiLanguage,
) {
    val loaded = state.snapshotSummary != null || state.classes.isNotEmpty()
    val missing = localizedStringResource(Res.string.unavailable, language)
    val metrics = snapshotSummaryMetrics(state)

    fun formatted(value: Long?): String = if (loaded) value?.let(::formatBytes) ?: missing else missing

    val cards =
        if (state.viewMode == MemoryProfilerViewMode.ClassList) {
            val summary = state.classListSummary
            listOf(
                Res.string.lifecycle_leaks to if (loaded) integer(summary.leakCount) else missing,
                Res.string.duplicate_bitmaps to if (loaded) integer(summary.duplicateBitmapCount) else missing,
                Res.string.classes to if (loaded) integer(summary.classCount) else missing,
                Res.string.count to if (loaded) integer(summary.totalCount) else missing,
                Res.string.native_size to formatted(summary.totalNativeSize),
                Res.string.shallow to if (loaded) formatBytes(summary.totalShallowSize) else missing,
                Res.string.retained_native_size to
                    formatted(summary.totalRetainedNativeSize),
                Res.string.retained to formatted(summary.totalRetainedSize),
            )
        } else {
            listOf(
                Res.string.heap_size to if (loaded) formatBytes(metrics.shallowSize) else missing,
                Res.string.objects to if (loaded) integer(metrics.objectCount) else missing,
                Res.string.classes to if (loaded) integer(metrics.classCount) else missing,
                Res.string.leak_suspects to if (loaded) integer(state.leakSuspects.size) else missing,
            )
        }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("memory-snapshot-summary")) {
        val scroll = rememberScrollState()
        Row(
            Modifier
                .horizontalScroll(scroll)
                .requiredWidth(maxOf(maxWidth, 140.dp * cards.size)),
            horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
        ) {
            cards.forEachIndexed { index, (label, value) ->
                val hasLeakWarning =
                    loaded &&
                        when (label) {
                            Res.string.lifecycle_leaks -> state.classListSummary.leakCount > 0
                            Res.string.leak_suspects -> state.leakSuspects.isNotEmpty()
                            else -> false
                        }
                StudioCard(
                    modifier = Modifier.weight(1f).testTag("memory-metric-card-$index"),
                    contentPadding = 4.dp,
                    contentSpacing = 0.dp,
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (hasLeakWarning) {
                            MemoryLeakWarningIcon(language, Modifier.testTag("memory-leak-summary-warning"))
                        }
                        Text(
                            localizedStringResource(label, language),
                            Modifier.weight(1f),
                            style = ViewerTypography.secondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        value,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleLarge,
                        color =
                            if (hasLeakWarning) {
                                LocalViewerColors.current.warning
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The class-list summary follows the visible heap/scope/search rows, not the all-heap dump. */
internal fun snapshotSummaryMetrics(state: MemoryProfilerState): HeapSummary =
    if (state.viewMode == MemoryProfilerViewMode.ClassList) {
        state.summary.copy(
            objectCount = state.classListSummary.totalCount,
            shallowSize = state.classListSummary.totalShallowSize,
            classCount = state.classListSummary.classCount,
        )
    } else {
        state.summary
    }

@Composable
private fun MemoryHeapDistribution(
    state: MemoryProfilerState,
    language: UiLanguage,
) {
    val palette = listOf(Color(0xFF4F9BE8), Color(0xFF47B796), Color(0xFFCA9B4B), Color(0xFFAC85CC), Color(0xFFDA7382))
    val segments = state.summary.heapSegments.filter { it.size > 0L }
    val rows =
        if (segments.isNotEmpty()) {
            segments.map { it.name to it.size }
        } else {
            state.classes.sortedByDescending { it.shallowSize }.take(4).map {
                it.className to
                    it.shallowSize
            }
        }
    val total = if (segments.isNotEmpty()) segments.sumOf { it.size } else state.summary.shallowSize
    Column(
        Modifier.fillMaxWidth().padding(StudioTokens.compactContentPadding).testTag("memory-heap-distribution"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            localizedStringResource(
                if (segments.isNotEmpty()) Res.string.memory_distribution else Res.string.memory_composition,
                language,
            ),
            style = ViewerTypography.bodyCompact,
            fontWeight = FontWeight.SemiBold,
        )
        Row(Modifier.fillMaxWidth().height(12.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            if (total > 0L) {
                val displayed = rows.sumOf { it.second }.coerceAtMost(total)
                rows.filter { it.second > 0L }.forEachIndexed { index, (_, bytes) ->
                    Box(
                        Modifier
                            .weight(
                                (bytes.toDouble() / total).toFloat().coerceAtLeast(0.0001f),
                            ).fillMaxHeight()
                            .background(
                                palette[
                                    index %
                                        palette.size,
                                ],
                            ),
                    )
                }
                if (displayed <
                    total
                ) {
                    Box(
                        Modifier
                            .weight(
                                ((total - displayed).toDouble() / total).toFloat().coerceAtLeast(0.0001f),
                            ).fillMaxHeight(),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            rows.forEachIndexed { index, (name, bytes) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(Modifier.width(8.dp).height(8.dp).background(palette[index % palette.size]))
                    Text(
                        "$name  ${formatBytes(bytes)}",
                        style = ViewerTypography.secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (rows.isEmpty()) {
                Text(
                    localizedStringResource(Res.string.memory_no_snapshot, language),
                    style = ViewerTypography.secondary,
                )
            }
        }
    }
}
