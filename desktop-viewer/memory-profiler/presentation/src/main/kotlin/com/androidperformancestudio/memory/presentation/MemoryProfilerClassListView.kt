@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "MaxLineLength",
    "TooManyFunctions",
    "ktlint:standard:function-naming",
    "ktlint:standard:import-ordering",
    "ktlint:standard:no-unused-imports",
    "ktlint:standard:argument-list-wrapping",
    "ktlint:standard:function-literal",
    "ktlint:standard:chain-method-continuation",
    "ktlint:standard:blank-line-before-declaration",
    "ktlint:standard:no-consecutive-blank-lines",
)

package com.androidperformancestudio.memory.presentation

import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ViewerTypography

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.model.ObjectReference
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.activity_fragment_leak
import com.androidperformancestudio.memory.presentation.generated.resources.all_classes
import com.androidperformancestudio.memory.presentation.generated.resources.all_heaps
import com.androidperformancestudio.memory.presentation.generated.resources.all_issues
import com.androidperformancestudio.memory.presentation.generated.resources.allocation_method
import com.androidperformancestudio.memory.presentation.generated.resources.allocation_method_option
import com.androidperformancestudio.memory.presentation.generated.resources.allocations
import com.androidperformancestudio.memory.presentation.generated.resources.allocations_size
import com.androidperformancestudio.memory.presentation.generated.resources.arrange_by
import com.androidperformancestudio.memory.presentation.generated.resources.array_elements
import com.androidperformancestudio.memory.presentation.generated.resources.array_page_range
import com.androidperformancestudio.memory.presentation.generated.resources.array_values_unavailable
import com.androidperformancestudio.memory.presentation.generated.resources.back
import com.androidperformancestudio.memory.presentation.generated.resources.forward
import com.androidperformancestudio.memory.presentation.generated.resources.callstack_name
import com.androidperformancestudio.memory.presentation.generated.resources.callstack_option
import com.androidperformancestudio.memory.presentation.generated.resources.class_name
import com.androidperformancestudio.memory.presentation.generated.resources.class_option
import com.androidperformancestudio.memory.presentation.generated.resources.class_scope
import com.androidperformancestudio.memory.presentation.generated.resources.classes
import com.androidperformancestudio.memory.presentation.generated.resources.copy_object_id
import com.androidperformancestudio.memory.presentation.generated.resources.count
import com.androidperformancestudio.memory.presentation.generated.resources.deallocations
import com.androidperformancestudio.memory.presentation.generated.resources.deallocations_size
import com.androidperformancestudio.memory.presentation.generated.resources.delete_filter
import com.androidperformancestudio.memory.presentation.generated.resources.depth
import com.androidperformancestudio.memory.presentation.generated.resources.estimated_native_size
import com.androidperformancestudio.memory.presentation.generated.resources.duplicate_bitmaps
import com.androidperformancestudio.memory.presentation.generated.resources.duplicates_summary
import com.androidperformancestudio.memory.presentation.generated.resources.fields
import com.androidperformancestudio.memory.presentation.generated.resources.filter_by
import com.androidperformancestudio.memory.presentation.generated.resources.filter_classes
import com.androidperformancestudio.memory.presentation.generated.resources.heap
import com.androidperformancestudio.memory.presentation.generated.resources.import_or_dump_an_hprof_file_to_show_class_histogram
import com.androidperformancestudio.memory.presentation.generated.resources.instance
import com.androidperformancestudio.memory.presentation.generated.resources.instance_details
import com.androidperformancestudio.memory.presentation.generated.resources.instance_list
import com.androidperformancestudio.memory.presentation.generated.resources.next_array_page
import com.androidperformancestudio.memory.presentation.generated.resources.no_object_fields
import com.androidperformancestudio.memory.presentation.generated.resources.previous_array_page
import com.androidperformancestudio.memory.presentation.generated.resources.leaks_summary
import com.androidperformancestudio.memory.presentation.generated.resources.match_case
import com.androidperformancestudio.memory.presentation.generated.resources.module_name
import com.androidperformancestudio.memory.presentation.generated.resources.native_size
import com.androidperformancestudio.memory.presentation.generated.resources.no_instances_for_class
import com.androidperformancestudio.memory.presentation.generated.resources.no_references
import com.androidperformancestudio.memory.presentation.generated.resources.none
import com.androidperformancestudio.memory.presentation.generated.resources.package_name
import com.androidperformancestudio.memory.presentation.generated.resources.package_option
import com.androidperformancestudio.memory.presentation.generated.resources.pin_object
import com.androidperformancestudio.memory.presentation.generated.resources.pinned_objects
import com.androidperformancestudio.memory.presentation.generated.resources.project_classes
import com.androidperformancestudio.memory.presentation.generated.resources.reference_chain
import com.androidperformancestudio.memory.presentation.generated.resources.references
import com.androidperformancestudio.memory.presentation.generated.resources.regex
import com.androidperformancestudio.memory.presentation.generated.resources.retained
import com.androidperformancestudio.memory.presentation.generated.resources.root_reachability
import com.androidperformancestudio.memory.presentation.generated.resources.root_reachability_unavailable
import com.androidperformancestudio.memory.presentation.generated.resources.select_a_class_to_view_its_instances
import com.androidperformancestudio.memory.presentation.generated.resources.select_an_instance_to_view_details
import com.androidperformancestudio.memory.presentation.generated.resources.shallow
import com.androidperformancestudio.memory.presentation.generated.resources.save_filter
import com.androidperformancestudio.memory.presentation.generated.resources.saved_filters
import com.androidperformancestudio.memory.presentation.generated.resources.shallow_size_change
import com.androidperformancestudio.memory.presentation.generated.resources.system_classes
import com.androidperformancestudio.memory.presentation.generated.resources.total_count
import com.androidperformancestudio.memory.presentation.generated.resources.unavailable
import com.androidperformancestudio.memory.presentation.generated.resources.unpin_object
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

/**
 * Android Studio-style heap class list, rendered as a stacked layout:
 * filter bar → summary → class table → instance list of the selected class.
 */
@Composable
@Suppress("CyclomaticComplexMethod")
public fun MemoryProfilerClassListPane(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val leakClasses = MemoryProfilerPresenter.leakClassNames(state)
    val duplicateClasses = MemoryProfilerPresenter.duplicateBitmapClasses(state)
    val leftPaneFraction = remember { mutableStateOf(INITIAL_LEFT_PANE_FRACTION) }
    Column(modifier.fillMaxSize()) {
        FilterBar(state, actions, language)
        HorizontalDivider()
        SummaryBar(summary = state.classListSummary, language = language)
        HorizontalDivider()
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val availableWidth = maxWidth
            val totalWidthPx = with(density) { availableWidth.toPx() }
            Row(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.width(availableWidth * leftPaneFraction.value).fillMaxHeight(),
                ) {
                    val classifierHorizontalScrollState = rememberScrollState()
                    val classifierListState = rememberLazyListState()
                    val scrollbarStyle =
                        LocalScrollbarStyle.current.copy(
                            unhoverColor = LocalViewerColors.current.accent.copy(alpha = 0.65f),
                            hoverColor = LocalViewerColors.current.accent,
                        )
                    val scrollbarThickness = scrollbarStyle.thickness
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(end = scrollbarThickness, bottom = scrollbarThickness),
                        ) {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .horizontalScroll(classifierHorizontalScrollState),
                            ) {
                                Column(Modifier.requiredWidth(CLASSIFIER_TABLE_WIDTH).fillMaxHeight()) {
                                    ClassTableHeader(
                                        language,
                                        state.arrangeBy,
                                        state.classifierSortColumn,
                                        state.classifierSortDirection,
                                        state.visibleClassifierColumns,
                                        actions.onClassifierSort,
                                        modifier = Modifier.height(CLASSIFIER_HEADER_HEIGHT),
                                    )
                                    Box(Modifier.fillMaxWidth().weight(1f)) {
                                        ClassTable(
                                            rows = state.classifierRows,
                                            selectedClassifierId = state.selectedClassifierId,
                                            leakClasses = leakClasses,
                                            duplicateClasses = duplicateClasses,
                                            visibleColumns = state.visibleClassifierColumns,
                                            onSelectClassifier = actions.onSelectClassifier,
                                            language = language,
                                            listState = classifierListState,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalScrollbar(
                            adapter = rememberScrollbarAdapter(classifierHorizontalScrollState),
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(end = scrollbarThickness),
                            style = scrollbarStyle,
                        )
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(classifierListState),
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .fillMaxHeight()
                                    .padding(top = CLASSIFIER_HEADER_HEIGHT, bottom = scrollbarThickness),
                            style = scrollbarStyle,
                        )
                    }
                }
                Box(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .width(8.dp)
                            .testTag("memory-profiler-class-list-resizer")
                            .pointerInput(totalWidthPx) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    if (totalWidthPx > 0f) {
                                        leftPaneFraction.value =
                                            (leftPaneFraction.value + dragAmount.x / totalWidthPx)
                                                .coerceIn(MIN_LEFT_PANE_FRACTION, MAX_LEFT_PANE_FRACTION)
                                    }
                                }
                            },
                ) {
                    VerticalDivider(Modifier.align(Alignment.Center))
                }
                Column(Modifier.weight(1f)) {
                    InstanceListTitle(
                        className = state.selectedClassifierLabel ?: state.selectedClassName,
                        language = language,
                    )
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        Column(Modifier.weight(1f)) {
                            InstanceTableHeader(language)
                            InstanceTable(
                                className = state.selectedClassifierLabel ?: state.selectedClassName,
                                instances = state.selectedClassInstances,
                                selectedDetail = state.selectedInstanceDetail,
                                onSelectInstance = actions.onSelectInstance,
                                language = language,
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            )
                        }
                        VerticalDivider()
                        val detail = state.selectedInstanceDetail
                        if (detail != null) {
                            InstanceDetailPane(
                                detail = detail,
                                language = language,
                                modifier = Modifier.weight(1.2f),
                                canNavigateBack = state.instanceHistoryIndex > 0,
                                canNavigateForward = state.instanceHistoryIndex in 0 until (state.instanceHistory.lastIndex),
                                isPinned = detail.objectId in state.pinnedInstanceIds,
                                pinnedInstanceIds = state.pinnedInstanceIds.toList(),
                                onNavigateBack = actions.onNavigateInstanceBack,
                                onNavigateForward = actions.onNavigateInstanceForward,
                                onTogglePinned = { actions.onTogglePinnedInstance(detail.objectId) },
                                onCopyObjectId = actions.onCopyObjectId,
                                onFollowObject = actions.onSelectInstance,
                                onLoadArrayRange = actions.onLoadArrayRange,
                            )
                        } else {
                            EmptyPaneHint(
                                text = localizedStringResource(Res.string.select_an_instance_to_view_details, language),
                                modifier = Modifier.weight(1.2f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterBar(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DropdownSelector(
            items = state.availableHeaps,
            selectedItem = state.heapFilter,
            onItemSelected = { heap -> actions.onHeapFilterChange(heap) },
            itemLabel = { it },
            selectedItemLabel = { it },
            placeholder = localizedStringResource(Res.string.all_heaps, language),
            onPlaceholderSelected = { actions.onHeapFilterChange(null) },
            selectorDescription = localizedStringResource(Res.string.heap, language),
        )
        DropdownSelector(
            items = MemoryClassScope.entries,
            selectedItem = state.classScope,
            onItemSelected = { scope -> actions.onClassScopeChange(scope) },
            itemLabel = { scope -> classScopeLabel(scope, language) },
            selectedItemLabel = { scope -> classScopeLabel(scope, language) },
            placeholder = localizedStringResource(Res.string.all_classes, language),
            selectorDescription = localizedStringResource(Res.string.class_scope, language),
        )
        DropdownSelector(
            items = MemoryLeakFilter.entries,
            selectedItem = state.leakFilter,
            onItemSelected = { filter -> actions.onLeakFilterChange(filter) },
            itemLabel = { filter -> leakFilterLabel(filter, language) },
            selectedItemLabel = { filter -> leakFilterLabel(filter, language) },
            placeholder = localizedStringResource(Res.string.none, language),
            selectorDescription = localizedStringResource(Res.string.filter_by, language),
        )
        DropdownSelector(
            items = state.availableArrangeBy,
            selectedItem = state.arrangeBy,
            onItemSelected = { arrange -> actions.onArrangeByChange(arrange) },
            itemLabel = { arrange -> arrangeByLabel(arrange, language) },
            selectedItemLabel = { arrange -> arrangeByLabel(arrange, language) },
            placeholder = localizedStringResource(Res.string.class_option, language),
            selectorDescription = localizedStringResource(Res.string.arrange_by, language),
        )
        BasicTextField(
            value = state.searchText,
            onValueChange = actions.onSearchChange,
            singleLine = true,
            textStyle =
                TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = ViewerTypography.bodyCompact.fontSize,
                ),
            modifier =
                Modifier
                    .widthIn(min = 160.dp, max = 220.dp)
                    .height(32.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (state.searchText.isEmpty()) {
                        Text(
                            localizedStringResource(Res.string.filter_classes, language),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = ViewerTypography.bodyCompact.fontSize,
                        )
                    }
                    innerTextField()
                }
            },
        )
        FilterCheckbox(
            label = localizedStringResource(Res.string.match_case, language),
            checked = state.matchCase,
            onChecked = actions.onMatchCaseChange,
        )
        FilterCheckbox(
            label = localizedStringResource(Res.string.regex, language),
            checked = state.useRegex,
            onChecked = actions.onUseRegexChange,
        )
        FilterCheckbox(
            label = localizedStringResource(Res.string.native_size, language),
            checked = MemoryClassifierColumn.NATIVE_SIZE in state.visibleClassifierColumns,
            onChecked = { actions.onToggleClassifierColumn(MemoryClassifierColumn.NATIVE_SIZE) },
        )
        FilterCheckbox(
            label = localizedStringResource(Res.string.retained, language),
            checked = MemoryClassifierColumn.RETAINED_SIZE in state.visibleClassifierColumns,
            onChecked = { actions.onToggleClassifierColumn(MemoryClassifierColumn.RETAINED_SIZE) },
        )
        FilterCheckbox(
            label = localizedStringResource(Res.string.shallow, language),
            checked = MemoryClassifierColumn.SHALLOW_SIZE in state.visibleClassifierColumns,
            onChecked = { actions.onToggleClassifierColumn(MemoryClassifierColumn.SHALLOW_SIZE) },
        )
        DropdownSelector(
            items = state.savedFilterPresets,
            selectedItem = state.savedFilterPresets.firstOrNull { it.id == state.activeFilterPresetId },
            onItemSelected = actions.onApplyFilterPreset,
            itemLabel = { it.label },
            selectedItemLabel = { it.label },
            placeholder = localizedStringResource(Res.string.saved_filters, language),
            selectorDescription = localizedStringResource(Res.string.saved_filters, language),
        )
        ProfilerCompactButton(
            text = localizedStringResource(Res.string.save_filter, language),
            onClick = actions.onSaveFilterPreset,
        )
        ProfilerCompactButton(
            text = localizedStringResource(Res.string.delete_filter, language),
            enabled = state.activeFilterPresetId != null,
            onClick = actions.onDeleteFilterPreset,
        )
    }
}

@Composable
private fun FilterCheckbox(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable { onChecked(!checked) },
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChecked,
            modifier = Modifier.size(18.dp),
        )
        Text(label, fontSize = ViewerTypography.bodyCompact.fontSize)
    }
}

@Composable
private fun SummaryBar(
    summary: MemoryClassListSummary,
    language: UiLanguage,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SummaryMetric("${integer(summary.classCount)} ${localizedStringResource(Res.string.classes, language)}")
        SummaryMetric(localizedStringResource(Res.string.leaks_summary, language, integer(summary.leakCount)))
        SummaryMetric(localizedStringResource(Res.string.duplicates_summary, language, integer(summary.duplicateBitmapCount)))
        Spacer(Modifier.weight(1f))
        SummaryMetric("${localizedStringResource(Res.string.count, language)} ${integer(summary.totalCount)}")
        SummaryMetric("${localizedStringResource(Res.string.native_size, language)} ${formatBytes(summary.totalNativeSize)}")
        SummaryMetric("${localizedStringResource(Res.string.shallow, language)} ${formatBytes(summary.totalShallowSize)}")
        SummaryMetric("${localizedStringResource(Res.string.retained, language)} ${formatBytes(summary.totalRetainedSize)}")
    }
}

@Composable
private fun SummaryMetric(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = ViewerTypography.bodyCompact.fontSize)
}

@Composable
private fun ClassTableHeader(
    language: UiLanguage,
    arrangeBy: MemoryArrangeBy,
    sortColumn: MemoryClassifierColumn,
    sortDirection: MemorySortDirection,
    visibleColumns: Set<MemoryClassifierColumn>,
    onSort: (MemoryClassifierColumn) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            headerTitle(firstColumnLabel(arrangeBy, language), MemoryClassifierColumn.NAME, sortColumn, sortDirection),
            Modifier.weight(1f).clickable { onSort(MemoryClassifierColumn.NAME) },
            fontWeight = FontWeight.Bold,
            fontSize = ViewerTypography.bodyCompact.fontSize,
        )
        listOf(
            MemoryClassifierColumn.MODULE_NAME to localizedStringResource(Res.string.module_name, language),
            MemoryClassifierColumn.ALLOCATIONS to localizedStringResource(Res.string.allocations, language),
            MemoryClassifierColumn.DEALLOCATIONS to localizedStringResource(Res.string.deallocations, language),
            MemoryClassifierColumn.TOTAL_COUNT to localizedStringResource(Res.string.total_count, language),
            MemoryClassifierColumn.NATIVE_SIZE to localizedStringResource(Res.string.native_size, language),
            MemoryClassifierColumn.SHALLOW_SIZE to localizedStringResource(Res.string.shallow, language),
            MemoryClassifierColumn.RETAINED_SIZE to localizedStringResource(Res.string.retained, language),
            MemoryClassifierColumn.ALLOCATIONS_SIZE to localizedStringResource(Res.string.allocations_size, language),
            MemoryClassifierColumn.DEALLOCATIONS_SIZE to localizedStringResource(Res.string.deallocations_size, language),
            MemoryClassifierColumn.SHALLOW_SIZE_CHANGE to localizedStringResource(Res.string.shallow_size_change, language),
        ).filter { (column, _) -> column in visibleColumns }.forEach { (column, title) ->
            Text(
                headerTitle(title, column, sortColumn, sortDirection),
                Modifier.width(96.dp).clickable {
                    onSort(column)
                },
                fontWeight = FontWeight.Bold,
                fontSize = ViewerTypography.bodyCompact.fontSize,
            )
        }
    }
}

private fun headerTitle(
    title: String,
    column: MemoryClassifierColumn,
    selected: MemoryClassifierColumn,
    direction: MemorySortDirection,
): String = if (column == selected) "$title ${if (direction == MemorySortDirection.ASCENDING) "↑" else "↓"}" else title

@Composable
private fun ClassTable(
    rows: List<MemoryClassifierRow>,
    selectedClassifierId: String?,
    leakClasses: Set<String>,
    duplicateClasses: Set<String>,
    visibleColumns: Set<MemoryClassifierColumn>,
    onSelectClassifier: (MemoryClassifierRow) -> Unit,
    language: UiLanguage,
    listState: LazyListState,
    modifier: Modifier,
) {
    if (rows.isEmpty()) {
        EmptyPaneHint(localizedStringResource(Res.string.import_or_dump_an_hprof_file_to_show_class_histogram, language), modifier)
    } else {
        val expanded = remember { mutableStateMapOf<String, Boolean>() }
        val visibleRows =
            buildList {
                fun appendRows(nodes: List<MemoryClassifierRow>) {
                    nodes.forEach { row ->
                        add(row)
                        if (row.children.isNotEmpty() && expanded[row.id] != false) appendRows(row.children)
                    }
                }
                appendRows(rows)
            }
        LazyColumn(state = listState, modifier = modifier) {
            itemsIndexed(visibleRows, key = { _, row -> row.id }) { index, row ->
                ClassifierTableRow(
                    row = row,
                    selected = row.id == selectedClassifierId,
                    isLeak = row.className?.let { it in leakClasses } == true,
                    isDuplicateBitmap = row.className?.let { it in duplicateClasses } == true,
                    depth = row.depth,
                    rowIndex = index,
                    visibleColumns = visibleColumns,
                    onSelectClassifier = onSelectClassifier,
                    expanded = expanded[row.id] != false,
                    onToggle = { expanded[row.id] = !(expanded[row.id] ?: true) },
                )
            }
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod")
private fun ClassifierTableRow(
    row: MemoryClassifierRow,
    selected: Boolean,
    isLeak: Boolean,
    isDuplicateBitmap: Boolean,
    depth: Int,
    rowIndex: Int,
    visibleColumns: Set<MemoryClassifierColumn>,
    onSelectClassifier: (MemoryClassifierRow) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val background =
        when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            isLeak -> MaterialTheme.colorScheme.errorContainer.copy(alpha = .45f)
            isDuplicateBitmap -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .45f)
            rowIndex % 2 == 0 -> MaterialTheme.colorScheme.surfaceContainerLow
            else -> MaterialTheme.colorScheme.surface
        }
    Row(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(background, RoundedCornerShape(3.dp))
            .clickable { onSelectClassifier(row) }
            .padding(
                start =
                    (
                        8 +
                            depth * 16
                    ).dp,
                end = 8.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (row.isLeaf) {
                ""
            } else if (expanded) {
                "▾"
            } else {
                "▸"
            },
            Modifier.width(14.dp).clickable(enabled = !row.isLeaf, onClick = onToggle),
            fontSize = ViewerTypography.bodyCompact.fontSize,
        )
        Text(
            row.label,
            Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = ViewerTypography.bodyCompact.fontSize,
        )
        if (MemoryClassifierColumn.MODULE_NAME in visibleColumns) classifierCell(row.moduleName)
        if (MemoryClassifierColumn.ALLOCATIONS in visibleColumns) classifierCell(row.allocations?.let(::integer))
        if (MemoryClassifierColumn.DEALLOCATIONS in visibleColumns) classifierCell(row.deallocations?.let(::integer))
        if (MemoryClassifierColumn.TOTAL_COUNT in visibleColumns) classifierCell(integer(row.totalCount))
        if (MemoryClassifierColumn.NATIVE_SIZE in visibleColumns) classifierCell(row.nativeSize?.let(::formatBytes))
        if (MemoryClassifierColumn.SHALLOW_SIZE in visibleColumns) classifierCell(formatBytes(row.shallowSize))
        if (MemoryClassifierColumn.RETAINED_SIZE in visibleColumns) classifierCell(row.retainedSize?.let(::formatBytes))
        if (MemoryClassifierColumn.ALLOCATIONS_SIZE in visibleColumns) classifierCell(row.allocationsSize?.let(::formatBytes))
        if (MemoryClassifierColumn.DEALLOCATIONS_SIZE in visibleColumns) classifierCell(row.deallocationsSize?.let(::formatBytes))
        if (MemoryClassifierColumn.SHALLOW_SIZE_CHANGE in visibleColumns) classifierCell(row.shallowSizeChange?.let(::formatBytes))
    }
}

@Composable
private fun RowScope.classifierCell(value: String?) {
    Text(
        value ?: "—",
        Modifier.width(96.dp),
        fontSize = ViewerTypography.bodyCompact.fontSize,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun InstanceListTitle(
    className: String?,
    language: UiLanguage,
) {
    Text(
        text =
            if (className != null) {
                "${localizedStringResource(Res.string.instance_list, language)} — $className"
            } else {
                localizedStringResource(Res.string.instance_list, language)
            },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun InstanceTableHeader(language: UiLanguage) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            localizedStringResource(Res.string.instance, language),
            Modifier.weight(1f),
            fontWeight = FontWeight.Bold,
            fontSize = ViewerTypography.bodyCompact.fontSize,
        )
        Text(
            localizedStringResource(Res.string.depth, language),
            Modifier.width(52.dp),
            fontWeight = FontWeight.Bold,
            fontSize = ViewerTypography.bodyCompact.fontSize,
        )
        Text(
            localizedStringResource(Res.string.native_size, language),
            Modifier.width(96.dp),
            fontWeight = FontWeight.Bold,
            fontSize = ViewerTypography.bodyCompact.fontSize,
        )
        Text(
            localizedStringResource(Res.string.shallow, language),
            Modifier.width(88.dp),
            fontWeight = FontWeight.Bold,
            fontSize = ViewerTypography.bodyCompact.fontSize,
        )
        Text(
            localizedStringResource(Res.string.retained, language),
            Modifier.width(112.dp),
            fontWeight = FontWeight.Bold,
            fontSize = ViewerTypography.bodyCompact.fontSize,
        )
    }
}

@Composable
private fun InstanceTable(
    className: String?,
    instances: List<MemoryInstanceRow>,
    selectedDetail: MemoryInstanceDetail?,
    onSelectInstance: (Long) -> Unit,
    language: UiLanguage,
    modifier: Modifier,
) {
    when {
        className == null ->
            EmptyPaneHint(localizedStringResource(Res.string.select_a_class_to_view_its_instances, language), modifier)
        instances.isEmpty() ->
            EmptyPaneHint(localizedStringResource(Res.string.no_instances_for_class, language), modifier)
        else ->
            LazyColumn(modifier) {
                itemsIndexed(instances, key = { _, row -> row.objectId }) { index, row ->
                    val selected = row.objectId == selectedDetail?.objectId
                    InstanceTableRow(
                        row = row,
                        rowIndex = index,
                        selected = selected,
                        chain = selectedDetail?.takeIf { it.objectId == row.objectId }?.referenceChain,
                        onClick = { onSelectInstance(row.objectId) },
                        language = language,
                    )
                }
            }
    }
}

@Composable
private fun InstanceTableRow(
    row: MemoryInstanceRow,
    rowIndex: Int,
    selected: Boolean,
    chain: List<ObjectReference>?,
    onClick: () -> Unit,
    language: UiLanguage,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(
                    when {
                        selected -> MaterialTheme.colorScheme.primaryContainer
                        rowIndex % 2 == 0 -> MaterialTheme.colorScheme.surfaceContainerLow
                        else -> MaterialTheme.colorScheme.surface
                    },
                    RoundedCornerShape(3.dp),
                ).clickable(onClick = onClick)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${if (selected) "▾" else "▸"} #${row.index}",
                modifier = Modifier.weight(1f),
                fontSize = ViewerTypography.bodyCompact.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.depth?.toString() ?: localizedStringResource(Res.string.unavailable, language),
                Modifier.width(52.dp),
                fontSize = ViewerTypography.bodyCompact.fontSize,
            )
            Text(row.nativeSize?.let(::formatBytes) ?: "—", Modifier.width(96.dp), fontSize = ViewerTypography.bodyCompact.fontSize)
            Text(
                if (row.shallowSizeKnown) formatBytes(row.shallowSize) else localizedStringResource(Res.string.unavailable, language),
                Modifier.width(88.dp),
                fontSize = ViewerTypography.bodyCompact.fontSize,
            )
            Text(
                text = row.retainedSize?.let(::formatBytes) ?: localizedStringResource(Res.string.unavailable, language),
                modifier = Modifier.width(112.dp),
                fontSize = ViewerTypography.bodyCompact.fontSize,
            )
        }
        if (selected && !chain.isNullOrEmpty()) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, bottom = 4.dp, top = 2.dp)) {
                chain.forEach { reference ->
                    Text(
                        text =
                            buildString {
                                append("↳ ")
                                append(reference.fieldName)
                                if (reference.targetClassName.isNotBlank()) {
                                    append(" → ")
                                    append(reference.targetClassName)
                                }
                            },
                        fontSize = ViewerTypography.secondary.fontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod")
private fun InstanceDetailPane(
    detail: MemoryInstanceDetail,
    language: UiLanguage,
    modifier: Modifier,
    canNavigateBack: Boolean,
    canNavigateForward: Boolean,
    isPinned: Boolean,
    pinnedInstanceIds: List<Long>,
    onNavigateBack: () -> Unit,
    onNavigateForward: () -> Unit,
    onTogglePinned: () -> Unit,
    onCopyObjectId: (Long) -> Unit,
    onFollowObject: (Long) -> Unit,
    onLoadArrayRange: (Long, Int) -> Unit,
) {
    Column(modifier.padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.back, language),
                enabled = canNavigateBack,
                onClick = onNavigateBack,
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.forward, language),
                enabled = canNavigateForward,
                onClick = onNavigateForward,
            )
            Spacer(Modifier.weight(1f))
            ProfilerCompactButton(
                text = localizedStringResource(if (isPinned) Res.string.unpin_object else Res.string.pin_object, language),
                selected = isPinned,
                onClick = onTogglePinned,
            )
            ProfilerCompactButton(
                text = localizedStringResource(Res.string.copy_object_id, language),
                onClick = { onCopyObjectId(detail.objectId) },
            )
        }
        if (pinnedInstanceIds.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    localizedStringResource(Res.string.pinned_objects, language),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = ViewerTypography.secondary.fontSize,
                )
                pinnedInstanceIds.forEach { objectId ->
                    ProfilerCompactButton(
                        text = "0x${java.lang.Long.toHexString(objectId)}",
                        selected = objectId == detail.objectId,
                        onClick = { onFollowObject(objectId) },
                    )
                }
            }
        }
        Text(
            text = "${localizedStringResource(Res.string.instance_details, language)} — ${detail.className}",
            fontWeight = FontWeight.Bold,
            fontSize = ViewerTypography.body.fontSize,
        )
        Text(
            text = "ID 0x${java.lang.Long.toHexString(detail.objectId)}",
            fontSize = ViewerTypography.secondary.fontSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                detail.depth?.let { localizedStringResource(Res.string.root_reachability, language, integer(it)) }
                    ?: localizedStringResource(Res.string.root_reachability_unavailable, language),
            fontSize = ViewerTypography.secondary.fontSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val unavailable = localizedStringResource(Res.string.unavailable, language)
        val shallowSize = if (detail.shallowSizeKnown) formatBytes(detail.shallowSize) else unavailable
        val retainedSize = detail.retainedSize?.let(::formatBytes) ?: unavailable
        val nativeSize = detail.nativeSize?.let(::formatBytes) ?: unavailable
        Text(
            text =
                "${localizedStringResource(Res.string.shallow, language)} $shallowSize · " +
                    "${localizedStringResource(Res.string.retained, language)} $retainedSize · " +
                    "${localizedStringResource(Res.string.estimated_native_size, language)} $nativeSize",
            fontSize = ViewerTypography.secondary.fontSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.weight(1f)) {
                Text(
                    localizedStringResource(Res.string.fields, language),
                    fontWeight = FontWeight.Bold,
                    fontSize = ViewerTypography.bodyCompact.fontSize,
                )
                if (detail.isArray && detail.elementCount != null && detail.fields.isNotEmpty()) {
                    Text(
                        localizedStringResource(
                            Res.string.array_page_range,
                            language,
                            integer(detail.arrayStart + 1),
                            integer(detail.arrayStart + detail.fields.size),
                            integer(detail.elementCount),
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = ViewerTypography.secondary.fontSize,
                    )
                }
                if (detail.fields.isEmpty()) {
                    Text(
                        text =
                            detail.elementCount?.let {
                                if (it > 0) {
                                    localizedStringResource(Res.string.array_values_unavailable, language)
                                } else {
                                    localizedStringResource(Res.string.array_elements, language, integer(it))
                                }
                            }
                                ?: localizedStringResource(Res.string.no_object_fields, language),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = ViewerTypography.secondary.fontSize,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 2.dp)) {
                        items(detail.fields) { field ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                Text(
                                    text = field.name,
                                    modifier = Modifier.width(110.dp),
                                    fontSize = ViewerTypography.secondary.fontSize,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = field.displayValue,
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .clickable(enabled = field.targetObjectId != null) {
                                                field.targetObjectId?.let(onFollowObject)
                                            },
                                    color =
                                        if (field.targetObjectId != null) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    fontSize = ViewerTypography.secondary.fontSize,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (detail.isArray) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            detail.previousArrayPageStart()?.let { previousStart ->
                                ProfilerCompactButton(
                                    text = localizedStringResource(Res.string.previous_array_page, language),
                                    onClick = { onLoadArrayRange(detail.objectId, previousStart) },
                                )
                            }
                            detail.nextArrayPageStart()?.let { nextStart ->
                                ProfilerCompactButton(
                                    text = localizedStringResource(Res.string.next_array_page, language),
                                    onClick = { onLoadArrayRange(detail.objectId, nextStart) },
                                )
                            }
                        }
                    }
                }
            }
            VerticalDivider(Modifier.padding(horizontal = 6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    localizedStringResource(Res.string.references, language),
                    fontWeight = FontWeight.Bold,
                    fontSize = ViewerTypography.bodyCompact.fontSize,
                )
                if (detail.references.isEmpty()) {
                    Text(
                        text = localizedStringResource(Res.string.no_references, language),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = ViewerTypography.secondary.fontSize,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 2.dp)) {
                        items(detail.references) { reference ->
                            Text(
                                text = "${reference.name} ← ${reference.displayValue}",
                                modifier = Modifier.clickable { reference.targetObjectId?.let(onFollowObject) },
                                fontSize = ViewerTypography.secondary.fontSize,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Text(
                    localizedStringResource(Res.string.reference_chain, language),
                    fontWeight = FontWeight.Bold,
                    fontSize = ViewerTypography.bodyCompact.fontSize,
                )
                detail.referenceChain.forEach { reference ->
                    Text(
                        text = "↳ ${reference.fieldName} → ${reference.targetClassName}",
                        modifier = Modifier.clickable { onFollowObject(reference.targetObjectId) },
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = ViewerTypography.secondary.fontSize,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyPaneHint(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(8.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = ViewerTypography.bodyCompact.fontSize)
    }
}

private fun classScopeLabel(
    scope: MemoryClassScope,
    language: UiLanguage,
): String =
    when (scope) {
        MemoryClassScope.ALL -> localizedStringResource(Res.string.all_classes, language)
        MemoryClassScope.PROJECT -> localizedStringResource(Res.string.project_classes, language)
        MemoryClassScope.SYSTEM -> localizedStringResource(Res.string.system_classes, language)
    }

private fun leakFilterLabel(
    filter: MemoryLeakFilter,
    language: UiLanguage,
): String =
    when (filter) {
        MemoryLeakFilter.NONE -> localizedStringResource(Res.string.none, language)
        MemoryLeakFilter.ALL_ISSUE -> localizedStringResource(Res.string.all_issues, language)
        MemoryLeakFilter.ACTIVITY_FRAGMENT_LEAK -> localizedStringResource(Res.string.activity_fragment_leak, language)
        MemoryLeakFilter.DUPLICATE_BITMAPS -> localizedStringResource(Res.string.duplicate_bitmaps, language)
    }

private fun arrangeByLabel(
    arrangeBy: MemoryArrangeBy,
    language: UiLanguage,
): String =
    when (arrangeBy) {
        MemoryArrangeBy.CLASS -> localizedStringResource(Res.string.class_option, language)
        MemoryArrangeBy.PACKAGE -> localizedStringResource(Res.string.package_option, language)
        MemoryArrangeBy.CALLSTACK -> localizedStringResource(Res.string.callstack_option, language)
        MemoryArrangeBy.ALLOCATION_METHOD -> localizedStringResource(Res.string.allocation_method_option, language)
    }

private fun firstColumnLabel(
    arrangeBy: MemoryArrangeBy,
    language: UiLanguage,
): String =
    when (arrangeBy) {
        MemoryArrangeBy.CLASS -> localizedStringResource(Res.string.class_name, language)
        MemoryArrangeBy.PACKAGE -> localizedStringResource(Res.string.package_name, language)
        MemoryArrangeBy.CALLSTACK -> localizedStringResource(Res.string.callstack_name, language)
        MemoryArrangeBy.ALLOCATION_METHOD -> localizedStringResource(Res.string.allocation_method, language)
    }

private val CLASSIFIER_HEADER_HEIGHT = 24.dp
private val CLASSIFIER_TABLE_WIDTH = 1_580.dp

private const val INITIAL_LEFT_PANE_FRACTION = 0.535f
private const val MIN_LEFT_PANE_FRACTION = 0.25f
private const val MAX_LEFT_PANE_FRACTION = 0.72f
