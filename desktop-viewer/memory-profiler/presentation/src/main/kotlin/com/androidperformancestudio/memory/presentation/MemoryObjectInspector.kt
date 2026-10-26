@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber", "LongParameterList", "ktlint:standard:function-naming")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.androidperformancestudio.memory.model.HeapCapability
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.array_page_range
import com.androidperformancestudio.memory.presentation.generated.resources.array_values_unavailable
import com.androidperformancestudio.memory.presentation.generated.resources.back
import com.androidperformancestudio.memory.presentation.generated.resources.copy_object_id
import com.androidperformancestudio.memory.presentation.generated.resources.estimated_native_size
import com.androidperformancestudio.memory.presentation.generated.resources.fields
import com.androidperformancestudio.memory.presentation.generated.resources.forward
import com.androidperformancestudio.memory.presentation.generated.resources.instance
import com.androidperformancestudio.memory.presentation.generated.resources.instance_details
import com.androidperformancestudio.memory.presentation.generated.resources.memory_depth
import com.androidperformancestudio.memory.presentation.generated.resources.memory_not_reachable
import com.androidperformancestudio.memory.presentation.generated.resources.memory_reachable
import com.androidperformancestudio.memory.presentation.generated.resources.memory_reference_column
import com.androidperformancestudio.memory.presentation.generated.resources.memory_root_unavailable
import com.androidperformancestudio.memory.presentation.generated.resources.next_array_page
import com.androidperformancestudio.memory.presentation.generated.resources.no_object_fields
import com.androidperformancestudio.memory.presentation.generated.resources.no_references
import com.androidperformancestudio.memory.presentation.generated.resources.pin_object
import com.androidperformancestudio.memory.presentation.generated.resources.pinned_objects
import com.androidperformancestudio.memory.presentation.generated.resources.previous_array_page
import com.androidperformancestudio.memory.presentation.generated.resources.reference_chain
import com.androidperformancestudio.memory.presentation.generated.resources.references
import com.androidperformancestudio.memory.presentation.generated.resources.retained
import com.androidperformancestudio.memory.presentation.generated.resources.select_an_instance_to_view_details
import com.androidperformancestudio.memory.presentation.generated.resources.shallow
import com.androidperformancestudio.memory.presentation.generated.resources.show_nearest_gc_root_only
import com.androidperformancestudio.memory.presentation.generated.resources.unavailable
import com.androidperformancestudio.memory.presentation.generated.resources.unpin_object
import com.androidperformancestudio.ui.ProfilerCompactButton
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
            Text(
                symbol,
                color =
                    if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(
                            alpha = 0.35f,
                        )
                    },
            )
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
    val unavailable = localizedStringResource(Res.string.unavailable, language)
    val detail = state.selectedInstanceDetail
    val snapshotKey = state.activeSnapshotId ?: state.snapshotSummary?.id
    var tab by remember(snapshotKey) { mutableStateOf(0) }
    var showNearestGcRootOnly by remember(snapshotKey) { mutableStateOf(false) }
    Column(modifier.testTag("memory-object-inspector")) {
        Text(
            localizedStringResource(Res.string.instance_details, language),
            Modifier.padding(10.dp),
            fontWeight = FontWeight.SemiBold,
        )
        HorizontalDivider()
        state.snapshotSummary?.sourceFile?.fileName?.toString()?.let { source ->
            Text(
                source,
                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = ViewerTypography.secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (detail == null) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    localizedStringResource(Res.string.select_an_instance_to_view_details, language),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MemoryToolButton(
                "\u2190",
                localizedStringResource(Res.string.back, language),
                state.instanceHistoryIndex > 0,
                onClick = actions.onNavigateInstanceBack,
            )
            MemoryToolButton(
                "\u2192",
                localizedStringResource(Res.string.forward, language),
                state.instanceHistoryIndex in 0 until state.instanceHistory.lastIndex,
                onClick = actions.onNavigateInstanceForward,
            )
            Spacer(Modifier.weight(1f))
            val pinned = detail.objectId in state.pinnedInstanceIds
            ProfilerCompactButton(
                localizedStringResource(if (pinned) Res.string.unpin_object else Res.string.pin_object, language),
                { actions.onTogglePinnedInstance(detail.objectId) },
                selected = pinned,
            )
            MemoryToolButton(
                "\u2398",
                localizedStringResource(Res.string.copy_object_id, language),
            ) { actions.onCopyObjectId(detail.objectId) }
        }
        if (state.pinnedInstanceIds.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.pinnedInstanceIds.forEach { id ->
                    ProfilerCompactButton(
                        "0x${java.lang.Long.toHexString(id)}",
                        { actions.onSelectInstance(id) },
                        selected =
                            id == detail.objectId,
                        modifier =
                            Modifier.semantics {
                                contentDescription =
                                    localizedStringResource(Res.string.pinned_objects, language) +
                                    " 0x${java.lang.Long.toHexString(id)}"
                            },
                    )
                }
            }
        }
        SelectionContainer {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (detail.objectId in MemoryProfilerPresenter.leakObjectIds(state)) {
                        MemoryLeakWarningIcon(language, Modifier.testTag("memory-leak-inspector-warning"))
                    }
                    Text(
                        detail.className,
                        Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                        style = ViewerTypography.bodyCompact,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "ID 0x${java.lang.Long.toHexString(detail.objectId)}",
                    color = MaterialTheme.colorScheme.primary,
                    style = ViewerTypography.secondary,
                )
                val reachability =
                    when {
                        detail.depth != null -> localizedStringResource(Res.string.memory_reachable, language)
                        HeapCapability.GC_ROOTS in state.snapshotSummary?.capabilities.orEmpty() ->
                            localizedStringResource(
                                Res.string.memory_not_reachable,
                                language,
                            )
                        else -> unavailable
                    }
                Text(
                    reachability,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = ViewerTypography.secondary,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    InspectorMetric(
                        localizedStringResource(Res.string.shallow, language),
                        detail.shallowSize.takeIf { detail.shallowSizeKnown }?.let(::formatBytes) ?: unavailable,
                    )
                    InspectorMetric(
                        localizedStringResource(Res.string.retained, language),
                        detail.retainedSize?.let(::formatBytes) ?: unavailable,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    InspectorMetric(
                        localizedStringResource(Res.string.estimated_native_size, language),
                        detail.nativeSize?.let(::formatBytes) ?: unavailable,
                    )
                    InspectorMetric(
                        localizedStringResource(Res.string.memory_depth, language),
                        detail.depth?.toString() ?: unavailable,
                    )
                }
            }
        }
        HorizontalDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .selectableGroup()
                .testTag("memory-instance-detail-tabs"),
        ) {
            listOf(Res.string.fields, Res.string.references).forEachIndexed {
                index,
                label,
                ->
                Column(
                    Modifier
                        .selectable(selected = tab == index, role = Role.Tab) { tab = index }
                        .padding(horizontal = 8.dp),
                ) {
                    Text(
                        localizedStringResource(label, language),
                        Modifier.padding(vertical = 10.dp),
                        style = ViewerTypography.secondary,
                        color =
                            if (tab ==
                                index
                            ) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    Box(
                        Modifier.fillMaxWidth().height(2.dp).background(
                            if (tab ==
                                index
                            ) {
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
        if (tab == 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag("memory-show-nearest-gc-root-only")
                    .toggleable(value = showNearestGcRootOnly, role = Role.Checkbox) { showNearestGcRootOnly = it }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = showNearestGcRootOnly,
                    onCheckedChange = null,
                )
                Text(
                    localizedStringResource(Res.string.show_nearest_gc_root_only, language),
                    style = ViewerTypography.secondary,
                )
            }
        }
        val showRootPath = tab == 1 && showNearestGcRootOnly
        val rows =
            remember(detail, tab, showNearestGcRootOnly) {
                when {
                    tab == 0 -> detail.fields
                    !showNearestGcRootOnly -> detail.references
                    detail.depth == null -> emptyList()
                    else -> detail.references.filter { reference -> reference.depth?.let { it < detail.depth } == true }
                }
            }
        Text(
            localizedStringResource(
                if (tab == 0) Res.string.instance else Res.string.memory_reference_column,
                language,
            ),
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            fontWeight = FontWeight.SemiBold,
            style = ViewerTypography.secondary,
        )
        HorizontalDivider()
        val contentListState = key(snapshotKey, detail.objectId, tab, showNearestGcRootOnly) { rememberLazyListState() }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                state = contentListState,
                modifier = Modifier.fillMaxSize().padding(end = 10.dp).testTag("memory-inspector-content"),
            ) {
                if (rows.isEmpty() && !showRootPath) {
                    item {
                        val emptyLabel =
                            when {
                                tab == 1 -> Res.string.no_references
                                detail.isArray && (detail.elementCount ?: 0) > 0 -> Res.string.array_values_unavailable
                                else -> Res.string.no_object_fields
                            }
                        Text(
                            localizedStringResource(emptyLabel, language),
                            Modifier.padding(10.dp),
                            style = ViewerTypography.secondary,
                        )
                    }
                }
                itemsIndexed(rows) { _, field ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = field.targetObjectId != null) {
                                field.targetObjectId?.let(actions.onSelectInstance)
                            }.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            field.name,
                            Modifier.weight(0.4f),
                            style = ViewerTypography.secondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            field.displayValue,
                            Modifier.weight(0.6f),
                            style = ViewerTypography.bodyCompact,
                            color =
                                if (field.targetObjectId != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HorizontalDivider()
                }
                if (showRootPath) {
                    item {
                        Text(
                            localizedStringResource(Res.string.reference_chain, language),
                            Modifier.padding(10.dp),
                            fontWeight = FontWeight.SemiBold,
                            style = ViewerTypography.secondary,
                        )
                    }
                    if (detail.referenceChain.isEmpty()) {
                        item {
                            Text(
                                localizedStringResource(Res.string.memory_root_unavailable, language),
                                Modifier.padding(10.dp),
                                style = ViewerTypography.secondary,
                            )
                        }
                    }
                    itemsIndexed(detail.referenceChain) { index, reference ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    actions.onSelectInstance(
                                        reference.targetObjectId,
                                    )
                                }.padding(10.dp),
                        ) {
                            Text(
                                "${index + 1}. ${reference.targetClassName}",
                                color = MaterialTheme.colorScheme.primary,
                                style = ViewerTypography.bodyCompact,
                            )
                            Text(
                                "${reference.fieldName}  0x${java.lang.Long.toHexString(reference.targetObjectId)}",
                                style = ViewerTypography.secondary,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
            VerticalScrollbar(
                rememberScrollbarAdapter(contentListState),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
        if (tab == 0 && detail.isArray && detail.fields.isNotEmpty()) {
            Text(
                localizedStringResource(
                    Res.string.array_page_range,
                    language,
                    integer(detail.arrayStart + 1),
                    integer(detail.arrayStart + detail.fields.size),
                    detail.elementCount?.let(::integer) ?: unavailable,
                ),
                Modifier.padding(horizontal = 10.dp),
                style = ViewerTypography.secondary,
            )
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MemoryToolButton(
                    "\u2190",
                    localizedStringResource(Res.string.previous_array_page, language),
                    detail.previousArrayPageStart() != null,
                ) {
                    detail.previousArrayPageStart()?.let { actions.onLoadArrayRange(detail.objectId, it) }
                }
                MemoryToolButton(
                    "\u2192",
                    localizedStringResource(Res.string.next_array_page, language),
                    detail.nextArrayPageStart() != null,
                ) {
                    detail.nextArrayPageStart()?.let { actions.onLoadArrayRange(detail.objectId, it) }
                }
            }
        }
    }
}

@Composable
private fun InspectorMetric(
    label: String,
    value: String,
) {
    Column {
        Text(label, style = ViewerTypography.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = ViewerTypography.bodyCompact)
    }
}
