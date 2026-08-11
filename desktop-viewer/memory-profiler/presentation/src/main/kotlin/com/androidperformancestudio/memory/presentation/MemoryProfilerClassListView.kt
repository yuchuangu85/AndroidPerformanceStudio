@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "MaxLineLength",
    "TooManyFunctions",
    "ktlint:standard:function-naming",
)

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.androidperformancestudio.memory.presentation.generated.resources.callstack_name
import com.androidperformancestudio.memory.presentation.generated.resources.callstack_option
import com.androidperformancestudio.memory.presentation.generated.resources.class_list
import com.androidperformancestudio.memory.presentation.generated.resources.class_name
import com.androidperformancestudio.memory.presentation.generated.resources.class_option
import com.androidperformancestudio.memory.presentation.generated.resources.class_scope
import com.androidperformancestudio.memory.presentation.generated.resources.classes
import com.androidperformancestudio.memory.presentation.generated.resources.count
import com.androidperformancestudio.memory.presentation.generated.resources.dashboard
import com.androidperformancestudio.memory.presentation.generated.resources.deallocations
import com.androidperformancestudio.memory.presentation.generated.resources.deallocations_size
import com.androidperformancestudio.memory.presentation.generated.resources.depth
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
import com.androidperformancestudio.memory.presentation.generated.resources.leaks_summary
import com.androidperformancestudio.memory.presentation.generated.resources.match_case
import com.androidperformancestudio.memory.presentation.generated.resources.module_name
import com.androidperformancestudio.memory.presentation.generated.resources.native_size
import com.androidperformancestudio.memory.presentation.generated.resources.no_instances_for_class
import com.androidperformancestudio.memory.presentation.generated.resources.no_references
import com.androidperformancestudio.memory.presentation.generated.resources.none
import com.androidperformancestudio.memory.presentation.generated.resources.package_name
import com.androidperformancestudio.memory.presentation.generated.resources.package_option
import com.androidperformancestudio.memory.presentation.generated.resources.project_classes
import com.androidperformancestudio.memory.presentation.generated.resources.reference_chain
import com.androidperformancestudio.memory.presentation.generated.resources.references
import com.androidperformancestudio.memory.presentation.generated.resources.regex
import com.androidperformancestudio.memory.presentation.generated.resources.retained
import com.androidperformancestudio.memory.presentation.generated.resources.select_a_class_to_view_its_instances
import com.androidperformancestudio.memory.presentation.generated.resources.select_an_instance_to_view_details
import com.androidperformancestudio.memory.presentation.generated.resources.shallow
import com.androidperformancestudio.memory.presentation.generated.resources.shallow_size_change
import com.androidperformancestudio.memory.presentation.generated.resources.system_classes
import com.androidperformancestudio.memory.presentation.generated.resources.total_count
import com.androidperformancestudio.memory.presentation.generated.resources.unreachable
import com.androidperformancestudio.ui.DropdownSelector
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

/**
 * Android Studio-style heap class list, rendered as a stacked layout:
 * filter bar → summary → class table → instance list of the selected class.
 */
@Composable
public fun MemoryProfilerClassListPane(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val leakClasses = MemoryProfilerPresenter.leakClassNames(state)
    val duplicateClasses = MemoryProfilerPresenter.duplicateBitmapClasses(state)
    Column(modifier.fillMaxSize()) {
        FilterBar(state, actions, language)
        HorizontalDivider()
        SummaryBar(summary = state.classListSummary, language = language)
        HorizontalDivider()
        Box(Modifier.fillMaxWidth().weight(1.5f).horizontalScroll(rememberScrollState())) {
            Column(Modifier.requiredWidth(CLASSIFIER_TABLE_WIDTH).fillMaxSize()) {
                ClassTableHeader(
                    language,
                    state.arrangeBy,
                    state.classifierSortColumn,
                    state.classifierSortDirection,
                    actions.onClassifierSort,
                )
                ClassTable(
                    rows = state.classifierRows,
                    selectedClassifierId = state.selectedClassifierId,
                    leakClasses = leakClasses,
                    duplicateClasses = duplicateClasses,
                    onSelectClassifier = actions.onSelectClassifier,
                    language = language,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
        HorizontalDivider()
        InstanceListTitle(className = state.selectedClassifierLabel ?: state.selectedClassName, language = language)
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
        OutlinedTextField(
            value = state.searchText,
            onValueChange = actions.onSearchChange,
            placeholder = { Text(localizedStringResource(Res.string.filter_classes, language), fontSize = 12.sp) },
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.sp),
            modifier = Modifier.weight(1f).height(36.dp),
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
    }
}

@Composable
private fun FilterCheckbox(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable { onChecked(!checked) },
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChecked,
            modifier = Modifier.size(18.dp),
        )
        Text(label, fontSize = 12.sp)
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
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
}

@Composable
private fun ClassTableHeader(
    language: UiLanguage,
    arrangeBy: MemoryArrangeBy,
    sortColumn: MemoryClassifierColumn,
    sortDirection: MemorySortDirection,
    onSort: (MemoryClassifierColumn) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            headerTitle(firstColumnLabel(arrangeBy, language), MemoryClassifierColumn.NAME, sortColumn, sortDirection),
            Modifier.weight(1f).clickable { onSort(MemoryClassifierColumn.NAME) },
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
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
        ).forEach { (column, title) ->
            Text(
                headerTitle(title, column, sortColumn, sortDirection),
                Modifier.width(96.dp).clickable {
                    onSort(column)
                },
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
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
    onSelectClassifier: (MemoryClassifierRow) -> Unit,
    language: UiLanguage,
    modifier: Modifier,
) {
    if (rows.isEmpty()) {
        EmptyPaneHint(localizedStringResource(Res.string.import_or_dump_an_hprof_file_to_show_class_histogram, language), modifier)
    } else {
        val expanded = remember { mutableStateMapOf<String, Boolean>() }
        LazyColumn(modifier) {
            fun emitRows(nodes: List<MemoryClassifierRow>) {
                nodes.forEach { row ->
                    item(key = row.id) {
                        ClassifierTableRow(
                            row,
                            row.id == selectedClassifierId,
                            row.className?.let { it in leakClasses } == true,
                            row.className?.let { it in duplicateClasses } == true,
                            row.depth,
                            onSelectClassifier,
                            expanded[row.id] != false,
                        ) { expanded[row.id] = !(expanded[row.id] ?: true) }
                    }
                    if (row.children.isNotEmpty() && expanded[row.id] != false) emitRows(row.children)
                }
            }
            emitRows(rows)
        }
    }
}

@Composable
private fun ClassifierTableRow(
    row: MemoryClassifierRow,
    selected: Boolean,
    isLeak: Boolean,
    isDuplicateBitmap: Boolean,
    depth: Int,
    onSelectClassifier: (MemoryClassifierRow) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val background =
        when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            isLeak -> MaterialTheme.colorScheme.errorContainer.copy(alpha = .45f)
            isDuplicateBitmap -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .45f)
            else -> Color.Transparent
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
            fontSize = 12.sp,
        )
        Text(row.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
        classifierCell(row.moduleName)
        classifierCell(row.allocations?.let(::integer))
        classifierCell(row.deallocations?.let(::integer))
        classifierCell(integer(row.totalCount))
        classifierCell(row.nativeSize?.let(::formatBytes))
        classifierCell(formatBytes(row.shallowSize))
        classifierCell(row.retainedSize?.let(::formatBytes))
        classifierCell(row.allocationsSize?.let(::formatBytes))
        classifierCell(row.deallocationsSize?.let(::formatBytes))
        classifierCell(row.shallowSizeChange?.let(::formatBytes))
    }
}

@Composable
private fun RowScope.classifierCell(value: String?) {
    Text(value ?: "—", Modifier.width(96.dp), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Text(localizedStringResource(Res.string.instance, language), Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(localizedStringResource(Res.string.depth, language), Modifier.width(52.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(
            localizedStringResource(Res.string.native_size, language),
            Modifier.width(96.dp),
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
        Text(localizedStringResource(Res.string.shallow, language), Modifier.width(88.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(localizedStringResource(Res.string.retained, language), Modifier.width(112.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                items(instances, key = { it.objectId }) { row ->
                    val selected = row.objectId == selectedDetail?.objectId
                    InstanceTableRow(
                        row = row,
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
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    RoundedCornerShape(3.dp),
                ).clickable(onClick = onClick)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${if (selected) "▾" else "▸"} #${row.index}",
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.depth?.toString() ?: localizedStringResource(Res.string.unreachable, language),
                Modifier.width(52.dp),
                fontSize = 12.sp,
            )
            Text(row.nativeSize?.let(::formatBytes) ?: "—", Modifier.width(96.dp), fontSize = 12.sp)
            Text(formatBytes(row.shallowSize), Modifier.width(88.dp), fontSize = 12.sp)
            Text(
                text = row.retainedSize?.let(::formatBytes) ?: localizedStringResource(Res.string.unreachable, language),
                modifier = Modifier.width(112.dp),
                fontSize = 12.sp,
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
                        fontSize = 11.sp,
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
private fun InstanceDetailPane(
    detail: MemoryInstanceDetail,
    language: UiLanguage,
    modifier: Modifier,
) {
    Column(modifier.padding(8.dp)) {
        Text(
            text = "${localizedStringResource(Res.string.instance_details, language)} — ${detail.className}",
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
        Text(
            text =
                buildString {
                    append("ID 0x${java.lang.Long.toHexString(detail.objectId)}")
                    detail.depth?.let { append(" · ${localizedStringResource(Res.string.depth, language)} $it") }
                    detail.retainedSize?.let { append(" · ${localizedStringResource(Res.string.retained, language)} ${formatBytes(it)}") }
                },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.weight(1f)) {
                Text(localizedStringResource(Res.string.fields, language), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                if (detail.fields.isEmpty()) {
                    Text(
                        text =
                            detail.elementCount?.let { localizedStringResource(Res.string.array_elements, language, integer(it)) }
                                ?: localizedStringResource(Res.string.unreachable, language),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 2.dp)) {
                        items(detail.fields) { field ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                Text(
                                    text = field.name,
                                    modifier = Modifier.width(110.dp),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = field.displayValue,
                                    modifier = Modifier.weight(1f),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            VerticalDivider(Modifier.padding(horizontal = 6.dp))
            Column(Modifier.weight(1f)) {
                Text(localizedStringResource(Res.string.references, language), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                if (detail.references.isEmpty()) {
                    Text(
                        text = localizedStringResource(Res.string.no_references, language),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 2.dp)) {
                        items(detail.references) { reference ->
                            Text(
                                text = "${reference.name} ← ${reference.displayValue}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Text(localizedStringResource(Res.string.reference_chain, language), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                detail.referenceChain.forEach { reference ->
                    Text("↳ ${reference.fieldName} → ${reference.targetClassName}", fontSize = 11.sp, maxLines = 1)
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
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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

private val CLASSIFIER_TABLE_WIDTH = 1_580.dp
