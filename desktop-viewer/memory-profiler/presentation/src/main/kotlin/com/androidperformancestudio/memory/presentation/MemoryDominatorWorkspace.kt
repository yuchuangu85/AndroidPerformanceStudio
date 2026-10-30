@file:Suppress("FunctionNaming", "MagicNumber", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.class_name
import com.androidperformancestudio.memory.presentation.generated.resources.dominator_tree
import com.androidperformancestudio.memory.presentation.generated.resources.instance_details
import com.androidperformancestudio.memory.presentation.generated.resources.memory_dominator_collapse
import com.androidperformancestudio.memory.presentation.generated.resources.memory_dominator_expand
import com.androidperformancestudio.memory.presentation.generated.resources.memory_inspector_loading
import com.androidperformancestudio.memory.presentation.generated.resources.retained
import com.androidperformancestudio.memory.presentation.generated.resources.shallow
import com.androidperformancestudio.memory.presentation.generated.resources.unavailable
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioCard
import com.androidperformancestudio.ui.studio.StudioTokens
import com.androidperformancestudio.ui_components.generated.resources.ic_nav_rail_expand
import kotlinx.coroutines.launch
import java.awt.Cursor
import com.androidperformancestudio.ui_components.generated.resources.Res as UiRes

private val FILTERS_WIDTH = 190.dp
private val MINIMUM_TREE_WIDTH = 260.dp
private val MINIMUM_DETAILS_WIDTH = 260.dp
private val SPLITTER_WIDTH = 7.dp
private const val INITIAL_TREE_FRACTION = 0.62f
private val KEYBOARD_STEP = 16.dp

internal fun MemoryProfilerState.remoteDominatorCanExpand(objectId: Long): Boolean = dominatorChildTotals[objectId] != 0

internal fun MemoryProfilerState.remoteDominatorChildRequest(objectId: Long): Int? =
    when {
        !remoteDominatorCanExpand(objectId) -> null
        objectId in failedDominatorChildren -> dominatorChildOffsets[objectId] ?: 0
        dominatorChildTotals[objectId] == null || dominatorChildren[objectId].isNullOrEmpty() ->
            dominatorChildOffsets[objectId] ?: 0
        else -> null
    }

internal sealed interface MemoryRemoteDominatorEntry {
    val depth: Int
    val key: String

    data class Node(
        val row: MemoryDominatorRow,
        override val depth: Int,
    ) : MemoryRemoteDominatorEntry {
        override val key: String get() = "object:${row.objectId}"
    }

    data class ChildPage(
        val parentObjectId: Long,
        override val depth: Int,
    ) : MemoryRemoteDominatorEntry {
        override val key: String get() = "children:$parentObjectId"
    }
}

/** Only loaded pages participate; missing child pages must not change parent/leaf semantics. */
internal fun remoteDominatorEntries(
    state: MemoryProfilerState,
    expanded: Set<Long>,
): List<MemoryRemoteDominatorEntry> =
    buildList {
        val visited = mutableSetOf<Long>()
        val pending = ArrayDeque<MemoryRemoteDominatorEntry>()
        state.dominatorRows.forEach { pending.addLast(MemoryRemoteDominatorEntry.Node(it, 0)) }
        while (pending.isNotEmpty()) {
            when (val entry = pending.removeFirst()) {
                is MemoryRemoteDominatorEntry.ChildPage -> add(entry)
                is MemoryRemoteDominatorEntry.Node -> {
                    val id = entry.row.objectId
                    if (!visited.add(id)) continue
                    add(entry)
                    if (id in expanded && state.remoteDominatorCanExpand(id)) {
                        pending.addFirst(MemoryRemoteDominatorEntry.ChildPage(id, entry.depth + 1))
                        state.dominatorChildren[id].orEmpty().asReversed().forEach { child ->
                            pending.addFirst(MemoryRemoteDominatorEntry.Node(child, entry.depth + 1))
                        }
                    }
                }
            }
        }
    }

@Composable
internal fun MemoryRemoteDominatorPane(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
    modifier: Modifier,
) {
    val expanded = remember(state.activeSnapshotId, state.snapshotSummary?.id) { mutableStateMapOf<Long, Boolean>() }
    val expandedIds = expanded.filterValues { it }.keys
    val entries =
        remember(state.dominatorRows, state.dominatorChildren, state.dominatorChildTotals, expandedIds) {
            remoteDominatorEntries(state, expandedIds)
        }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.dominatorPageOffset) { listState.scrollToItem(0) }
    Column(
        modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp).testTag("memory-dominator-tree-content"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(localizedStringResource(Res.string.dominator_tree, language), fontWeight = FontWeight.Bold)
        MemoryHeapPageControls(
            MemoryHeapPage(
                state.dominatorPageOffset,
                state.dominatorTotalCount,
                state.dominatorRows.size,
                state.dominatorPageSize,
            ),
            language,
            "memory-dominator-page",
            actions.onLoadDominatorPage,
            enabled = !state.isSelectionLoading && !state.isDumping,
        )
        RemoteDominatorTable(language, Modifier.fillMaxWidth().weight(1f)) { listModifier ->
            LazyColumn(listModifier, state = listState) {
                itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
                    RemoteDominatorEntry(entry, index, state, expanded, actions, language) { parentId, offset ->
                        actions.onLoadDominatorChildren(parentId, offset)
                        val parentIndex = entries.indexOfFirst { it.key == "object:$parentId" }
                        if (parentIndex >= 0) scope.launch { listState.scrollToItem(parentIndex) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteDominatorTable(
    language: UiLanguage,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val scroll = rememberScrollState()
        val tableWidth = maxOf(maxWidth, 480.dp)
        val scrollbarInset = if (tableWidth > maxWidth) LocalScrollbarStyle.current.thickness else 0.dp
        Column(
            Modifier
                .fillMaxSize()
                .padding(bottom = scrollbarInset)
                .horizontalScroll(scroll)
                .requiredWidth(tableWidth),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    localizedStringResource(Res.string.class_name, language),
                    Modifier.weight(1f),
                    style = ViewerTypography.secondary,
                )
                Text(
                    localizedStringResource(Res.string.shallow, language),
                    Modifier.width(80.dp),
                    style = ViewerTypography.secondary,
                )
                Text(
                    localizedStringResource(Res.string.retained, language),
                    Modifier.width(80.dp),
                    style = ViewerTypography.secondary,
                )
            }
            content(Modifier.fillMaxWidth().weight(1f).testTag("memory-dominator-list"))
        }
        if (scrollbarInset > 0.dp) {
            HorizontalScrollbar(
                rememberScrollbarAdapter(scroll),
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .testTag("memory-dominator-horizontal-scrollbar"),
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun RemoteDominatorEntry(
    entry: MemoryRemoteDominatorEntry,
    index: Int,
    state: MemoryProfilerState,
    expanded: MutableMap<Long, Boolean>,
    actions: MemoryProfilerActions,
    language: UiLanguage,
    onLoadChildPage: (Long, Int) -> Unit,
) {
    val row =
        when (entry) {
            is MemoryRemoteDominatorEntry.Node -> entry.row
            is MemoryRemoteDominatorEntry.ChildPage -> {
                RemoteDominatorChildPage(entry, state, language) { offset ->
                    onLoadChildPage(entry.parentObjectId, offset)
                }
                return
            }
        }
    val isExpanded = expanded[row.objectId] == true
    val rowColor =
        if (index % 2 == 0) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("memory-dominator-tree-row-${row.objectId}")
            .background(rowColor)
            .clickable { actions.onSelectInstance(row.objectId) }
            .padding(start = (8 + entry.depth.coerceAtMost(10) * 12).dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.remoteDominatorCanExpand(row.objectId)) {
            MemoryHeapIconButton(
                UiRes.drawable.ic_nav_rail_expand,
                localizedStringResource(
                    if (isExpanded) Res.string.memory_dominator_collapse else Res.string.memory_dominator_expand,
                    language,
                    "0x${java.lang.Long.toHexString(row.objectId)}",
                ),
                "memory-dominator-expand-${row.objectId}",
                onClick = {
                    val opening = !isExpanded
                    expanded[row.objectId] = opening
                    if (opening) {
                        state.remoteDominatorChildRequest(row.objectId)?.let { offset ->
                            actions.onLoadDominatorChildren(row.objectId, offset)
                        }
                    }
                },
                rotation = if (isExpanded) 90f else 0f,
            )
        } else {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { Text(".") }
        }
        Text(
            "0x${java.lang.Long.toHexString(row.objectId)} \u00b7 ${row.className}",
            Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = ViewerTypography.bodyCompact,
        )
        Text(formatBytes(row.shallowSize), Modifier.width(80.dp), style = ViewerTypography.secondary)
        Text(formatBytes(row.retainedSize), Modifier.width(80.dp), style = ViewerTypography.secondary)
    }
}

@Composable
private fun RemoteDominatorChildPage(
    entry: MemoryRemoteDominatorEntry.ChildPage,
    state: MemoryProfilerState,
    language: UiLanguage,
    onLoadPage: (Int) -> Unit,
) {
    val id = entry.parentObjectId
    val modifier = Modifier.fillMaxWidth().padding(start = (8 + entry.depth.coerceAtMost(10) * 12).dp)
    val total = state.dominatorChildTotals[id]
    if (id in state.failedDominatorChildren) {
        Text(
            localizedStringResource(Res.string.unavailable, language),
            modifier.testTag("memory-dominator-children-$id-unavailable"),
            style = ViewerTypography.secondary,
        )
    } else if (total == null) {
        Text(
            localizedStringResource(Res.string.memory_inspector_loading, language),
            modifier.testTag("memory-dominator-children-$id-loading"),
            style = ViewerTypography.secondary,
        )
    } else {
        MemoryHeapPageControls(
            MemoryHeapPage(
                state.dominatorChildOffsets[id] ?: 0,
                total,
                state.dominatorChildren[id].orEmpty().size,
                state.dominatorPageSize,
            ),
            language,
            "memory-dominator-children-$id",
            onLoadPage = onLoadPage,
            modifier = modifier,
            enabled = !state.isSelectionLoading && !state.isDumping,
        )
    }
}

@Composable
internal fun MemoryDominatorWorkspace(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val scroll = rememberScrollState()
        val minimumWidth =
            FILTERS_WIDTH + StudioTokens.cardGap + MINIMUM_TREE_WIDTH + SPLITTER_WIDTH + MINIMUM_DETAILS_WIDTH
        val contentWidth = maxOf(maxWidth, minimumWidth)
        val scrollbarInset = if (contentWidth > maxWidth) LocalScrollbarStyle.current.thickness else 0.dp
        Row(
            Modifier
                .fillMaxSize()
                .padding(bottom = scrollbarInset)
                .horizontalScroll(scroll)
                .requiredWidth(contentWidth),
            horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
        ) {
            StudioCard(
                Modifier.width(FILTERS_WIDTH).fillMaxHeight().testTag("memory-filters-card"),
                contentPadding = 0.dp,
                contentSpacing = 0.dp,
            ) {
                MemoryFiltersPane(state, actions, language, Modifier.fillMaxSize().testTag("memory-workspace-filters"))
            }
            MemoryDominatorAnalysisCard(state, actions, language, Modifier.weight(1f).fillMaxHeight(), content)
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
private fun MemoryDominatorAnalysisCard(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val treeFraction = remember { mutableStateOf(INITIAL_TREE_FRACTION) }
    StudioCard(
        modifier.testTag("memory-dominator-analysis-card"),
        contentPadding = 0.dp,
        contentSpacing = 0.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val paneWidth = (maxWidth - SPLITTER_WIDTH).coerceAtLeast(0.dp)
            val minimumFraction = if (paneWidth > 0.dp) minOf(MINIMUM_TREE_WIDTH, paneWidth / 2) / paneWidth else 0f
            val maximumFraction =
                if (paneWidth > 0.dp) 1f - minOf(MINIMUM_DETAILS_WIDTH, paneWidth / 2) / paneWidth else 1f
            val range = minimumFraction..maximumFraction
            val fraction = treeFraction.value.coerceIn(range)
            Row(Modifier.fillMaxSize().testTag("memory-dominator-split-content")) {
                Box(Modifier.width(paneWidth * fraction).fillMaxHeight().testTag("memory-dominator-tree-pane")) {
                    content(Modifier.fillMaxSize())
                }
                DominatorDetailsDivider(treeFraction, paneWidth, range, language)
                Box(Modifier.weight(1f).fillMaxHeight().testTag("memory-inspector-card")) {
                    MemoryObjectInspector(state, actions, language, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun DominatorDetailsDivider(
    fraction: MutableState<Float>,
    paneWidth: Dp,
    range: ClosedFloatingPointRange<Float>,
    language: UiLanguage,
) {
    val density = LocalDensity.current
    val paneWidthPx = with(density) { paneWidth.toPx() }
    val label =
        localizedStringResource(Res.string.dominator_tree, language) + " / " +
            localizedStringResource(Res.string.instance_details, language)
    Box(
        Modifier
            .width(SPLITTER_WIDTH)
            .fillMaxHeight()
            .testTag("memory-dominator-details-resizer")
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(paneWidthPx) {
                detectHorizontalDragGestures { change, amount ->
                    change.consume()
                    if (paneWidthPx > 0f) {
                        fraction.value = (fraction.value.coerceIn(range) + amount / paneWidthPx).coerceIn(range)
                    }
                }
            }.semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(fraction.value.coerceIn(range), range)
                setProgress {
                    fraction.value = it.coerceIn(range)
                    true
                }
            }.onKeyEvent { event ->
                val direction =
                    when (event.key) {
                        Key.DirectionLeft -> -1
                        Key.DirectionRight -> 1
                        else -> 0
                    }
                if (event.type == KeyEventType.KeyDown && direction != 0 && paneWidthPx > 0f) {
                    val step = with(density) { KEYBOARD_STEP.toPx() } / paneWidthPx
                    fraction.value = (fraction.value.coerceIn(range) + direction * step).coerceIn(range)
                    true
                } else {
                    false
                }
            }.focusable(),
        contentAlignment = Alignment.Center,
    ) {
        VerticalDivider(Modifier.fillMaxHeight().testTag("memory-dominator-details-divider-line"))
    }
}
