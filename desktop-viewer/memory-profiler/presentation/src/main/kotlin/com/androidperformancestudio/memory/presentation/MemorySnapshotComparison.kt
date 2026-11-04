@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.model.HeapDiffEntry
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.class_name
import com.androidperformancestudio.memory.presentation.generated.resources.memory_after_count
import com.androidperformancestudio.memory.presentation.generated.resources.memory_after_retained
import com.androidperformancestudio.memory.presentation.generated.resources.memory_before_count
import com.androidperformancestudio.memory.presentation.generated.resources.memory_before_retained
import com.androidperformancestudio.memory.presentation.generated.resources.memory_compare
import com.androidperformancestudio.memory.presentation.generated.resources.memory_compare_a
import com.androidperformancestudio.memory.presentation.generated.resources.memory_compare_b
import com.androidperformancestudio.memory.presentation.generated.resources.memory_compare_empty
import com.androidperformancestudio.memory.presentation.generated.resources.memory_compare_limit
import com.androidperformancestudio.memory.presentation.generated.resources.memory_delta_count
import com.androidperformancestudio.memory.presentation.generated.resources.memory_delta_retained
import com.androidperformancestudio.memory.presentation.generated.resources.memory_delta_shallow
import com.androidperformancestudio.memory.presentation.generated.resources.memory_no_growth
import com.androidperformancestudio.memory.presentation.generated.resources.unavailable
import com.androidperformancestudio.ui.CopyableText
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource

@Composable
@Suppress("CyclomaticComplexMethod")
internal fun MemorySnapshotComparison(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val diff = state.heapDiff
    val snapshots = state.snapshotSummaries
    var before by remember(snapshots, diff?.beforeSnapshot?.id) {
        mutableStateOf(
            diff?.beforeSnapshot?.id ?: snapshots.dropLast(1).lastOrNull()?.id,
        )
    }
    var after by remember(snapshots, diff?.afterSnapshot?.id) {
        mutableStateOf(
            diff?.afterSnapshot?.id ?: snapshots.lastOrNull()?.id,
        )
    }
    var sortColumn by remember { mutableStateOf(3) }
    var descending by remember { mutableStateOf(true) }
    val missing = localizedStringResource(Res.string.unavailable, language)
    val scroll = rememberScrollState()
    val list = rememberLazyListState()
    val entries =
        remember(diff, state.searchText, state.matchCase, state.useRegex, sortColumn, descending) {
            val regex =
                if (state.useRegex) {
                    runCatching {
                        Regex(state.searchText, if (state.matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))
                    }.getOrNull()
                } else {
                    null
                }
            val filtered =
                diff?.entries.orEmpty().filter {
                    if (state.useRegex) {
                        regex?.containsMatchIn(
                            it.className,
                        ) == true
                    } else {
                        it.className.contains(state.searchText, ignoreCase = !state.matchCase)
                    }
                }
            val comparator =
                when (sortColumn) {
                    0 -> compareBy<HeapDiffEntry> { it.className }
                    1 -> compareBy { it.beforeCount }
                    2 -> compareBy { it.afterCount }
                    4 -> compareBy { it.beforeRetainedSize }
                    5 -> compareBy { it.afterRetainedSize }
                    6 -> compareBy { it.retainedSizeDelta }
                    7 -> compareBy { it.shallowSizeDelta }
                    else -> compareBy { it.countDelta }
                }
            filtered.sortedWith(if (descending) comparator.reversed() else comparator)
        }
    Column(modifier.testTag("memory-snapshot-comparison")) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(true, false).forEach { isBefore ->
                val label =
                    localizedStringResource(
                        if (isBefore) Res.string.memory_compare_a else Res.string.memory_compare_b,
                        language,
                    )
                DropdownSelector(
                    items = snapshots,
                    selectedItem = snapshots.firstOrNull { it.id == if (isBefore) before else after },
                    onItemSelected = { if (isBefore) before = it.id else after = it.id },
                    itemLabel = { it.sourceFile?.fileName?.toString() ?: it.id },
                    placeholder = label,
                    selectorDescription = label,
                    modifier = Modifier.width(170.dp),
                    fillWidth = true,
                )
            }
            ProfilerCompactButton(localizedStringResource(Res.string.memory_compare, language), {
                if (before != null && after != null) actions.onCompareSnapshots(before!!, after!!)
            }, enabled = before != null && after != null && before != after && !state.isDumping)
        }
        CopyableText(
            localizedStringResource(Res.string.memory_compare_limit, language),
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = ViewerTypography.secondary,
        )
        HorizontalDivider()
        if (diff == null) {
            CopyableText(localizedStringResource(Res.string.memory_compare_empty, language), Modifier.padding(16.dp))
        } else {
            val beforeName =
                diff.beforeSnapshot
                    ?.sourceFile
                    ?.fileName
                    ?.toString() ?: diff.beforeSnapshot?.id ?: missing
            val afterName =
                diff.afterSnapshot
                    ?.sourceFile
                    ?.fileName
                    ?.toString() ?: diff.afterSnapshot?.id ?: missing
            Text(
                "A: $beforeName  |  B: $afterName",
                Modifier.padding(10.dp),
                style = ViewerTypography.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(end = 10.dp, bottom = 10.dp)
                        .horizontalScroll(scroll)
                        .width(1060.dp),
                ) {
                    val labels =
                        listOf(
                            Res.string.class_name,
                            Res.string.memory_before_count,
                            Res.string.memory_after_count,
                            Res.string.memory_delta_count,
                            Res.string.memory_before_retained,
                            Res.string.memory_after_retained,
                            Res.string.memory_delta_retained,
                            Res.string.memory_delta_shallow,
                        )
                    Row(
                        Modifier.fillMaxWidth().height(36.dp).background(MaterialTheme.colorScheme.surfaceContainerLow),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        labels.forEachIndexed { index, label ->
                            Text(
                                localizedStringResource(label, language) +
                                    if (sortColumn == index) {
                                        if (descending) " \u2193" else " \u2191"
                                    } else {
                                        ""
                                    },
                                Modifier
                                    .width(if (index == 0) 276.dp else 112.dp)
                                    .clickable {
                                        if (sortColumn == index) {
                                            descending = !descending
                                        } else {
                                            sortColumn = index
                                            descending = true
                                        }
                                    }.padding(horizontal = 8.dp),
                                style = ViewerTypography.secondary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                    HorizontalDivider()
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list) {
                        if (entries.isEmpty()) {
                            item {
                                CopyableText(
                                    localizedStringResource(Res.string.memory_no_growth, language),
                                    Modifier.padding(16.dp),
                                )
                            }
                        }
                        itemsIndexed(entries, key = { _, entry -> entry.className }) { index, entry ->
                            Row(
                                Modifier.fillMaxWidth().height(34.dp).background(
                                    if (index % 2 ==
                                        0
                                    ) {
                                        MaterialTheme.colorScheme.surface
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val values =
                                    listOf(
                                        entry.className,
                                        integer(entry.beforeCount),
                                        integer(entry.afterCount),
                                        signedCount(entry.countDelta),
                                        entry.beforeRetainedSize?.let(::formatBytes) ?: missing,
                                        entry.afterRetainedSize?.let(::formatBytes) ?: missing,
                                        entry.retainedSizeDelta?.let(::signedMemoryBytes) ?: missing,
                                        signedMemoryBytes(entry.shallowSizeDelta),
                                    )
                                values.forEachIndexed { column, value ->
                                    Text(
                                        value,
                                        Modifier
                                            .width(
                                                if (column ==
                                                    0
                                                ) {
                                                    276.dp
                                                } else {
                                                    112.dp
                                                },
                                            ).padding(horizontal = 8.dp),
                                        style = ViewerTypography.secondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color =
                                            if (column == 3 &&
                                                entry.countDelta > 0
                                            ) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalScrollbar(
                    rememberScrollbarAdapter(scroll),
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 10.dp),
                )
                VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).height(200.dp))
            }
        }
    }
}

private fun signedCount(value: Int): String = if (value > 0) "+${integer(value)}" else integer(value)

private fun signedMemoryBytes(value: Long): String =
    when {
        value > 0 -> "+${formatBytes(value)}"
        value < 0 -> "-${formatBytes(-value)}"
        else -> formatBytes(0)
    }
