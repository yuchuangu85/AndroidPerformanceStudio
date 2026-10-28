@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "MagicNumber", "ktlint:standard:function-naming")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.model.HeapValueKind
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.array_values_unavailable
import com.androidperformancestudio.memory.presentation.generated.resources.depth
import com.androidperformancestudio.memory.presentation.generated.resources.ic_memory_array
import com.androidperformancestudio.memory.presentation.generated.resources.ic_memory_class
import com.androidperformancestudio.memory.presentation.generated.resources.ic_memory_field
import com.androidperformancestudio.memory.presentation.generated.resources.ic_memory_object
import com.androidperformancestudio.memory.presentation.generated.resources.ic_memory_primitive
import com.androidperformancestudio.memory.presentation.generated.resources.instance
import com.androidperformancestudio.memory.presentation.generated.resources.memory_inspector_collapsed
import com.androidperformancestudio.memory.presentation.generated.resources.memory_inspector_cycle
import com.androidperformancestudio.memory.presentation.generated.resources.memory_inspector_depth_limit
import com.androidperformancestudio.memory.presentation.generated.resources.memory_inspector_expanded
import com.androidperformancestudio.memory.presentation.generated.resources.memory_inspector_go_to_instance
import com.androidperformancestudio.memory.presentation.generated.resources.memory_inspector_loading
import com.androidperformancestudio.memory.presentation.generated.resources.memory_inspector_more
import com.androidperformancestudio.memory.presentation.generated.resources.memory_inspector_retained_size
import com.androidperformancestudio.memory.presentation.generated.resources.memory_inspector_shallow_size
import com.androidperformancestudio.memory.presentation.generated.resources.memory_reference_column
import com.androidperformancestudio.memory.presentation.generated.resources.native_size
import com.androidperformancestudio.memory.presentation.generated.resources.no_object_fields
import com.androidperformancestudio.memory.presentation.generated.resources.no_references
import com.androidperformancestudio.memory.presentation.generated.resources.retained_native_size
import com.androidperformancestudio.memory.presentation.generated.resources.unavailable
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui_components.generated.resources.icon_collapse
import com.androidperformancestudio.ui_components.generated.resources.icon_expand
import org.jetbrains.compose.resources.painterResource
import java.awt.Cursor
import com.androidperformancestudio.ui_components.generated.resources.Res as UiRes

@Composable
internal fun MemoryInspectorTable(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
    references: Boolean,
    entries: List<MemoryInspectorTreeEntry>,
    sort: MemoryInspectorSort,
    selectedKey: String?,
    onSelect: (MemoryInspectorTreeEntry.Value) -> Unit,
    onSort: (MemoryInspectorColumn) -> Unit,
    onMore: (MemoryInspectorTreeEntry.More) -> Unit,
    modifier: Modifier = Modifier,
) {
    val columns = inspectorColumns(state)
    val overrides =
        remember(state.activeSnapshotId, references, columns) { mutableStateMapOf<MemoryInspectorColumn, Dp>() }
    val horizontal = rememberScrollState()
    val list = rememberLazyListState()
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val usableWidth = maxOf(maxWidth - 10.dp, (120 + (columns.size - 1) * 40).dp)
        val preferredTotal = columns.sumOf { it.preferredWidth.value.toDouble() }.toFloat()
        val extra = usableWidth - (120 + (columns.size - 1) * 40).dp
        val widths =
            columns.map { column ->
                val minimum = if (column == MemoryInspectorColumn.LABEL) 120.dp else 40.dp
                overrides[column] ?: (minimum + extra * (column.preferredWidth.value / preferredTotal))
            }
        val tableWidth = widths.fold(0.dp, Dp::plus)
        Box(Modifier.fillMaxSize().padding(end = 10.dp, bottom = 10.dp)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontal)
                    .requiredWidth(tableWidth)
                    .inspectorGrid(widths)
                    .testTag("memory-inspector-table"),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .background(LocalViewerColors.current.toolbar)
                        .testTag("memory-inspector-columns"),
                ) {
                    columns.forEachIndexed { index, column ->
                        val title = inspectorColumnTitle(column, references, language)
                        val order = if (sort.descending) " \u2193" else " \u2191"
                        Box(Modifier.width(widths[index]).fillMaxHeight()) {
                            InspectorTooltip(title) {
                                Text(
                                    title + if (sort.column == column) order else "",
                                    Modifier
                                        .fillMaxSize()
                                        .testTag("memory-inspector-header-${column.name}")
                                        .clickable { onSort(column) }
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                    style = ViewerTypography.bodyCompact,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign =
                                        if (column == MemoryInspectorColumn.LABEL) TextAlign.Start else TextAlign.End,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (index < columns.lastIndex) {
                                Box(
                                    Modifier
                                        .align(Alignment.CenterEnd)
                                        .offset(x = 3.dp)
                                        .width(7.dp)
                                        .fillMaxHeight()
                                        .testTag("memory-inspector-column-resizer-${column.name}")
                                        .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                                        .pointerInput(index, usableWidth) {
                                            detectHorizontalDragGestures { change, amount ->
                                                change.consume()
                                                val next = columns[index + 1]
                                                val left = overrides[column] ?: widths[index]
                                                val right = overrides[next] ?: widths[index + 1]
                                                val minimum = if (index == 0) 80.dp else 32.dp
                                                val delta =
                                                    with(density) { amount.toDp() }
                                                        .coerceIn(minimum - left, right - 32.dp)
                                                overrides[column] = left + delta
                                                overrides[next] = right - delta
                                            }
                                        },
                                )
                            }
                        }
                    }
                }
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("memory-inspector-content"),
                ) {
                    items(entries, key = { it.key }) { entry ->
                        when (entry) {
                            is MemoryInspectorTreeEntry.Value ->
                                InspectorValueRow(
                                    entry,
                                    columns,
                                    widths,
                                    references,
                                    selectedKey == entry.key,
                                    language,
                                    onSelect = { onSelect(entry) },
                                    onNavigate = actions.onSelectInstance,
                                )
                            is MemoryInspectorTreeEntry.Message -> InspectorMessage(entry, language)
                            is MemoryInspectorTreeEntry.More -> {
                                val text =
                                    localizedStringResource(Res.string.memory_inspector_more, language, entry.count)
                                Text(
                                    text,
                                    Modifier
                                        .fillMaxWidth()
                                        .height(22.dp)
                                        .testTag(entry.tag)
                                        .clickable { onMore(entry) }
                                        .padding(start = (24 + entry.depth * 12).dp, top = 2.dp),
                                    style = ViewerTypography.bodyCompact,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(list),
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(top = 24.dp, bottom = 10.dp),
        )
        HorizontalScrollbar(
            rememberScrollbarAdapter(horizontal),
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(end = 10.dp),
        )
    }
}

@Composable
private fun InspectorValueRow(
    entry: MemoryInspectorTreeEntry.Value,
    columns: List<MemoryInspectorColumn>,
    widths: List<Dp>,
    references: Boolean,
    selected: Boolean,
    language: UiLanguage,
    onSelect: () -> Unit,
    onNavigate: (Long) -> Unit,
) {
    val label = inspectorFieldLabel(entry.field, references)
    val coloredLabel =
        buildAnnotatedString {
            val preview =
                entry.field.stringPreview?.takeIf { !references && entry.field.valueKind == HeapValueKind.STRING }
            if (preview != null && label.endsWith(preview)) {
                append(label.removeSuffix(preview))
                withStyle(SpanStyle(color = Color(0xFF008000), fontWeight = FontWeight.Bold)) { append(preview) }
            } else {
                append(label)
            }
        }
    val rowHeight = with(LocalDensity.current) { ViewerTypography.bodyCompact.lineHeight.toDp() } + 4.dp
    val target = entry.field.targetObjectId?.takeIf { it != 0L }
    ContextMenuArea(items = {
        if (target == null) {
            emptyList()
        } else {
            listOf(
                ContextMenuItem(localizedStringResource(Res.string.memory_inspector_go_to_instance, language)) {
                    onNavigate(target)
                },
            )
        }
    }) {
        InspectorTooltip(label) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .testTag(entry.tag)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    ).selectable(selected = selected, role = Role.Button, onClick = onSelect)
                    .semantics {
                        if (entry.expandable) {
                            stateDescription =
                                localizedStringResource(
                                    if (entry.expanded) {
                                        Res.string.memory_inspector_expanded
                                    } else {
                                        Res.string.memory_inspector_collapsed
                                    },
                                    language,
                                )
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.width(widths[0]).padding(start = (8 + entry.depth.coerceAtMost(20) * 12).dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (entry.expandable) {
                        Icon(
                            painterResource(
                                if (entry.expanded) UiRes.drawable.icon_expand else UiRes.drawable.icon_collapse,
                            ),
                            null,
                            Modifier.size(12.dp).testTag("${entry.tag}-disclosure"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Box(Modifier.size(12.dp))
                    }
                    InspectorTypeIcon(entry.field.valueKind, entry.root)
                    Text(
                        coloredLabel,
                        Modifier.weight(1f).testTag("${entry.tag}-name"),
                        style = ViewerTypography.bodyCompact,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                columns.drop(1).forEachIndexed { index, column ->
                    val metric = inspectorMetric(entry.field, column)
                    Text(
                        metric?.let(::integer) ?: "-",
                        Modifier
                            .width(widths[index + 1])
                            .padding(horizontal = 6.dp)
                            .testTag("${entry.tag}-${column.name}"),
                        style = ViewerTypography.bodyCompact,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun InspectorTypeIcon(
    kind: HeapValueKind,
    root: Boolean,
) {
    val drawable =
        when (kind) {
            HeapValueKind.PRIMITIVE -> Res.drawable.ic_memory_primitive
            HeapValueKind.ARRAY -> Res.drawable.ic_memory_array
            HeapValueKind.CLASS -> Res.drawable.ic_memory_class
            else -> if (root) Res.drawable.ic_memory_object else Res.drawable.ic_memory_field
        }
    Icon(
        painterResource(drawable),
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = Color.Unspecified,
    )
}

@Composable
private fun InspectorMessage(
    entry: MemoryInspectorTreeEntry.Message,
    language: UiLanguage,
) {
    val key =
        when (entry.kind) {
            MemoryInspectorTreeMessage.LOADING -> Res.string.memory_inspector_loading
            MemoryInspectorTreeMessage.UNAVAILABLE -> Res.string.unavailable
            MemoryInspectorTreeMessage.CYCLE -> Res.string.memory_inspector_cycle
            MemoryInspectorTreeMessage.DEPTH_LIMIT -> Res.string.memory_inspector_depth_limit
            MemoryInspectorTreeMessage.NO_FIELDS -> Res.string.no_object_fields
            MemoryInspectorTreeMessage.NO_REFERENCES -> Res.string.no_references
            MemoryInspectorTreeMessage.ARRAY_UNAVAILABLE -> Res.string.array_values_unavailable
        }
    Text(
        localizedStringResource(key, language),
        Modifier
            .fillMaxWidth()
            .testTag(entry.tag)
            .padding(start = (24 + entry.depth.coerceAtMost(20) * 12).dp, top = 2.dp, bottom = 2.dp),
        style = ViewerTypography.bodyCompact,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun InspectorTooltip(
    text: String,
    content: @Composable () -> Unit,
) {
    TooltipArea(tooltip = {
        Surface(shadowElevation = 4.dp) {
            Text(text, Modifier.widthIn(max = 600.dp).padding(8.dp), style = ViewerTypography.secondary)
        }
    }) { content() }
}

@Composable
private fun Modifier.inspectorGrid(widths: List<Dp>): Modifier {
    val color = MaterialTheme.colorScheme.outlineVariant
    return drawWithContent {
        drawContent()
        var left = 0f
        widths.dropLast(1).forEach { width ->
            left += width.toPx()
            drawRect(color, Offset(kotlin.math.floor(left), 0f), Size(1.dp.toPx(), size.height))
        }
    }
}

private fun inspectorColumnTitle(
    column: MemoryInspectorColumn,
    references: Boolean,
    language: UiLanguage,
): String {
    val key =
        when (column) {
            MemoryInspectorColumn.LABEL -> if (references) Res.string.memory_reference_column else Res.string.instance
            MemoryInspectorColumn.DEPTH -> Res.string.depth
            MemoryInspectorColumn.NATIVE_SIZE -> Res.string.native_size
            MemoryInspectorColumn.SHALLOW_SIZE -> Res.string.memory_inspector_shallow_size
            MemoryInspectorColumn.RETAINED_NATIVE_SIZE -> Res.string.retained_native_size
            MemoryInspectorColumn.RETAINED_SIZE -> Res.string.memory_inspector_retained_size
        }
    return localizedStringResource(key, language)
}
