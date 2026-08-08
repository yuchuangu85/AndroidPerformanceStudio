package com.androidperformancestudio.network.analysis

import com.androidperformancestudio.network.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NetworkAnalyzerTest {
    @Test
    fun `keeps transport outcomes http status and connection reuse separate`() {
        val calls = listOf(call("1", 10_000_000, CallOutcome.COMPLETED, 500, ConnectionUse.NEW), call("2", 20_000_000, CallOutcome.FAILED, null, ConnectionUse.REUSED))
        val summary = NetworkAnalyzer().summarize(calls)
        assertEquals(2, summary.callCount)
        assertEquals(1, summary.completedCount)
        assertEquals(1, summary.failureCount)
        assertEquals(mapOf("5xx" to 1), summary.httpStatusFamilies)
        assertEquals(20, summary.totalRequestBytes)
        assertEquals(40, summary.totalResponseBytes)
        assertEquals(0.5, summary.connectionReuse.reuseRateAmongKnown)
    }

    @Test
    fun `computes connection reuse metrics`() {
        val calls =
            listOf(
                call("1", 10_000_000, CallOutcome.COMPLETED, connectionUse = ConnectionUse.REUSED),
                call("2", 20_000_000, CallOutcome.COMPLETED, connectionUse = ConnectionUse.NEW),
                call("3", 15_000_000, CallOutcome.COMPLETED, connectionUse = ConnectionUse.REUSED),
            )
        val summary = NetworkAnalyzer().summarize(calls)
        assertEquals(2, summary.connectionReuse.reusedExchangeCount)
        assertEquals(1, summary.connectionReuse.newExchangeCount)
        assertTrue(summary.connectionReuse.reuseRateAmongKnown != null && summary.connectionReuse.reuseRateAmongKnown > 0.6)
    }

    @Test
    fun `computes concurrency with sweep line`() {
        // Two overlapping calls: call1 [0, 10M], call2 [5M, 15M]
        val calls =
            listOf(
                call("1", 10_000_000, CallOutcome.COMPLETED, start = 0),
                call("2", 10_000_000, CallOutcome.COMPLETED, start = 5_000_000),
            )
        val summary = NetworkAnalyzer().summarize(calls)
        assertEquals(2, summary.concurrency.peakConcurrency)
        assertNotNull(summary.concurrency.peakConcurrencyNs)
        assertTrue(summary.concurrency.timeline.isNotEmpty())
        // Timeline should have at least the start of call1, start of call2, end of call1, end of call2
        assertTrue(summary.concurrency.timeline.size >= 4)
    }

    @Test
    fun `concurrency handles sequential calls`() {
        // Two sequential calls: call1 [0, 10M], call2 [12M, 20M]
        val calls =
            listOf(
                call("1", 10_000_000, CallOutcome.COMPLETED, start = 0),
                call("2", 8_000_000, CallOutcome.COMPLETED, start = 12_000_000),
            )
        val summary = NetworkAnalyzer().summarize(calls)
        assertEquals(1, summary.concurrency.peakConcurrency)
    }

    private fun call(
        id: String,
        duration: Long,
        outcome: CallOutcome,
        status: Int? = 200,
        connectionUse: ConnectionUse = ConnectionUse.REUSED,
        start: Long? = null,
    ): HttpCall {
        val startedNs = start ?: 0
        val reusePhases = when (connectionUse) {
            ConnectionUse.NEW -> listOf(
                NetworkPhase(NetworkPhaseKind.DNS, 1_000_000, 2_000_000, NetworkConfidence.EXACT),
                NetworkPhase(NetworkPhaseKind.CONNECT, 2_000_000, 3_000_000, NetworkConfidence.EXACT),
                NetworkPhase(NetworkPhaseKind.TLS, 3_000_000, 5_000_000, NetworkConfidence.EXACT),
            )
            ConnectionUse.REUSED -> listOf(
                NetworkPhase(NetworkPhaseKind.CONNECTION_HELD, 1_000_000, duration, NetworkConfidence.EXACT),
            )
            ConnectionUse.UNKNOWN -> emptyList()
        }
        return HttpCall(
            callId = id,
            instrumentationId = "client",
            method = "GET",
            redactedUrl = "https://example.test/",
            startedNs = startedNs,
            endedNs = startedNs + duration,
            exchanges = listOf(
                HttpExchange(
                    exchangeIndex = 0,
                    connectionId = "connection",
                    connectionUse = connectionUse,
                    protocol = "h2",
                    statusCode = status,
                    requestBytes = 10,
                    responseBytes = 20,
                    decodedResponseBytes = null,
                    phases = reusePhases + listOf(NetworkPhase(NetworkPhaseKind.TOTAL, startedNs, startedNs + duration, NetworkConfidence.EXACT)),
                    cacheDisposition = CacheDisposition.HIT,
                    failure = null,
                ),
            ),
            outcome = outcome,
            source = NetworkEvidenceSource.HAR_IMPORT,
        )
    }
}
