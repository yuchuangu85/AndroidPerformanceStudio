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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.memory_filters
import com.androidperformancestudio.memory.presentation.generated.resources.activity_fragment_leak
import com.androidperformancestudio.memory.presentation.generated.resources.all_classes
import com.androidperformancestudio.memory.presentation.generated.resources.all_heaps
import com.androidperformancestudio.memory.presentation.generated.resources.all_issues
import com.androidperformancestudio.memory.presentation.generated.resources.allocation_method
import com.androidperformancestudio.memory.presentation.generated.resources.allocation_method_option
import com.androidperformancestudio.memory.presentation.generated.resources.allocations
import com.androidperformancestudio.memory.presentation.generated.resources.allocations_size
import com.androidperformancestudio.memory.presentation.generated.resources.arrange_by
import com.androidperformancestudio.memory.presentation.generated.resources.callstack_name
import com.androidperformancestudio.memory.presentation.generated.resources.callstack_option
import com.androidperformancestudio.memory.presentation.generated.resources.class_name
import com.androidperformancestudio.memory.presentation.generated.resources.class_option
import com.androidperformancestudio.memory.presentation.generated.resources.class_scope
import com.androidperformancestudio.memory.presentation.generated.resources.classes
import com.androidperformancestudio.memory.presentation.generated.resources.count
import com.androidperformancestudio.memory.presentation.generated.resources.deallocations
import com.androidperformancestudio.memory.presentation.generated.resources.deallocations_size
import com.androidperformancestudio.memory.presentation.generated.resources.delete_filter
import com.androidperformancestudio.memory.presentation.generated.resources.depth
import com.androidperformancestudio.memory.presentation.generated.resources.duplicate_bitmaps
import com.androidperformancestudio.memory.presentation.generated.resources.duplicates_summary
import com.androidperformancestudio.memory.presentation.generated.resources.filter_by
import com.androidperformancestudio.memory.presentation.generated.resources.filter_classes
import com.androidperformancestudio.memory.presentation.generated.resources.heap
import com.androidperformancestudio.memory.presentation.generated.resources.import_or_dump_an_hprof_file_to_show_class_histogram
import com.androidperformancestudio.memory.presentation.generated.resources.instance
import com.androidperformancestudio.memory.presentation.generated.resources.instance_details
import com.androidperformancestudio.memory.presentation.generated.resources.instance_list
import com.androidperformancestudio.memory.presentation.generated.resources.instances
import com.androidperformancestudio.memory.presentation.generated.resources.leaks_summary
import com.androidperformancestudio.memory.presentation.generated.resources.match_case
import com.androidperformancestudio.memory.presentation.generated.resources.module_name
import com.androidperformancestudio.memory.presentation.generated.resources.native_size
import com.androidperformancestudio.memory.presentation.generated.resources.no_instances_for_class
import com.androidperformancestudio.memory.presentation.generated.resources.none
import com.androidperformancestudio.memory.presentation.generated.resources.package_name
import com.androidperformancestudio.memory.presentation.generated.resources.package_option
import com.androidperformancestudio.memory.presentation.generated.resources.project_classes
import com.androidperformancestudio.memory.presentation.generated.resources.regex
import com.androidperformancestudio.memory.presentation.generated.resources.retained
import com.androidperformancestudio.memory.presentation.generated.resources.retained_native_size
import com.androidperformancestudio.memory.presentation.generated.resources.select_a_class_to_view_its_instances
import com.androidperformancestudio.memory.presentation.generated.resources.shallow
import com.androidperformancestudio.memory.presentation.generated.resources.save_filter
import com.androidperformancestudio.memory.presentation.generated.resources.saved_filters
import com.androidperformancestudio.memory.presentation.generated.resources.shallow_size_change
import com.androidperformancestudio.memory.presentation.generated.resources.system_classes
import com.androidperformancestudio.memory.presentation.generated.resources.unavailable
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.awt.Cursor

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
                    val nameViewportRight =
                        memoryTableViewportRight(
                            availableWidth * leftPaneFraction.value - scrollbarThickness, classifierHorizontalScrollState,
                        )
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(end = scrollbarThickness, bottom = scrollbarThickness)
                                .testTag("memory-classifier-viewport"),
                        ) {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .horizontalScroll(classifierHorizontalScrollState),
                            ) {
                                Column(
                                    Modifier.requiredWidth(CLASSIFIER_TABLE_WIDTH).fillMaxHeight()
                                        .memoryTableGrid(classifierColumnWidths(state.visibleClassifierColumns, false))
                                        .testTag("memory-classifier-table"),
                                ) {
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
                                            expandHeapRoots = state.heapFilter != null,
                                            expansionKey = state.activeSnapshotId,
                                            nameViewportRight = nameViewportRight,
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
                        isLeak = state.selectedClassName?.let { it in leakClasses } == true,
                        language = language,
                    )
                    HorizontalDivider()
                    InstanceListContent(state, actions, language, Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun MemoryFiltersPane(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(localizedStringResource(Res.string.memory_filters, language), fontWeight = FontWeight.SemiBold)
        com.androidperformancestudio.ui.ProfilerCompactTextField(
            label = "", value = state.searchText, onValueChange = actions.onSearchChange,
            placeholder = localizedStringResource(Res.string.filter_classes, language), modifier = Modifier.fillMaxWidth(),
        )
        FilterCheckbox(localizedStringResource(Res.string.match_case, language), state.matchCase, actions.onMatchCaseChange)
        FilterCheckbox(localizedStringResource(Res.string.regex, language), state.useRegex, actions.onUseRegexChange)
    }
}

@Composable
internal fun MemoryClassesPane(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val leakClasses = MemoryProfilerPresenter.leakClassNames(state)
    val columns =
        state.visibleClassifierColumns.intersect(
            setOf(
                MemoryClassifierColumn.NATIVE_SIZE,
                MemoryClassifierColumn.SHALLOW_SIZE,
                MemoryClassifierColumn.RETAINED_NATIVE_SIZE,
                MemoryClassifierColumn.RETAINED_SIZE,
            ),
        ) + MemoryClassifierColumn.TOTAL_COUNT
    val scroll = rememberScrollState()
    val listState = rememberLazyListState()
    val showHeapColumn = state.perHeapClasses.isEmpty() && state.heapFilter == null
    val classesFraction = remember { mutableStateOf(INITIAL_CLASS_PANE_FRACTION) }
    BoxWithConstraints(modifier) {
        val paneHeight = (maxHeight - CLASS_INSTANCE_SPLITTER_HEIGHT).coerceAtLeast(0.dp)
        val minimumScale = minOf(1f, paneHeight / 280.dp)
        val minimumClassesHeight = 100.dp * minimumScale
        val minimumInstancesHeight = 180.dp * minimumScale
        val minimumFraction = if (paneHeight > 0.dp) minimumClassesHeight / paneHeight else 0f
        val maximumFraction =
            if (paneHeight > 0.dp) (1f - minimumInstancesHeight / paneHeight).coerceAtLeast(minimumFraction) else 1f
        val fraction = classesFraction.value.coerceIn(minimumFraction, maximumFraction)
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(paneHeight * fraction).testTag("memory-classes-card"),
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val tableWidth = maxOf(maxWidth - 10.dp, (260 + (columns.size + if (showHeapColumn) 1 else 0) * 104).dp)
                    val nameViewportRight = memoryTableViewportRight(maxWidth - 10.dp, scroll)
                    val scrollbarInset = if (tableWidth > maxWidth - 10.dp) LocalScrollbarStyle.current.thickness else 0.dp
                    Box(
                        Modifier.fillMaxSize().padding(end = 10.dp, bottom = scrollbarInset)
                            .testTag("memory-classifier-viewport"),
                    ) {
                        Column(
                            Modifier.horizontalScroll(scroll).requiredWidth(tableWidth).fillMaxHeight()
                                .memoryTableGrid(classifierColumnWidths(columns, showHeapColumn))
                                .testTag("memory-classifier-table"),
                        ) {
                            ClassTableHeader(
                                language, state.arrangeBy, state.classifierSortColumn, state.classifierSortDirection,
                                columns, actions.onClassifierSort, Modifier.height(CLASSIFIER_HEADER_HEIGHT), showHeap = showHeapColumn,
                            )
                            HorizontalDivider()
                            ClassTable(
                                state.classifierRows, state.selectedClassifierId, MemoryProfilerPresenter.leakClassNames(state),
                                MemoryProfilerPresenter.duplicateBitmapClasses(state), columns, actions.onSelectClassifier, language,
                                listState, Modifier.fillMaxWidth().weight(1f), showHeap = showHeapColumn,
                                expandHeapRoots = state.heapFilter != null, expansionKey = state.activeSnapshotId,
                                nameViewportRight = nameViewportRight,
                            )
                        }
                    }
                    if (scrollbarInset > 0.dp) {
                        HorizontalScrollbar(
                            rememberScrollbarAdapter(scroll),
                            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 10.dp)
                                .testTag("memory-classifier-horizontal-scrollbar"),
                        )
                    }
                    VerticalScrollbar(
                        rememberScrollbarAdapter(listState),
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                            .padding(top = CLASSIFIER_HEADER_HEIGHT, bottom = scrollbarInset),
                    )
                }
            }
            ClassInstanceHeightDivider(classesFraction, paneHeight, minimumFraction, maximumFraction, language)
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("memory-instances-card"),
            ) {
                InstanceListTitle(
                    className = state.selectedClassifierLabel ?: state.selectedClassName,
                    isLeak = state.selectedClassName?.let { it in leakClasses } == true,
                    language = language,
                )
                HorizontalDivider()
                InstanceListContent(state, actions, language, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun ClassInstanceHeightDivider(
    classesFraction: MutableState<Float>,
    paneHeight: Dp,
    minimumFraction: Float,
    maximumFraction: Float,
    language: UiLanguage,
) {
    val density = LocalDensity.current
    val paneHeightPx = with(density) { paneHeight.toPx() }
    val fraction = classesFraction.value.coerceIn(minimumFraction, maximumFraction)
    val splitterLabel =
        localizedStringResource(Res.string.classes, language) + " / " +
            localizedStringResource(Res.string.instance_list, language)
    Box(
        Modifier.fillMaxWidth().height(CLASS_INSTANCE_SPLITTER_HEIGHT)
            .testTag("memory-class-instance-divider")
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
            .pointerInput(paneHeightPx) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    if (paneHeightPx > 0f) {
                        classesFraction.value =
                            (classesFraction.value.coerceIn(minimumFraction, maximumFraction) + dragAmount / paneHeightPx)
                                .coerceIn(minimumFraction, maximumFraction)
                    }
                }
            }.semantics {
                contentDescription = splitterLabel
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, minimumFraction..maximumFraction)
                setProgress { target ->
                    classesFraction.value = target.coerceIn(minimumFraction, maximumFraction)
                    true
                }
            }.onKeyEvent { event ->
                val direction =
                    when (event.key) {
                        Key.DirectionUp -> -1
                        Key.DirectionDown -> 1
                        else -> 0
                    }
                if (event.type == KeyEventType.KeyDown && direction != 0 && paneHeightPx > 0f) {
                    val step = with(density) { 16.dp.toPx() } / paneHeightPx
                    val current = classesFraction.value.coerceIn(minimumFraction, maximumFraction)
                    classesFraction.value = (current + direction * step).coerceIn(minimumFraction, maximumFraction)
                    true
                } else {
                    false
                }
            }.focusable(),
        contentAlignment = Alignment.Center,
    ) {
        HorizontalDivider(Modifier.testTag("memory-class-instance-divider-line"))
    }
}

@Composable
internal fun MemoryPrimaryFilterBar(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
) {
    FilterBar(state, actions, language, advancedControls = false)
}

@Composable
private fun FilterBar(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
    advancedControls: Boolean = true,
) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxWidth().testTag("memory-primary-filter-bar")) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scroll)
                    .padding(horizontal = 8.dp),
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
                        .testTag("memory-workspace-search")
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
            if (advancedControls) {
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
                    label = localizedStringResource(Res.string.retained_native_size, language),
                    checked = MemoryClassifierColumn.RETAINED_NATIVE_SIZE in state.visibleClassifierColumns,
                    onChecked = { actions.onToggleClassifierColumn(MemoryClassifierColumn.RETAINED_NATIVE_SIZE) },
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
        HorizontalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.BottomStart).fillMaxWidth())
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
        SummaryMetric(
            "${localizedStringResource(Res.string.native_size, language)} " +
                (summary.totalNativeSize?.let(::formatBytes) ?: localizedStringResource(Res.string.unavailable, language)),
        )
        SummaryMetric("${localizedStringResource(Res.string.shallow, language)} ${formatBytes(summary.totalShallowSize)}")
        SummaryMetric(
            "${localizedStringResource(Res.string.retained, language)} " +
                (summary.totalRetainedSize?.let(::formatBytes) ?: localizedStringResource(Res.string.unavailable, language)),
        )
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
    showHeap: Boolean = false,
) {
    Row(
        modifier =
            modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            headerTitle(firstColumnLabel(arrangeBy, language), MemoryClassifierColumn.NAME, sortColumn, sortDirection),
            Modifier.weight(1f).clickable { onSort(MemoryClassifierColumn.NAME) }.padding(horizontal = 8.dp),
            fontWeight = FontWeight.Bold,
            style = ViewerTypography.bodyCompact,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        listOf(
            MemoryClassifierColumn.MODULE_NAME to localizedStringResource(Res.string.module_name, language),
            MemoryClassifierColumn.ALLOCATIONS to localizedStringResource(Res.string.allocations, language),
            MemoryClassifierColumn.DEALLOCATIONS to localizedStringResource(Res.string.deallocations, language),
            MemoryClassifierColumn.TOTAL_COUNT to localizedStringResource(Res.string.instances, language),
            MemoryClassifierColumn.NATIVE_SIZE to localizedStringResource(Res.string.native_size, language),
            MemoryClassifierColumn.SHALLOW_SIZE to localizedStringResource(Res.string.shallow, language),
            MemoryClassifierColumn.RETAINED_NATIVE_SIZE to localizedStringResource(Res.string.retained_native_size, language),
            MemoryClassifierColumn.RETAINED_SIZE to localizedStringResource(Res.string.retained, language),
            MemoryClassifierColumn.ALLOCATIONS_SIZE to localizedStringResource(Res.string.allocations_size, language),
            MemoryClassifierColumn.DEALLOCATIONS_SIZE to
                localizedStringResource(Res.string.deallocations_size, language),
            MemoryClassifierColumn.SHALLOW_SIZE_CHANGE to
                localizedStringResource(Res.string.shallow_size_change, language),
        ).filter { (column, _) -> column in visibleColumns }.forEach { (column, title) ->
            Text(
                headerTitle(title, column, sortColumn, sortDirection),
                Modifier.width(CLASSIFIER_METRIC_WIDTH).testTag("memory-classifier-header-${column.name}").clickable {
                    onSort(column)
                }.padding(horizontal = 8.dp),
                fontWeight = FontWeight.Bold,
                style = ViewerTypography.bodyCompact,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
        if (showHeap) {
            Text(
                localizedStringResource(Res.string.heap, language), Modifier.width(CLASSIFIER_METRIC_WIDTH).padding(horizontal = 8.dp),
                fontWeight = FontWeight.Bold, style = ViewerTypography.bodyCompact,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
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
    showHeap: Boolean = false,
    expandHeapRoots: Boolean = true,
    expansionKey: String? = null,
    nameViewportRight: Dp = Dp.Infinity,
) {
    if (rows.isEmpty()) {
        EmptyPaneHint(localizedStringResource(Res.string.import_or_dump_an_hprof_file_to_show_class_histogram, language), modifier)
    } else {
        val expanded = remember(expandHeapRoots, expansionKey) { mutableStateMapOf<String, Boolean>() }
        fun isExpanded(row: MemoryClassifierRow): Boolean = expanded[row.id] ?: (row.heapName == null || row.depth != 0 || expandHeapRoots)
        val visibleRows =
            buildList {
                fun appendRows(nodes: List<MemoryClassifierRow>) {
                    nodes.forEach { row ->
                        add(row)
                        if (row.children.isNotEmpty() && isExpanded(row)) appendRows(row.children)
                    }
                }
                appendRows(rows)
            }
        LazyColumn(state = listState, modifier = modifier) {
            itemsIndexed(visibleRows, key = { _, row -> row.id }) { index, row ->
                ClassifierTableRow(
                    row = row,
                    selected = row.id == selectedClassifierId,
                    isLeak = row.containsLeakClass(leakClasses),
                    isDuplicateBitmap = row.className?.let { it in duplicateClasses } == true,
                    depth = row.depth,
                    rowIndex = index,
                    visibleColumns = visibleColumns,
                    onSelectClassifier = onSelectClassifier,
                    expanded = isExpanded(row),
                    onToggle = { expanded[row.id] = !isExpanded(row) },
                    showHeap = showHeap,
                    language = language,
                    nameViewportRight = nameViewportRight,
                )
            }
        }
    }
}

private fun MemoryClassifierRow.containsLeakClass(leakClasses: Set<String>): Boolean =
    className in leakClasses || children.any { it.containsLeakClass(leakClasses) }

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
    showHeap: Boolean = false,
    language: UiLanguage,
    nameViewportRight: Dp = Dp.Infinity,
) {
    val isHeap = row.heapName != null && row.depth == 0
    val background =
        when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            isLeak -> MaterialTheme.colorScheme.errorContainer.copy(alpha = .45f)
            isDuplicateBitmap -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .45f)
            rowIndex % 2 == 0 -> MaterialTheme.colorScheme.surfaceContainerLow
            else -> MaterialTheme.colorScheme.surface
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(memoryTableRowHeight())
                .background(background)
                .testTag("memory-classifier-row-${row.id}")
                .clickable { if (isHeap) onToggle() else onSelectClassifier(row) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemoryTableNameCell(
            isLeak, nameViewportRight, "memory-classifier-leak-warning-${row.id}", language,
            Modifier.weight(1f).fillMaxHeight().testTag("memory-classifier-name-cell-${row.id}"),
        ) {
            Row(
                Modifier.weight(1f).padding(start = (8 + depth * 16).dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (row.children.isEmpty()) {
                        ""
                    } else if (expanded) {
                        "▾"
                    } else {
                        "▸"
                    },
                    Modifier.width(14.dp).testTag("memory-classifier-toggle-${row.id}")
                        .clickable(enabled = row.children.isNotEmpty(), onClick = onToggle),
                    style = ViewerTypography.bodyCompact,
                    fontWeight = if (isHeap) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    row.label,
                    Modifier.weight(1f).testTag("memory-classifier-name-${row.id}"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = ViewerTypography.bodyCompact,
                    fontWeight = if (isHeap) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
        if (MemoryClassifierColumn.MODULE_NAME in visibleColumns) classifierCell(row.moduleName)
        if (MemoryClassifierColumn.ALLOCATIONS in visibleColumns) classifierCell(row.allocations?.let(::integer))
        if (MemoryClassifierColumn.DEALLOCATIONS in visibleColumns) classifierCell(row.deallocations?.let(::integer))
        if (MemoryClassifierColumn.TOTAL_COUNT in visibleColumns) classifierCell(integer(row.totalCount))
        if (MemoryClassifierColumn.NATIVE_SIZE in visibleColumns) classifierCell(row.nativeSize?.let(::formatBytes))
        if (MemoryClassifierColumn.SHALLOW_SIZE in visibleColumns) classifierCell(formatBytes(row.shallowSize))
        if (MemoryClassifierColumn.RETAINED_NATIVE_SIZE in visibleColumns) {
            classifierCell(row.retainedNativeSize?.let(::formatBytes))
        }
        if (MemoryClassifierColumn.RETAINED_SIZE in visibleColumns) classifierCell(row.retainedSize?.let(::formatBytes))
        if (MemoryClassifierColumn.ALLOCATIONS_SIZE in
            visibleColumns
        ) {
            classifierCell(row.allocationsSize?.let(::formatBytes))
        }
        if (MemoryClassifierColumn.DEALLOCATIONS_SIZE in
            visibleColumns
        ) {
            classifierCell(row.deallocationsSize?.let(::formatBytes))
        }
        if (MemoryClassifierColumn.SHALLOW_SIZE_CHANGE in
            visibleColumns
        ) {
            classifierCell(row.shallowSizeChange?.let(::formatBytes))
        }
        if (showHeap) classifierCell(row.heapNames.sorted().joinToString().takeIf { it.isNotBlank() })
    }
}

@Composable
private fun RowScope.classifierCell(value: String?) {
    Text(
        value ?: "—",
        Modifier.width(CLASSIFIER_METRIC_WIDTH).padding(horizontal = 8.dp),
        style = ViewerTypography.bodyCompact,
        textAlign = TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun InstanceListTitle(
    className: String?,
    isLeak: Boolean,
    language: UiLanguage,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text =
                if (className != null) {
                    "${localizedStringResource(Res.string.instance_list, language)} — $className"
                } else {
                    localizedStringResource(Res.string.instance_list, language)
                },
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f, fill = false).testTag("memory-instance-list-title-text"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isLeak) MemoryLeakWarningIcon(language, Modifier.testTag("memory-leak-instance-class-warning"))
    }
}

@Composable
private fun InstanceTableHeader(language: UiLanguage) {
    Row(
        modifier =
            Modifier.fillMaxWidth().height(CLASSIFIER_HEADER_HEIGHT)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            localizedStringResource(Res.string.instance, language),
            Modifier.weight(1f).padding(horizontal = 8.dp),
            fontWeight = FontWeight.Bold,
            style = ViewerTypography.bodyCompact,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            localizedStringResource(Res.string.depth, language),
            Modifier.width(INSTANCE_COLUMN_WIDTHS[0]).padding(horizontal = 8.dp),
            fontWeight = FontWeight.Bold,
            style = ViewerTypography.bodyCompact,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
        Text(
            localizedStringResource(Res.string.native_size, language),
            Modifier.width(INSTANCE_COLUMN_WIDTHS[1]).padding(horizontal = 8.dp),
            fontWeight = FontWeight.Bold,
            style = ViewerTypography.bodyCompact,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
        Text(
            localizedStringResource(Res.string.shallow, language),
            Modifier.width(INSTANCE_COLUMN_WIDTHS[2]).padding(horizontal = 8.dp),
            fontWeight = FontWeight.Bold,
            style = ViewerTypography.bodyCompact,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
        Text(
            localizedStringResource(Res.string.retained, language),
            Modifier.width(INSTANCE_COLUMN_WIDTHS[3]).padding(horizontal = 8.dp),
            fontWeight = FontWeight.Bold,
            style = ViewerTypography.bodyCompact,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun InstanceListContent(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val tableFraction = remember { mutableStateOf(INITIAL_INSTANCE_PANE_FRACTION) }
    val splitterLabel =
        localizedStringResource(Res.string.instance, language) + " / " +
            localizedStringResource(Res.string.instance_details, language)
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        if (!hasInspectorContent(state.selectedInstanceDetail)) {
            Row(Modifier.fillMaxSize().testTag("memory-instance-list-content")) {
                BoxWithConstraints(Modifier.fillMaxSize().testTag("memory-instance-list-table-pane")) {
                    InstanceTableContent(state, actions, language, maxOf(maxWidth, INSTANCE_TABLE_MIN_WIDTH))
                }
            }
            return@BoxWithConstraints
        }
        val paneWidth = (maxWidth - INSTANCE_SPLITTER_WIDTH).coerceAtLeast(0.dp)
        val paneWidthPx = with(density) { paneWidth.toPx() }
        val minimumTableWidth = minOf(280.dp, paneWidth / 2)
        val minimumDetailsWidth = minOf(260.dp, paneWidth / 2)
        val minimumFraction = if (paneWidth > 0.dp) minimumTableWidth / paneWidth else 0f
        val maximumFraction = if (paneWidth > 0.dp) 1f - minimumDetailsWidth / paneWidth else 1f
        val fraction = tableFraction.value.coerceIn(minimumFraction, maximumFraction)
        Row(Modifier.fillMaxSize().testTag("memory-instance-list-content")) {
            BoxWithConstraints(
                Modifier.width(paneWidth * fraction).fillMaxHeight().testTag("memory-instance-list-table-pane"),
            ) {
                InstanceTableContent(state, actions, language, maxOf(maxWidth, INSTANCE_TABLE_MIN_WIDTH))
            }
            Box(
                Modifier.width(INSTANCE_SPLITTER_WIDTH).fillMaxHeight()
                    .testTag("memory-instance-list-resizer")
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                    .pointerInput(paneWidthPx) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            if (paneWidthPx > 0f) {
                                tableFraction.value =
                                    (tableFraction.value.coerceIn(minimumFraction, maximumFraction) + dragAmount / paneWidthPx)
                                        .coerceIn(minimumFraction, maximumFraction)
                            }
                        }
                    }.semantics {
                        contentDescription = splitterLabel
                        progressBarRangeInfo = ProgressBarRangeInfo(fraction, minimumFraction..maximumFraction)
                        setProgress { target ->
                            tableFraction.value = target.coerceIn(minimumFraction, maximumFraction)
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
                            val step = with(density) { 16.dp.toPx() } / paneWidthPx
                            tableFraction.value = (fraction + direction * step).coerceIn(minimumFraction, maximumFraction)
                            true
                        } else {
                            false
                        }
                    }.focusable(),
                contentAlignment = Alignment.Center,
            ) {
                VerticalDivider(Modifier.fillMaxHeight())
            }
            Box(Modifier.weight(1f).fillMaxHeight().testTag("memory-inspector-card")) {
                MemoryObjectInspector(state, actions, language, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun InstanceTableContent(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
    tableWidth: Dp,
) {
    val horizontalScroll = rememberScrollState()
    val listState = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val nameViewportRight = memoryTableViewportRight(maxWidth - 10.dp, horizontalScroll)
        Column(
            Modifier.fillMaxSize().padding(bottom = 10.dp, end = 10.dp)
                .horizontalScroll(horizontalScroll).requiredWidth(tableWidth - 10.dp)
                .memoryTableGrid(INSTANCE_COLUMN_WIDTHS).testTag("memory-instance-table"),
        ) {
            InstanceTableHeader(language)
            InstanceTable(
                className = state.selectedClassifierLabel ?: state.selectedClassName,
                instances = state.selectedClassInstances,
                selectedObjectId = state.selectedInstanceDetail?.objectId,
                onSelectInstance = actions.onSelectInstance,
                language = language,
                modifier = Modifier.fillMaxWidth().weight(1f),
                listState = listState,
                nameViewportRight = nameViewportRight,
            )
        }
        HorizontalScrollbar(
            rememberScrollbarAdapter(horizontalScroll),
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 10.dp),
        )
        VerticalScrollbar(
            rememberScrollbarAdapter(listState),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(top = CLASSIFIER_HEADER_HEIGHT, bottom = 10.dp),
        )
    }
}

@Composable
private fun InstanceTable(
    className: String?,
    instances: List<MemoryInstanceRow>,
    selectedObjectId: Long?,
    onSelectInstance: (Long) -> Unit,
    language: UiLanguage,
    modifier: Modifier,
    listState: LazyListState,
    nameViewportRight: Dp = Dp.Infinity,
) {
    when {
        className == null ->
            EmptyPaneHint(localizedStringResource(Res.string.select_a_class_to_view_its_instances, language), modifier)
        instances.isEmpty() ->
            EmptyPaneHint(localizedStringResource(Res.string.no_instances_for_class, language), modifier)
        else ->
            LazyColumn(modifier, state = listState) {
                itemsIndexed(instances, key = { _, row -> row.objectId }) { index, row ->
                    val selected = row.objectId == selectedObjectId
                    InstanceTableRow(
                        row = row,
                        rowIndex = index,
                        selected = selected,
                        className = row.className,
                        onClick = { onSelectInstance(row.objectId) },
                        language = language,
                        nameViewportRight = nameViewportRight,
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
    className: String?,
    onClick: () -> Unit,
    language: UiLanguage,
    nameViewportRight: Dp = Dp.Infinity,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(memoryTableRowHeight())
                    .background(
                        when {
                            selected -> MaterialTheme.colorScheme.primaryContainer
                            rowIndex % 2 == 0 -> MaterialTheme.colorScheme.surfaceContainerLow
                            else -> MaterialTheme.colorScheme.surface
                        },
                    ).testTag("memory-instance-row-${row.objectId}")
                    .selectable(selected = selected, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MemoryTableNameCell(
                row.isLeakWarning, nameViewportRight, "memory-leak-instance-warning-${row.objectId}", language,
                Modifier.weight(1f).fillMaxHeight().testTag("memory-instance-name-cell-${row.objectId}"),
            ) {
                Text(
                    text = instanceTableLabel(row.objectId, className, row.representedClassName),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("memory-instance-name-${row.objectId}"),
                    style = ViewerTypography.bodyCompact,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = row.depth?.toString() ?: localizedStringResource(Res.string.unavailable, language),
                Modifier.width(INSTANCE_COLUMN_WIDTHS[0]).padding(horizontal = 8.dp),
                style = ViewerTypography.bodyCompact,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.nativeSize?.let(::formatBytes) ?: "—",
                Modifier.width(INSTANCE_COLUMN_WIDTHS[1]).padding(horizontal = 8.dp),
                style = ViewerTypography.bodyCompact, textAlign = TextAlign.End,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (row.shallowSizeKnown) formatBytes(row.shallowSize) else localizedStringResource(Res.string.unavailable, language),
                Modifier.width(INSTANCE_COLUMN_WIDTHS[2]).padding(horizontal = 8.dp),
                style = ViewerTypography.bodyCompact,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.retainedSize?.let(::formatBytes) ?: localizedStringResource(Res.string.unavailable, language),
                modifier = Modifier.width(INSTANCE_COLUMN_WIDTHS[3]).padding(horizontal = 8.dp),
                style = ViewerTypography.bodyCompact,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
private val CLASSIFIER_METRIC_WIDTH = 104.dp
private val CLASSIFIER_TABLE_WIDTH = 1_580.dp
private val INSTANCE_TABLE_MIN_WIDTH = 650.dp
private val INSTANCE_SPLITTER_WIDTH = 7.dp
private val CLASS_INSTANCE_SPLITTER_HEIGHT = 7.dp
private val INSTANCE_COLUMN_WIDTHS = listOf(60.dp, 104.dp, 96.dp, 120.dp)

@Composable
private fun memoryTableRowHeight(): Dp = with(LocalDensity.current) { ViewerTypography.bodyCompact.lineHeight.toDp() } + 4.dp

private fun classifierColumnWidths(
    columns: Set<MemoryClassifierColumn>,
    showHeap: Boolean,
): List<Dp> = List(columns.count { it != MemoryClassifierColumn.NAME } + if (showHeap) 1 else 0) { CLASSIFIER_METRIC_WIDTH }

@Composable
private fun Modifier.memoryTableGrid(columnWidths: List<Dp>): Modifier {
    val color = MaterialTheme.colorScheme.outlineVariant
    return drawWithContent {
        drawContent()
        var x = size.width
        columnWidths.asReversed().forEach { width ->
            x -= width.toPx()
            drawRect(color, topLeft = Offset(kotlin.math.floor(x), 0f), size = Size(1.dp.toPx(), size.height))
        }
    }
}

internal fun instanceTableLabel(
    objectId: Long,
    className: String?,
    representedClassName: String? = null,
): String {
    val identity = "$objectId (0x${java.lang.Long.toHexString(objectId)})"
    representedClassName?.let { return "${it.substringAfterLast('.')} (class)@$identity" }
    return className?.takeIf { it.isNotBlank() }?.substringAfterLast('.')?.let { "$it@$identity" } ?: identity
}

private const val INITIAL_LEFT_PANE_FRACTION = 0.535f
private const val INITIAL_CLASS_PANE_FRACTION = 0.5f
private const val INITIAL_INSTANCE_PANE_FRACTION = 0.62f
private const val MIN_LEFT_PANE_FRACTION = 0.25f
private const val MAX_LEFT_PANE_FRACTION = 0.72f
