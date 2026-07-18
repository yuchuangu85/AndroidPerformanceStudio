@file:Suppress("MagicNumber", "MaxLineLength")

package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.storage.MarkerProjectionRow
import com.androidperformancestudio.storage.MarkerProjectionSnapshot
import com.androidperformancestudio.storage.PanelProjection

private enum class MarkerSort { START, DURATION, NAME, THREAD, SCHEMA }

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun MarkerTablePanel(
    state: ReportState,
    projection: PanelProjection<MarkerProjectionSnapshot>,
    actions: ReportActions,
    style: MacOsDeviceTargetStyle,
) {
    var sort by remember { mutableStateOf(MarkerSort.START) }
    var ascending by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().testTag("marker-table-panel")) {
        when (projection) {
            is PanelProjection.Failed -> MarkerPanelMessage("${projection.code}: ${projection.message}", style)
            is PanelProjection.Ready -> {
                if (projection.value.markers.isEmpty()) {
                    MarkerPanelMessage(projection.value.markerMessage(), style)
                } else {
                    MarkerTableHeader(sort, ascending, style) { next ->
                        ascending = if (sort == next) !ascending else true
                        sort = next
                    }
                    val markers = remember(projection.value, sort, ascending) { projection.value.markers.sorted(sort, ascending) }
                    LazyColumn {
                        items(markers, key = { it.id.value }) { marker ->
                            MarkerTableRow(marker, marker.id == state.workspace.selections.markerId, style) {
                                actions.onSelectMarker(marker.id)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun MarkerTableHeader(
    sort: MarkerSort,
    ascending: Boolean,
    style: MacOsDeviceTargetStyle,
    onSort: (MarkerSort) -> Unit,
) {
    Row(Modifier.fillMaxWidth().background(style.toolbar).padding(6.dp)) {
        MarkerSort.entries.forEach { option ->
            val label = option.name.lowercase().replaceFirstChar(Char::uppercase)
            Text(
                "$label${if (sort == option) {
                    if (ascending) {
                        " ↑"
                    } else {
                        " ↓"
                    }
                } else {
                    ""
                }}",
                modifier = Modifier.weight(if (option == MarkerSort.NAME) 1f else 0.7f).clickable { onSort(option) },
                color = style.text,
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
private fun MarkerTableRow(
    marker: MarkerProjectionRow,
    selected: Boolean,
    style: MacOsDeviceTargetStyle,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("marker-row-${marker.id.value}")
            .background(if (selected) style.accent.copy(alpha = 0.18f) else style.panel)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(marker.name, Modifier.weight(1f), color = style.text, fontSize = 10.sp)
        Text(marker.startNanos.toString(), Modifier.width(110.dp), color = style.text, fontSize = 9.sp)
        Text((marker.endNanosExclusive - marker.startNanos).toString(), Modifier.width(90.dp), color = style.text, fontSize = 9.sp)
        Text(marker.threadName ?: "Global", Modifier.width(110.dp), color = style.text, fontSize = 9.sp)
        Text(marker.schema, Modifier.width(100.dp), color = style.text, fontSize = 9.sp)
    }
}

private fun List<MarkerProjectionRow>.sorted(
    sort: MarkerSort,
    ascending: Boolean,
): List<MarkerProjectionRow> {
    val comparator =
        when (sort) {
            MarkerSort.START -> compareBy(MarkerProjectionRow::startNanos)
            MarkerSort.DURATION -> compareBy { it.endNanosExclusive - it.startNanos }
            MarkerSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER, MarkerProjectionRow::name)
            MarkerSort.THREAD -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.threadName ?: "" }
            MarkerSort.SCHEMA -> compareBy(String.CASE_INSENSITIVE_ORDER, MarkerProjectionRow::schema)
        }
    return if (ascending) sortedWith(comparator) else sortedWith(comparator.reversed())
}
