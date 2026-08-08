package com.androidperformancestudio.network.analysis

import com.androidperformancestudio.network.model.*

public data class PhaseSummary(
    val source: NetworkEvidenceSource,
    val kind: NetworkPhaseKind,
    val sampleCount: Int,
    val missingCount: Int,
    val medianDurationMs: Double?,
    val p95DurationMs: Double?,
)

public data class ConnectionReuseSummary(
    val newExchangeCount: Int,
    val reusedExchangeCount: Int,
    val unknownExchangeCount: Int,
    val reuseRateAmongKnown: Double?,
)

public data class NetworkSummary(
    val callCount: Int,
    val completedCount: Int,
    val failureCount: Int,
    val cancelledCount: Int,
    val incompleteCount: Int,
    val httpStatusFamilies: Map<String, Int>,
    val totalRequestBytes: Long,
    val totalResponseBytes: Long,
    val totalDecodedResponseBytes: Long,
    val medianDurationMs: Double?,
    val p90DurationMs: Double?,
    val p95DurationMs: Double?,
    val cacheHitRate: Double?,
    val connectionReuse: ConnectionReuseSummary,
    val phaseSummaries: List<PhaseSummary>,
    val largestObservedPhase: PhaseSummary?,
    val slowestCalls: List<HttpCall>,
    val missingTimingCount: Int,
)

public class NetworkAnalyzer {
    public fun summarize(calls: List<HttpCall>): NetworkSummary {
        val durations = calls.mapNotNull { it.durationNs?.div(1_000_000.0) }.sorted()
        val exchanges = calls.flatMap { it.exchanges }
        val cacheKnown = exchanges.filter { it.cacheDisposition != CacheDisposition.UNKNOWN }
        val phaseSummaries = summarizePhases(calls)
        val knownConnections = exchanges.filter { it.connectionUse != ConnectionUse.UNKNOWN }
        return NetworkSummary(
            callCount = calls.size,
            completedCount = calls.count { it.outcome == CallOutcome.COMPLETED },
            failureCount = calls.count { it.outcome == CallOutcome.FAILED },
            cancelledCount = calls.count { it.outcome == CallOutcome.CANCELLED },
            incompleteCount = calls.count { it.outcome == CallOutcome.INCOMPLETE },
            httpStatusFamilies = exchanges.mapNotNull { it.statusCode }.groupingBy(::statusFamily).eachCount().toSortedMap(),
            totalRequestBytes = exchanges.mapNotNull { it.requestBytes }.sum(),
            totalResponseBytes = exchanges.mapNotNull { it.responseBytes }.sum(),
            totalDecodedResponseBytes = exchanges.mapNotNull { it.decodedResponseBytes }.sum(),
            medianDurationMs = percentile(durations, 0.5),
            p90DurationMs = percentile(durations, 0.9),
            p95DurationMs = percentile(durations, 0.95),
            cacheHitRate = cacheKnown.takeIf { it.isNotEmpty() }?.let { known -> known.count { it.cacheDisposition == CacheDisposition.HIT }.toDouble() / known.size },
            connectionReuse = ConnectionReuseSummary(
                newExchangeCount = exchanges.count { it.connectionUse == ConnectionUse.NEW },
                reusedExchangeCount = exchanges.count { it.connectionUse == ConnectionUse.REUSED },
                unknownExchangeCount = exchanges.count { it.connectionUse == ConnectionUse.UNKNOWN },
                reuseRateAmongKnown = knownConnections.takeIf { it.isNotEmpty() }?.let { known -> known.count { it.connectionUse == ConnectionUse.REUSED }.toDouble() / known.size },
            ),
            phaseSummaries = phaseSummaries,
            largestObservedPhase = phaseSummaries.filter { it.kind != NetworkPhaseKind.TOTAL }.maxByOrNull { it.medianDurationMs ?: -1.0 },
            slowestCalls = calls.sortedByDescending { it.durationNs ?: -1 }.take(20),
            missingTimingCount = calls.count { call -> call.endedNs == null || call.exchanges.any { exchange -> exchange.phases.none { it.kind == NetworkPhaseKind.TOTAL && it.durationNs != null } } },
        )
    }

    private fun summarizePhases(calls: List<HttpCall>): List<PhaseSummary> {
        data class Key(
            val source: NetworkEvidenceSource,
            val kind: NetworkPhaseKind,
        )
        val groups = calls.flatMap { call -> call.exchanges.flatMap { exchange -> exchange.phases.map { phase -> Key(call.source, phase.kind) to phase } } }.groupBy({ it.first }, { it.second })
        return groups.map { (key, phases) ->
            val durations = phases.mapNotNull { it.durationNs?.div(1_000_000.0) }.sorted()
            PhaseSummary(key.source, key.kind, durations.size, phases.size - durations.size, percentile(durations, 0.5), percentile(durations, 0.95))
        }.sortedWith(compareBy({ it.source.name }, { it.kind.ordinal }))
    }

    private fun statusFamily(status: Int): String = if (status in 100..599) "${status / 100}xx" else "other"

    private fun percentile(values: List<Double>, fraction: Double): Double? {
        if (values.isEmpty()) return null
        val index = ((values.size - 1) * fraction).toInt().coerceIn(values.indices)
        return values[index]
    }
}
