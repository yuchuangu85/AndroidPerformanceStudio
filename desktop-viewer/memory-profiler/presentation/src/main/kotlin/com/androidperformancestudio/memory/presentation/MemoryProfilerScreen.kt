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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.unit.sp
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
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MemoryToolbar(presentedState, actions)
            ErrorAndWarnings(presentedState, actions)
            Overview(summary = presentedState.summary, activityCount = presentedState.activityCount)
            Histogram(
                classes = presentedState.classes,
                sort = presentedState.sort,
                actions = actions,
                highlightedClassName = presentedState.highlightedClassName,
            )
            LeakSuspectsPhaseTwo(presentedState)
            HeapDiffSection(presentedState.heapDiff)
            BitmapSection(presentedState.bitmapInstances)
        }
    }
}

@Composable
private fun MemoryToolbar(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().height(MEMORY_TOOLBAR_HEIGHT_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                modifier = Modifier.height(MEMORY_TOOLBAR_BUTTON_HEIGHT_DP.dp),
                shape = RoundedCornerShape(MEMORY_TOOLBAR_BUTTON_RADIUS_DP.dp),
                contentPadding = MEMORY_TOOLBAR_BUTTON_CONTENT_PADDING,
            ) {
                Text(if (state.isDumping) "Dumping…" else "Dump Heap", fontSize = 11.sp)
            }
            Button(
                onClick = actions.onImportHprof,
                enabled = !state.isDumping,
                modifier = Modifier.height(MEMORY_TOOLBAR_BUTTON_HEIGHT_DP.dp),
                shape = RoundedCornerShape(MEMORY_TOOLBAR_BUTTON_RADIUS_DP.dp),
                contentPadding = MEMORY_TOOLBAR_BUTTON_CONTENT_PADDING,
            ) {
                Text(if (state.isDumping) "Working…" else "Import hprof", fontSize = 11.sp)
            }
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
            modifier =
                Modifier
                    .height(MEMORY_TOOLBAR_BUTTON_HEIGHT_DP.dp)
                    .semantics { contentDescription = "Device selector" },
            shape = RoundedCornerShape(MEMORY_TOOLBAR_BUTTON_RADIUS_DP.dp),
            contentPadding = MEMORY_TOOLBAR_BUTTON_CONTENT_PADDING,
        ) {
            Text(
                text = selected?.name ?: "Select device ▼",
                fontSize = 11.sp,
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
            modifier =
                Modifier
                    .height(MEMORY_TOOLBAR_BUTTON_HEIGHT_DP.dp)
                    .semantics { contentDescription = "Process selector" },
            shape = RoundedCornerShape(MEMORY_TOOLBAR_BUTTON_RADIUS_DP.dp),
            contentPadding = MEMORY_TOOLBAR_BUTTON_CONTENT_PADDING,
        ) {
            Text(
                text = selected?.name ?: "Select process ▼",
                fontSize = 11.sp,
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

internal const val MEMORY_TOOLBAR_HEIGHT_DP = 29
internal const val MEMORY_TOOLBAR_BUTTON_HEIGHT_DP = 22
private const val MEMORY_TOOLBAR_BUTTON_RADIUS_DP = 7
private val MEMORY_TOOLBAR_BUTTON_CONTENT_PADDING = PaddingValues(horizontal = 8.dp)

@Composable
private fun ErrorAndWarnings(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
) {
    state.operationMessage?.let { message ->
        MessageCard(
            title = "In progress",
            body = message,
            tone = MessageTone.INFO,
        ) {
            CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
        }
    }
    state.error?.let { error ->
        MessageCard(
            title = error.title,
            body = error.detail,
            tone = MessageTone.ERROR,
        ) {
            Button(onClick = actions.onRetry) { Text(error.retryLabel) }
        }
    }
    state.cleanupWarning?.let { warning ->
        MessageCard(
            title = "Cleanup warning",
            body = warning,
            tone = MessageTone.WARNING,
        )
    }
    state.warning?.let { warning ->
        MessageCard(
            title = "Warning",
            body = warning,
            tone = MessageTone.WARNING,
        )
    }
}

@Composable
private fun MessageCard(
    title: String,
    body: String,
    tone: MessageTone,
    trailing: @Composable (() -> Unit)? = null,
) {
    val palette =
        when (tone) {
            MessageTone.INFO ->
                MessageCardPalette(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    MaterialTheme.colorScheme.primary,
                )
            MessageTone.ERROR ->
                MessageCardPalette(
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                    MaterialTheme.colorScheme.error,
                )
            MessageTone.WARNING ->
                MessageCardPalette(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                    MaterialTheme.colorScheme.tertiary,
                )
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(palette.container, RoundedCornerShape(4.dp))
                .border(1.dp, palette.border, RoundedCornerShape(4.dp))
                .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.content, fontWeight = FontWeight.Bold)
            Text(body, color = palette.content)
        }
        trailing?.invoke()
    }
}

private enum class MessageTone { INFO, ERROR, WARNING }

private data class MessageCardPalette(
    val container: Color,
    val content: Color,
    val border: Color,
)

@Composable
private fun Overview(
    summary: HeapSummary,
    activityCount: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Overview", fontWeight = FontWeight.Bold)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            MetricCard("Heap Size", formatBytes(summary.shallowSize), Modifier.weight(1f))
            MetricCard("Objects", integer(summary.objectCount), Modifier.weight(1f))
            MetricCard("Classes", integer(summary.classCount), Modifier.weight(1f))
            MetricCard("Activity", "Count: ${integer(activityCount)}", Modifier.weight(1f))
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
                    RoundedCornerShape(4.dp),
                ).border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(4.dp),
                ).padding(8.dp),
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
    highlightedClassName: String?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp))
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Class histogram", fontWeight = FontWeight.Bold)
        HistogramHeader(sort, actions)
        if (classes.isEmpty()) {
            Text("Import or dump an hprof file to show class histogram data.")
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(classes, key = { it.className }) { stats ->
                    HistogramRow(
                        stats = stats,
                        highlighted = stats.className == highlightedClassName,
                        onClick = { actions.onHighlightClass(stats.className) },
                    )
                }
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
private fun HistogramRow(
    stats: ClassStats,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(
                if (highlighted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                RoundedCornerShape(3.dp),
            ).clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
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
            text = stats.retainedSize?.let(::formatBytes) ?: "Unavailable",
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
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp))
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Leak Suspects", fontWeight = FontWeight.Bold)
        if (state.leakSuspects.isEmpty()) {
            Text("No leak suspects detected.")
        } else {
            state.leakSuspects.forEach { suspect ->
                var expanded by remember(suspect) { mutableStateOf(false) }
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(3.dp),
                            ).clickable { expanded = !expanded }
                            .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "${if (expanded) "▾" else "▸"} ${suspect.className}: ${suspect.reason}",
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "retained ${suspect.retainedSize?.let(::formatBytes) ?: "—"} · " +
                            "confidence ${(suspect.confidence * 100).toInt()}%",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (expanded) {
                        suspect.referenceChain.forEachIndexed { index, reference ->
                            Text(
                                "${"  ".repeat(index)}↳ ${reference.fieldName} → " +
                                    (reference.targetClassName.ifBlank { reference.targetObjectId.toString() }),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeapDiffSection(diff: com.androidperformancestudio.memory.model.HeapDiff?) {
    if (diff == null) return
    val changedEntries = diff.entries.filter { it.countDelta != 0 || it.shallowSizeDelta != 0L }
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("Heap diff", fontWeight = FontWeight.Bold)
        if (changedEntries.isEmpty()) {
            Text("No class changes between the latest two heap dumps.")
        } else {
            changedEntries.take(10).forEach { entry ->
                Text(
                    "${entry.className}: ${entry.beforeCount} → ${entry.afterCount} " +
                        "(${entry.countDelta.withSign()}) · ${entry.shallowSizeDelta.withSign()} B",
                )
            }
        }
    }
}

@Composable
private fun BitmapSection(bitmaps: List<com.androidperformancestudio.memory.model.BitmapInstanceStats>) {
    if (bitmaps.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("Bitmap analysis", fontWeight = FontWeight.Bold)
        bitmaps.take(8).forEach { bitmap ->
            Text(
                "#${bitmap.objectId} ${bitmap.width ?: "?"}×${bitmap.height ?: "?"} · " +
                    formatBytes(bitmap.retainedSize),
            )
        }
    }
}

private fun Int.withSign(): String = if (this >= 0) "+$this" else toString()

private fun Long.withSign(): String = if (this >= 0) "+$this" else toString()

private fun integer(value: Int): String = NumberFormat.getIntegerInstance(Locale.US).format(value)

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= BYTES_PER_MB -> "%.1f MB".format(Locale.US, bytes.toDouble() / BYTES_PER_MB)
        bytes >= BYTES_PER_KB -> "%.1f KB".format(Locale.US, bytes.toDouble() / BYTES_PER_KB)
        else -> "$bytes B"
    }

private const val BYTES_PER_KB = 1024.0
private const val BYTES_PER_MB = 1024.0 * 1024.0
