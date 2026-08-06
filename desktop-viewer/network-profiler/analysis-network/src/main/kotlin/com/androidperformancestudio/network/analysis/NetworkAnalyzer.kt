package com.androidperformancestudio.network.analysis

import com.androidperformancestudio.network.model.CacheDisposition
import com.androidperformancestudio.network.model.CallOutcome
import com.androidperformancestudio.network.model.HttpCall
import com.androidperformancestudio.network.model.HttpExchange
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
    /** Connection reuse metrics. Computed across all exchanges. */
    val connectionReuse: ConnectionReuseSummary = ConnectionReuseSummary(),
    /** Concurrency analysis results (sweep-line algorithm). */
    val concurrency: ConcurrencySummary = ConcurrencySummary(),
)

/**
 * Summary of connection reuse across all exchanges.
 *
 * An exchange is considered "reused" when it has no DNS, CONNECT, or TLS phases —
 * the underlying TCP+TLS connection was taken from the pool.
 */
public data class ConnectionReuseSummary(
    /** Total exchanges that used a reused connection. */
    val reusedExchangeCount: Int = 0,
    /** Total exchanges that established a new connection (cold). */
    val coldExchangeCount: Int = 0,
    /** Reuse ratio: reused / (reused + cold), or null if no exchanges. */
    val reuseRatio: Double? = null,
    /** Average CONNECTION_HELD duration in milliseconds for reused connections. */
    val avgConnectionHeldMs: Double? = null,
)

/**
 * Concurrency analysis via sweep-line algorithm.
 *
 * Each call's [startedNs, endedNs] interval contributes +1 at start and -1 at end.
 * The sweep-line finds the peak concurrency and provides a time-series for
 * visualization.
 */
public data class ConcurrencySummary(
    /** Maximum number of concurrent in-flight calls at any point in time. */
    val peakConcurrency: Int = 0,
    /** Timestamp (ns) of the peak concurrency. */
    val peakConcurrencyNs: Long? = null,
    /** Average concurrent calls over the entire capture window. */
    val avgConcurrency: Double? = null,
    /**
     * Concurrency timeline: pairs of (timestampNs, activeCount), one entry
     * per concurrency change. Suitable for rendering as a step chart.
     */
    val timeline: List<ConcurrencyPoint> = emptyList(),
)

/**
 * A single point in the concurrency timeline.
 * [activeCount] is the number of in-flight calls at [timestampNs].
 */
public data class ConcurrencyPoint(
    val timestampNs: Long,
    val activeCount: Int,
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
            connectionReuse = computeReuse(exchanges),
            concurrency = computeConcurrency(calls),
        )
    }

    /**
     * Computes connection reuse metrics across all exchanges.
     *
     * An exchange is "reused" when [HttpExchange.connectionReused] is true
     * (i.e. no DNS, CONNECT, or TLS phases were observed).
     * Additionally, the average [NetworkPhaseKind.CONNECTION_HELD] duration
     * is computed for reused exchanges.
     */
    private fun computeReuse(exchanges: List<HttpExchange>): ConnectionReuseSummary {
        val reused = exchanges.count { it.connectionReused }
        val cold = exchanges.count { !it.connectionReused }
        val ratio = if (reused + cold > 0) reused.toDouble() / (reused + cold) else null
        val heldDurations = exchanges
            .filter { it.connectionReused }
            .mapNotNull { exchange ->
                exchange.phases
                    .firstOrNull { it.kind == NetworkPhaseKind.CONNECTION_HELD }
                    ?.durationNs
                    ?.div(1_000_000.0)
            }
        val avgHeld = if (heldDurations.isNotEmpty()) heldDurations.average() else null
        return ConnectionReuseSummary(reused, cold, ratio, avgHeld)
    }

    /**
     * Sweep-line algorithm for concurrent request counting.
     *
     * For each call with valid [startedNs, endedNs]:
     * 1. Create +1 event at start, -1 event at end
     * 2. Sort all events by timestamp (ties: -1 before +1 so end-of-frame is accurate)
     * 3. Sweep through, tracking active count → peak + timeline
     *
     * Calls with null [endedNs] are excluded from the sweep.
     */
    private fun computeConcurrency(calls: List<HttpCall>): ConcurrencySummary {
        val events = calls.mapNotNull { call ->
            val end = call.endedNs ?: return@mapNotNull null
            if (end <= call.startedNs) return@mapNotNull null
            listOf(
                ConcurrencyEvent(call.startedNs, +1),
                ConcurrencyEvent(end, -1),
            )
        }.flatten().sortedWith(compareBy({ it.timestampNs }, { it.delta }))

        if (events.isEmpty()) return ConcurrencySummary()

        val timeline = mutableListOf<ConcurrencyPoint>()
        var active = 0
        var peak = 0
        var peakNs = 0L
        var weightedSum = 0.0
        var lastTs = events.first().timestampNs

        for (event in events) {
            // Weighted sum contribution from [lastTs, event.timestampNs)
            if (event.timestampNs > lastTs) {
                weightedSum += active.toDouble() * (event.timestampNs - lastTs)
            }
            active += event.delta
            timeline.add(ConcurrencyPoint(event.timestampNs, active))
            if (active > peak) {
                peak = active
                peakNs = event.timestampNs
            }
            lastTs = event.timestampNs
        }

        val totalNs = lastTs - events.first().timestampNs
        val avg = if (totalNs > 0) weightedSum / totalNs else active.toDouble()

        return ConcurrencySummary(peak, peakNs.takeIf { peak > 0 }, avg, timeline)
    }

    private fun percentile(values: List<Double>, fraction: Double): Double? {
        if (values.isEmpty()) return null
        val index = ((values.size - 1) * fraction).toInt().coerceIn(values.indices)
        return values[index]
    }

    private data class ConcurrencyEvent(val timestampNs: Long, val delta: Int)
}
