@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "MagicNumber",
    "MaxLineLength",
    "TooManyFunctions",
    "ktlint:standard:function-naming",
)

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapSummary
import java.text.NumberFormat
import java.util.Locale

@Composable
public fun MemoryProfilerScreen(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    modifier: Modifier = Modifier,
) {
    val presentedState = MemoryProfilerPresenter.present(state)
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MemoryToolbar(presentedState, actions)
            ErrorAndWarnings(presentedState, actions)
            Overview(summary = presentedState.summary)
            Histogram(
                classes = presentedState.classes,
                sort = presentedState.sort,
                actions = actions,
            )
            LeakSuspectsPhaseTwo(presentedState)
        }
    }
}

@Composable
private fun MemoryToolbar(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeviceSelector(state, actions)
        ProcessSelector(state, actions)
        Button(
            onClick = actions.onDumpHeap,
            enabled =
                !state.isDumping &&
                    state.selectedDeviceSerial != null &&
                    state.selectedProcessId != null,
        ) {
            Text(if (state.isDumping) "Dumping…" else "Dump Heap")
        }
        Button(
            onClick = actions.onImportHprof,
            enabled = !state.isDumping,
        ) {
            Text(if (state.isDumping) "Working…" else "Import hprof")
        }
    }
}

@Composable
private fun DeviceSelector(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.devices.firstOrNull { it.serial == state.selectedDeviceSerial }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = "Device selector" },
        ) {
            Text(
                text = selected?.name ?: "Select device ▼",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (state.devices.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No devices") },
                    onClick = {},
                    enabled = false,
                )
            }
            state.devices.forEach { device ->
                DropdownMenuItem(
                    text = {
                        Text(if (device.online) device.name else "${device.name} (offline)")
                    },
                    enabled = device.online,
                    onClick = {
                        expanded = false
                        actions.onSelectDevice(device.serial)
                    },
                )
            }
        }
    }
}

@Composable
private fun ProcessSelector(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.processes.firstOrNull { it.pid == state.selectedProcessId }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = "Process selector" },
        ) {
            Text(
                text = selected?.name ?: "Select process ▼",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (state.processes.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No processes") },
                    onClick = {},
                    enabled = false,
                )
            }
            state.processes.forEach { process ->
                DropdownMenuItem(
                    text = { Text("${process.name} (${process.pid})") },
                    onClick = {
                        expanded = false
                        actions.onSelectProcess(process.pid)
                    },
                )
            }
        }
    }
}

@Composable
private fun ErrorAndWarnings(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
) {
    state.operationMessage?.let { message ->
        MessageCard(
            title = "In progress",
            body = message,
            color = Color(0xFFE3F2FD),
            border = Color(0xFF1565C0),
        ) {
            CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
        }
    }
    state.error?.let { error ->
        MessageCard(
            title = error.title,
            body = error.detail,
            color = Color(0xFFFFEBEE),
            border = Color(0xFFB3261E),
        ) {
            Button(onClick = actions.onRetry) { Text(error.retryLabel) }
        }
    }
    state.cleanupWarning?.let { warning ->
        MessageCard(
            title = "Cleanup warning",
            body = warning,
            color = Color(0xFFFFF8E1),
            border = Color(0xFFFFA000),
        )
    }
    state.warning?.let { warning ->
        MessageCard(
            title = "Warning",
            body = warning,
            color = Color(0xFFFFF8E1),
            border = Color(0xFFFFA000),
        )
    }
}

@Composable
private fun MessageCard(
    title: String,
    body: String,
    color: Color,
    border: Color,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(color, RoundedCornerShape(8.dp))
                .border(1.dp, border, RoundedCornerShape(8.dp))
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body)
        }
        trailing?.invoke()
    }
}

@Composable
private fun Overview(summary: HeapSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Overview", fontWeight = FontWeight.Bold)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            MetricCard("Heap Size", formatBytes(summary.shallowSize), Modifier.weight(1f))
            MetricCard("Objects", integer(summary.objectCount), Modifier.weight(1f))
            MetricCard("Classes", integer(summary.classCount), Modifier.weight(1f))
            MetricCard("Activity", "Count: Phase 2 available", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier,
) {
    Column(
        modifier =
            modifier
                .background(
                    MaterialTheme.colorScheme.surfaceContainer,
                    RoundedCornerShape(10.dp),
                ).border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(10.dp),
                ).padding(12.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Histogram(
    classes: List<ClassStats>,
    sort: MemoryHistogramSort,
    actions: MemoryProfilerActions,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(360.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Class histogram", fontWeight = FontWeight.Bold)
        HistogramHeader(sort, actions)
        if (classes.isEmpty()) {
            Text("Import or dump an hprof file to show class histogram data.")
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(classes, key = { it.className }) { stats -> HistogramRow(stats) }
            }
        }
    }
}

@Composable
private fun HistogramHeader(
    sort: MemoryHistogramSort,
    actions: MemoryProfilerActions,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Class Name", Modifier.weight(1f), fontWeight = FontWeight.Bold)
        SortHeader(
            label = "Count",
            headerSort = MemoryHistogramSort.Count,
            currentSort = sort,
            actions = actions,
            modifier = Modifier.width(96.dp),
        )
        SortHeader(
            label = "Shallow",
            headerSort = MemoryHistogramSort.Shallow,
            currentSort = sort,
            actions = actions,
            modifier = Modifier.width(112.dp),
        )
        Text("Retained", Modifier.width(140.dp), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SortHeader(
    label: String,
    headerSort: MemoryHistogramSort,
    currentSort: MemoryHistogramSort,
    actions: MemoryProfilerActions,
    modifier: Modifier,
) {
    Text(
        text = if (currentSort == headerSort) "$label ↓" else label,
        modifier = modifier.clickable { actions.onSortHistogram(headerSort) },
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun HistogramRow(stats: ClassStats) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stats.className,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(integer(stats.instanceCount), Modifier.width(96.dp))
        Text(formatBytes(stats.shallowSize), Modifier.width(112.dp))
        Text(
            text = stats.retainedSize?.let(::formatBytes) ?: "Phase 2 available",
            modifier = Modifier.width(140.dp),
        )
    }
}

@Composable
private fun LeakSuspectsPhaseTwo(state: MemoryProfilerState) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Leak Suspects", fontWeight = FontWeight.Bold)
        if (state.leakSuspects.isEmpty()) {
            Text("Phase 2 available")
        } else {
            state.leakSuspects.forEach { suspect ->
                Text("${suspect.className}: ${suspect.reason}")
            }
        }
    }
}

private fun integer(value: Int): String = NumberFormat.getIntegerInstance(Locale.US).format(value)

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= BYTES_PER_MB -> "%.1f MB".format(Locale.US, bytes.toDouble() / BYTES_PER_MB)
        bytes >= BYTES_PER_KB -> "%.1f KB".format(Locale.US, bytes.toDouble() / BYTES_PER_KB)
        else -> "$bytes B"
    }

private const val BYTES_PER_KB = 1024.0
private const val BYTES_PER_MB = 1024.0 * 1024.0
