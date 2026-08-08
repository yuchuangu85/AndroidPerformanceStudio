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
import com.androidperformancestudio.network.model.CallOutcome
import com.androidperformancestudio.network.model.ConnectionUse
import com.androidperformancestudio.network.model.EvidenceCompleteness
import com.androidperformancestudio.network.model.HttpCall
import com.androidperformancestudio.network.model.InstrumentationMode
import com.androidperformancestudio.network.model.NetworkCaptureResult
import com.androidperformancestudio.network.model.NetworkConfidence
import com.androidperformancestudio.network.model.NetworkPhaseKind
import com.androidperformancestudio.network.presentation.generated.resources.*
import com.androidperformancestudio.network.presentation.generated.resources.Res
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

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
            Summary(localizedStringResource(Res.string.calls, language), state.summary?.callCount?.toString() ?: "—", Modifier.weight(1f))
            Summary(localizedStringResource(Res.string.failures, language), state.summary?.failureCount?.toString() ?: "—", Modifier.weight(1f))
            Summary(localizedStringResource(Res.string.p50, language), state.summary?.medianDurationMs?.let { "%.1f ms".format(it) } ?: "—", Modifier.weight(1f))
            Summary(localizedStringResource(Res.string.p95, language), state.summary?.p95DurationMs?.let { "%.1f ms".format(it) } ?: "—", Modifier.weight(1f))
            Summary(localizedStringResource(Res.string.dropped, language), state.result?.session?.completeness?.droppedEvents?.toString() ?: "0", Modifier.weight(1f))
        }
        state.result?.session?.coverage?.let { coverage ->
            Text(
                localizedStringResource(
                    Res.string.text,
                    language,
                    coverage.instrumentationMode.displayName(language),
                    state.result.session.completeness.status.displayName(language),
                    coverage.observedLibraries.joinToString(),
                ),
            )
            Text(
                localizedStringResource(
                    Res.string.not_covered,
                    language,
                    coverage.knownLimitations.joinToString(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.summary?.let { summary ->
            Text(localizedStringResource(Res.string.outcome_summary, language, summary.completedCount, summary.failureCount, summary.cancelledCount, summary.incompleteCount, summary.httpStatusFamilies.entries.joinToString { "${it.key}:${it.value}" }.ifEmpty { "—" }), style = MaterialTheme.typography.bodySmall)
            val reuse = summary.connectionReuse
            Text(localizedStringResource(Res.string.reuse_summary, language, reuse.reuseRateAmongKnown?.let { "%.1f%%".format(it * 100) } ?: "—", reuse.newExchangeCount, reuse.reusedExchangeCount, reuse.unknownExchangeCount), style = MaterialTheme.typography.bodySmall)
            summary.largestObservedPhase?.let { phase -> Text(localizedStringResource(Res.string.largest_phase, language, phase.kind.displayName(language), phase.medianDurationMs?.let { "%.2f ms".format(it) } ?: "—"), style = MaterialTheme.typography.bodySmall) }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { items(state.result?.calls.orEmpty()) { call -> CallCard(call, call.callId == state.selectedCallId, language) { actions.selectCall(call.callId) } } }
            VerticalDivider(color = MaterialTheme.colorScheme.outline)
            val selected = state.result?.calls?.firstOrNull { it.callId == state.selectedCallId }
            Card(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(localizedStringResource(Res.string.request_details, language), style = MaterialTheme.typography.titleLarge)
                    if (selected == null) {
                        Text(localizedStringResource(Res.string.select_a_call_to_inspect_phase_evidence, language))
                    } else {
                        Text(localizedStringResource(Res.string.text_b8cc21ae, language, selected.method, selected.redactedUrl))
                        selected.exchanges.forEach { exchange ->
                            Text(
                                localizedStringResource(
                                    Res.string.http_exchange,
                                    language,
                                    exchange.statusCode ?: "—",
                                    exchange.protocol ?: "—",
                                    exchange.connectionId ?: localizedStringResource(Res.string.reused_unknown, language),
                                    exchange.connectionUse.displayName(language),
                                ),
                            )
                            exchange.tlsHandshake?.let { handshake -> Text(localizedStringResource(Res.string.tls_handshake, language, handshake.tlsVersion ?: "—", handshake.cipherSuite ?: "—"), style = MaterialTheme.typography.bodySmall) }
                            exchange.phases.forEach { phase ->
                                Text(
                                    localizedStringResource(
                                        Res.string.phase_detail,
                                        language,
                                        phase.kind.displayName(language),
                                        phase.durationNs
                                            ?.div(1_000_000.0)
                                            ?.let { "%.2f ms".format(it) }
                                            ?: localizedStringResource(Res.string.unavailable, language),
                                        phase.confidence.displayName(language),
                                    ),
                                )
                            }
                            exchange.failure?.let {
                                Text(
                                    localizedStringResource(Res.string.text_c08282b1, language, it.type, it.message ?: "null"),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
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
            Text(localizedStringResource(Res.string.text_fb68e1ae, language, call.method, call.outcome.displayName(language)))
            Text(call.redactedUrl, maxLines = 1, style = MaterialTheme.typography.bodySmall)
            Text(call.durationNs?.div(1_000_000.0)?.let { "%.2f ms".format(it) } ?: localizedStringResource(Res.string.incomplete, language))
        }
    }
}

private fun InstrumentationMode.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            InstrumentationMode.EXPLICIT_FACTORY -> Res.string.instrumentation_explicit_factory
            InstrumentationMode.INSTRUMENTED_PARTIAL -> Res.string.instrumentation_partial
            InstrumentationMode.HAR_IMPORT -> Res.string.instrumentation_har_import
            InstrumentationMode.RAW_IMPORT -> Res.string.instrumentation_raw_import
        },
        language,
    )

private fun NetworkConfidence.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            NetworkConfidence.EXACT -> Res.string.confidence_exact
            NetworkConfidence.DERIVED -> Res.string.confidence_derived
            NetworkConfidence.INFERRED -> Res.string.confidence_inferred
            NetworkConfidence.PARTIAL -> Res.string.confidence_partial
            NetworkConfidence.UNKNOWN -> Res.string.confidence_unknown
        },
        language,
    )

private fun CallOutcome.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            CallOutcome.COMPLETED -> Res.string.outcome_completed
            CallOutcome.FAILED -> Res.string.outcome_failed
            CallOutcome.CANCELLED -> Res.string.outcome_cancelled
            CallOutcome.INCOMPLETE -> Res.string.outcome_incomplete
        },
        language,
    )

private fun EvidenceCompleteness.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            EvidenceCompleteness.COMPLETE -> Res.string.completeness_complete
            EvidenceCompleteness.PARTIAL -> Res.string.confidence_partial
            EvidenceCompleteness.UNKNOWN -> Res.string.confidence_unknown
        },
        language,
    )

private fun ConnectionUse.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            ConnectionUse.NEW -> Res.string.connection_new
            ConnectionUse.REUSED -> Res.string.connection_reused
            ConnectionUse.UNKNOWN -> Res.string.connection_unknown
        },
        language,
    )

private fun NetworkPhaseKind.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            NetworkPhaseKind.DISPATCHER_QUEUE -> Res.string.phase_dispatcher_queue
            NetworkPhaseKind.PROXY_SELECT -> Res.string.phase_proxy_select
            NetworkPhaseKind.DNS -> Res.string.phase_dns
            NetworkPhaseKind.CONNECT -> Res.string.phase_connect
            NetworkPhaseKind.TLS -> Res.string.phase_tls
            NetworkPhaseKind.REQUEST_HEADERS -> Res.string.phase_request_headers
            NetworkPhaseKind.REQUEST_BODY -> Res.string.phase_request_body
            NetworkPhaseKind.SERVER_WAIT -> Res.string.phase_server_wait
            NetworkPhaseKind.RESPONSE_HEADERS -> Res.string.phase_response_headers
            NetworkPhaseKind.RESPONSE_BODY -> Res.string.phase_response_body
            NetworkPhaseKind.CONNECTION_HELD -> Res.string.phase_connection_held
            NetworkPhaseKind.TOTAL -> Res.string.phase_total
        },
        language,
    )
