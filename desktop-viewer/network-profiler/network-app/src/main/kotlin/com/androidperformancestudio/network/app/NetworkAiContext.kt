package com.androidperformancestudio.network.app

import com.androidperformancestudio.network.model.HttpCall
import com.androidperformancestudio.network.presentation.NetworkProfilerState
import com.androidperformancestudio.ui.UiAiContext
import com.androidperformancestudio.ui.UiAiEvidence
import com.androidperformancestudio.ui.uiAiPayload
import com.androidperformancestudio.ui.uiAiEvidenceId

internal fun networkAiContext(state: NetworkProfilerState): UiAiContext? {

    val result = state.result ?: return null
    if (state.capturing) return null
    val selected = state.selectedCallId?.let { id -> result.calls.firstOrNull { it.callId == id } ?: return null }
    val rows =
        if (selected != null) {
            listOf(selected)
        } else {
            result.calls
                .sortedWith(compareByDescending<HttpCall> { it.durationNs ?: Long.MIN_VALUE }.thenBy { it.callId })
                .take(6)
        }
    val completeness = result.session.completeness
    val payload = uiAiPayload(
        "timeUnit" to "nanoseconds", "callCount" to result.calls.size, "includedCalls" to rows.size,
        "omittedCalls" to if (selected == null) result.calls.size - rows.size else 0,
        "sourceTimeDomain" to result.session.sourceTimeDomain.name, "completeness" to completeness.status.name,
        "droppedEvents" to completeness.droppedEvents, "sequenceGaps" to completeness.sequenceGaps,
        "unpairedEvents" to completeness.unpairedEvents, "skippedRecords" to completeness.skippedRecords,
        "coverageMode" to result.session.coverage.instrumentationMode.name,
        "coverageLimitationCount" to result.session.coverage.knownLimitations.size,
        "calls" to rows.map { call ->
            val phaseCount = call.exchanges.sumOf { it.phases.size }
            val phases = call.exchanges.asSequence().flatMap { it.phases }.take(4).toList()
            mapOf(
                "callRef" to uiAiEvidenceId("network-call", result.session.id, call.callId),
                "durationNs" to call.durationNs,
                "outcome" to call.outcome.name,
                "source" to call.source.name,
                "exchangeCount" to call.exchanges.size,
                "statusCodes" to call.exchanges.take(4).map { it.statusCode },
                "omittedExchanges" to (call.exchanges.size - 4).coerceAtLeast(0),
                "phases" to phases.map { mapOf("kind" to it.kind.name, "durationNs" to it.durationNs, "availability" to it.availability.name, "confidence" to it.confidence.name) },
                "omittedPhases" to (phaseCount - phases.size),
            )
        },
    )
    val kind = if (selected != null) "network-selection" else "network-summary"
    return UiAiContext("network", "Network evidence", selected != null, listOf(UiAiEvidence(uiAiEvidenceId(kind, result.session.id, payload), kind, "Coverage differs from completeness; URLs, headers and bodies excluded", payload)))
}
