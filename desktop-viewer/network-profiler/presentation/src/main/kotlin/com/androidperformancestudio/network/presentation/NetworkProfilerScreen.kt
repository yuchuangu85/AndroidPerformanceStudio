@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod", "MagicNumber")

package com.androidperformancestudio.network.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.network.analysis.NetworkSummary
import com.androidperformancestudio.network.model.HttpCall
import com.androidperformancestudio.network.model.NetworkCaptureResult

public data class NetworkProfilerState(
    val deviceSerial: String = "",
    val packageName: String = "",
    val capturing: Boolean = false,
    val liveEventCount: Int = 0,
    val result: NetworkCaptureResult? = null,
    val summary: NetworkSummary? = null,
    val selectedCallId: String? = null,
    val message: String? = null,
    val error: String? = null,
)

public data class NetworkProfilerActions(
    val selectCall: (String) -> Unit,
)

@Composable
public fun NetworkProfilerScreen(state: NetworkProfilerState, actions: NetworkProfilerActions, chinese: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Summary(if (chinese)"请求" else "Calls", state.summary?.callCount?.toString() ?: "—", Modifier.weight(1f))
            Summary(if (chinese)"失败" else "Failures", state.summary?.failureCount?.toString() ?: "—", Modifier.weight(1f))
            Summary("p50", state.summary?.medianDurationMs?.let { "%.1f ms".format(it) } ?: "—", Modifier.weight(1f))
            Summary("p95", state.summary?.p95DurationMs?.let { "%.1f ms".format(it) } ?: "—", Modifier.weight(1f))
            Summary(if (chinese)"丢弃事件" else "Dropped", state.result?.session?.coverage?.droppedEvents?.toString() ?: "0", Modifier.weight(1f))
        }
        state.result?.session?.coverage?.let { coverage ->
            Text("${coverage.instrumentationMode} · ${coverage.completeness} · ${coverage.observedLibraries.joinToString()}")
            Text(if (chinese)"覆盖范围不包括：${coverage.unsupportedStacks.joinToString()}" else "Not covered: ${coverage.unsupportedStacks.joinToString()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { items(state.result?.calls.orEmpty()) { call -> CallCard(call, call.callId == state.selectedCallId) { actions.selectCall(call.callId) } } }
            val selected = state.result?.calls?.firstOrNull { it.callId == state.selectedCallId }
            Card(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (chinese)"请求详情" else "Request details", style = MaterialTheme.typography.titleLarge)
                    if (selected == null) {
                        Text(if (chinese)"选择请求查看阶段证据" else "Select a call to inspect phase evidence")
                    } else {
                        Text("${selected.method} ${selected.redactedUrl}")
                        selected.exchanges.forEach { exchange ->
                            Text("HTTP ${exchange.statusCode ?: "—"} · ${exchange.protocol ?: "—"} · connection ${exchange.connectionId ?: "reused/unknown"}")
                            exchange.phases.forEach { phase -> Text("${phase.kind}: ${phase.durationNs?.div(1_000_000.0)?.let { "%.2f ms".format(it) } ?: "unavailable"} · ${phase.confidence}") }
                            exchange.failure?.let { Text("${it.type}: ${it.message}", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Summary(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable private fun CallCard(call: HttpCall, selected: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = if (selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text("${call.method} · ${call.outcome}")
            Text(call.redactedUrl, maxLines = 1, style = MaterialTheme.typography.bodySmall)
            Text(call.durationNs?.div(1_000_000.0)?.let { "%.2f ms".format(it) } ?: "incomplete")
        }
    }
}
