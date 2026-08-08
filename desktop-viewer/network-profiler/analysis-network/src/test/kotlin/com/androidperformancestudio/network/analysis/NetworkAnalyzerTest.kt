package com.androidperformancestudio.network.analysis

import com.androidperformancestudio.network.model.*
import kotlin.test.Test
import kotlin.test.assertEquals

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

    private fun call(id: String, duration: Long, outcome: CallOutcome, status: Int?, connectionUse: ConnectionUse): HttpCall =
        HttpCall(
            callId = id,
            instrumentationId = "client",
            method = "GET",
            redactedUrl = "https://example.test/",
            startedNs = 0,
            endedNs = duration,
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
                    phases = listOf(NetworkPhase(NetworkPhaseKind.TOTAL, 0, duration, NetworkConfidence.EXACT)),
                    cacheDisposition = CacheDisposition.HIT,
                    failure = null,
                ),
            ),
            outcome = outcome,
            source = NetworkEvidenceSource.HAR_IMPORT,
        )
}
