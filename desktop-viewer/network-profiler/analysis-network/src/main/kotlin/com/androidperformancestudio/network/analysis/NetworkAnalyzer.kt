package com.androidperformancestudio.network.analysis

import com.androidperformancestudio.network.model.CacheDisposition
import com.androidperformancestudio.network.model.CallOutcome
import com.androidperformancestudio.network.model.HttpCall
import com.androidperformancestudio.network.model.NetworkPhaseKind

public data class NetworkSummary(
    val callCount: Int,
    val failureCount: Int,
    val totalRequestBytes: Long,
    val totalResponseBytes: Long,
    val medianDurationMs: Double?,
    val p90DurationMs: Double?,
    val p95DurationMs: Double?,
    val cacheHitRate: Double?,
    val slowestCalls: List<HttpCall>,
    val missingTimingCount: Int,
)

public class NetworkAnalyzer {
    public fun summarize(calls: List<HttpCall>): NetworkSummary {
        val durations = calls.mapNotNull { it.durationNs?.div(1_000_000.0) }.sorted()
        val exchanges = calls.flatMap { it.exchanges }
        val cacheKnown = exchanges.filter { it.cacheDisposition != CacheDisposition.UNKNOWN }
        return NetworkSummary(
            callCount = calls.size,
            failureCount = calls.count { it.outcome in setOf(CallOutcome.FAILED, CallOutcome.CANCELLED) },
            totalRequestBytes = exchanges.mapNotNull { it.requestBytes }.sum(),
            totalResponseBytes = exchanges.mapNotNull { it.responseBytes }.sum(),
            medianDurationMs = percentile(durations, 0.5),
            p90DurationMs = percentile(durations, 0.9),
            p95DurationMs = percentile(durations, 0.95),
            cacheHitRate = cacheKnown.takeIf { it.isNotEmpty() }?.let { known -> known.count { it.cacheDisposition == CacheDisposition.HIT }.toDouble() / known.size },
            slowestCalls = calls.sortedByDescending { it.durationNs ?: -1 }.take(20),
            missingTimingCount = calls.count { call -> call.endedNs == null || call.exchanges.any { exchange -> exchange.phases.none { it.kind == NetworkPhaseKind.TOTAL && it.durationNs != null } } },
        )
    }

    private fun percentile(values: List<Double>, fraction: Double): Double? {
        if (values.isEmpty()) return null
        val index = ((values.size - 1) * fraction).toInt().coerceIn(values.indices)
        return values[index]
    }
}
