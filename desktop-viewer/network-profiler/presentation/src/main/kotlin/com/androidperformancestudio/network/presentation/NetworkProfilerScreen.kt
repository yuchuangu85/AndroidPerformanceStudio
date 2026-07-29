@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod", "MagicNumber")

package com.androidperformancestudio.network.presentation

import com.androidperformancestudio.ui.UiLanguage
import org.jetbrains.compose.resources.stringResource

import com.androidperformancestudio.network.presentation.generated.resources.Res
import com.androidperformancestudio.network.presentation.generated.resources.*

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
public fun NetworkProfilerScreen(state: NetworkProfilerState, actions: NetworkProfilerActions, language: UiLanguage, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Summary(stringResource(Res.string.calls), state.summary?.callCount?.toString() ?: "—", Modifier.weight(1f))
            Summary(stringResource(Res.string.failures), state.summary?.failureCount?.toString() ?: "—", Modifier.weight(1f))
            Summary(stringResource(Res.string.p50), state.summary?.medianDurationMs?.let { "%.1f ms".format(it) } ?: "—", Modifier.weight(1f))
            Summary(stringResource(Res.string.p95), state.summary?.p95DurationMs?.let { "%.1f ms".format(it) } ?: "—", Modifier.weight(1f))
            Summary(stringResource(Res.string.dropped), state.result?.session?.coverage?.droppedEvents?.toString() ?: "0", Modifier.weight(1f))
        }
        state.result?.session?.coverage?.let { coverage ->
            Text(stringResource(Res.string.text, coverage.instrumentationMode, coverage.completeness, coverage.observedLibraries.joinToString()))
            Text(stringResource(Res.string.not_covered, coverage.unsupportedStacks.joinToString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { items(state.result?.calls.orEmpty()) { call -> CallCard(call, call.callId == state.selectedCallId, language) { actions.selectCall(call.callId) } } }
            VerticalDivider(color = MaterialTheme.colorScheme.outline)
            val selected = state.result?.calls?.firstOrNull { it.callId == state.selectedCallId }
            Card(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(Res.string.request_details), style = MaterialTheme.typography.titleLarge)
                    if (selected == null) {
                        Text(stringResource(Res.string.select_a_call_to_inspect_phase_evidence))
                    } else {
                        Text(stringResource(Res.string.text_b8cc21ae, selected.method, selected.redactedUrl))
                        selected.exchanges.forEach { exchange ->
                            Text(stringResource(Res.string.http_exchange, exchange.statusCode ?: "—", exchange.protocol ?: "—", exchange.connectionId ?: stringResource(Res.string.reused_unknown)))
                            exchange.phases.forEach { phase ->
                                Text(stringResource(Res.string.phase_detail, phase.kind, phase.durationNs?.div(1_000_000.0)?.let { "%.2f ms".format(it) } ?: stringResource(Res.string.unavailable), phase.confidence))
                            }
                            exchange.failure?.let { Text(stringResource(Res.string.text_c08282b1, it.type, it.message ?: "null"), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Summary(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable private fun CallCard(call: HttpCall, selected: Boolean, language: UiLanguage, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = if (selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(6.dp)) {
            Text(stringResource(Res.string.text_fb68e1ae, call.method, call.outcome))
            Text(call.redactedUrl, maxLines = 1, style = MaterialTheme.typography.bodySmall)
            Text(call.durationNs?.div(1_000_000.0)?.let { "%.2f ms".format(it) } ?: stringResource(Res.string.incomplete))
        }
    }
}
