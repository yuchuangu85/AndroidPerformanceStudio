@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber", "ktlint:standard:function-naming")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.fields
import com.androidperformancestudio.memory.presentation.generated.resources.instance_details
import com.androidperformancestudio.memory.presentation.generated.resources.memory_inspector_close
import com.androidperformancestudio.memory.presentation.generated.resources.references
import com.androidperformancestudio.memory.presentation.generated.resources.show_nearest_gc_root_only
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource

@Composable
internal fun MemoryToolButton(
    symbol: String,
    label: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    TooltipArea(tooltip = {
        Surface(shadowElevation = 4.dp) { Text(label, Modifier.padding(8.dp), style = ViewerTypography.secondary) }
    }) {
        Box(
            Modifier
                .size(28.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                ).semantics { contentDescription = label }
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(symbol, color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .35f))
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod")
internal fun MemoryObjectInspector(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val detail = state.selectedInstanceDetail ?: return
    if (!hasInspectorContent(detail)) return
    val snapshotKey = state.activeSnapshotId ?: state.snapshotSummary?.id
    val tabs =
        buildList {
            if (hasInspectorFields(detail)) add(false)
            if (detail.references.isNotEmpty()) add(true)
        }
    var references by remember(snapshotKey, detail.objectId) { mutableStateOf(tabs.first()) }
    val referenceTab = if (references in tabs) references else tabs.first()
    var nearestOnly by remember(snapshotKey) { mutableStateOf(false) }
    var autoExpand by remember(snapshotKey, detail.objectId) { mutableStateOf(nearestOnly) }
    var rootExpanded by remember(snapshotKey, detail.objectId) { mutableStateOf(true) }
    var selectedKey by remember(snapshotKey, detail.objectId, referenceTab) { mutableStateOf<String?>(null) }
    var sort by remember(snapshotKey, referenceTab) {
        mutableStateOf(
            if (referenceTab) {
                MemoryInspectorSort(MemoryInspectorColumn.DEPTH, false)
            } else {
                MemoryInspectorSort(MemoryInspectorColumn.RETAINED_SIZE, true)
            },
        )
    }
    val expanded = remember(snapshotKey, detail.objectId) { mutableStateMapOf<String, Boolean>() }
    val visibleCounts = remember(snapshotKey, detail.objectId) { mutableStateMapOf<String, Int>() }
    val entries =
        MemoryInspectorTreeBuilder(
            detail,
            referenceTab,
            nearestOnly,
            expanded.filterValues { it }.keys,
            visibleCounts.toMap(),
            state.inspectorObjects,
            sort,
            rootExpanded,
        ).build()
    // Studio expands and selects the first decreasing-depth reference path when nearest mode is enabled.
    LaunchedEffect(referenceTab, autoExpand, nearestOnly, entries) {
        if (referenceTab && autoExpand && nearestOnly) {
            val step = inspectorNearestStep(entries)
            val candidate = step.expand
            if (candidate == null) {
                if (!step.waiting) {
                    selectedKey = step.selectedKey
                    autoExpand = false
                }
            } else {
                expanded[candidate.key] = true
                candidate.loadKey?.let { actions.onLoadInspectorObject(it.objectId, it.arrayStart) }
            }
        }
    }
    Column(modifier.testTag("memory-object-inspector")) {
        Row(
            Modifier.fillMaxWidth().height(28.dp).testTag("memory-inspector-title"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val title = localizedStringResource(Res.string.instance_details, language) + " - " + detail.inspectorLabel()
            TooltipArea(modifier = Modifier.weight(1f), tooltip = {
                Surface(shadowElevation = 4.dp) {
                    Text(title, Modifier.widthIn(max = 600.dp).padding(8.dp), style = ViewerTypography.secondary)
                }
            }) {
                Text(
                    title,
                    Modifier.fillMaxWidth().padding(start = 4.dp).testTag("memory-inspector-title-text"),
                    style = ViewerTypography.bodyCompact,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier
                    .size(28.dp)
                    .testTag("memory-inspector-close")
                    .semantics {
                        contentDescription = localizedStringResource(Res.string.memory_inspector_close, language)
                    }.clickable(role = Role.Button, onClick = actions.onClearInstanceSelection),
                contentAlignment = Alignment.Center,
            ) {
                Text("\u00d7", style = ViewerTypography.bodyCompact, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .horizontalScroll(rememberScrollState())
                .selectableGroup()
                .testTag("memory-instance-detail-tabs"),
        ) {
            tabs.forEach { isReference ->
                Column(
                    Modifier
                        .selectable(selected = referenceTab == isReference, role = Role.Tab) {
                            references = isReference
                        }.padding(horizontal = 10.dp),
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            localizedStringResource(
                                if (isReference) Res.string.references else Res.string.fields,
                                language,
                            ),
                            style = ViewerTypography.bodyCompact,
                            color =
                                if (referenceTab == isReference) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                    Box(
                        Modifier.fillMaxWidth().height(2.dp).background(
                            if (referenceTab == isReference) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ),
                    )
                }
            }
        }
        HorizontalDivider()
        if (referenceTab) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .testTag("memory-show-nearest-gc-root-only")
                    .toggleable(value = nearestOnly, role = Role.Checkbox) {
                        nearestOnly = it
                        rootExpanded = true
                        autoExpand = it
                        expanded.keys
                            .filter { key -> key.startsWith("references") }
                            .forEach { key -> expanded.remove(key) }
                    }.padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = nearestOnly, onCheckedChange = null, modifier = Modifier.size(20.dp))
                Text(
                    localizedStringResource(Res.string.show_nearest_gc_root_only, language),
                    style = ViewerTypography.bodyCompact,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        MemoryInspectorTable(
            state,
            actions,
            language,
            referenceTab,
            entries,
            sort,
            selectedKey,
            onSelect = { value ->
                selectedKey = value.key
                autoExpand = false
                if (value.root) {
                    rootExpanded = !rootExpanded
                } else if (value.expandable) {
                    val open = expanded[value.key] != true
                    expanded[value.key] = open
                    value.loadKey
                        ?.takeIf { open && state.inspectorObjects[it] !is MemoryInspectorObjectState.Loaded }
                        ?.let { actions.onLoadInspectorObject(it.objectId, it.arrayStart) }
                }
            },
            onSort = { column ->
                sort =
                    MemoryInspectorSort(
                        column,
                        if (sort.column == column) {
                            !sort.descending
                        } else {
                            column != MemoryInspectorColumn.LABEL && column != MemoryInspectorColumn.DEPTH
                        },
                    )
            },
            onMore = { more ->
                visibleCounts[more.parentKey] = (visibleCounts[more.parentKey] ?: INSPECTOR_FIELD_BATCH) + more.count
                more.loadKey?.let { actions.onLoadInspectorObject(it.objectId, it.arrayStart) }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}
