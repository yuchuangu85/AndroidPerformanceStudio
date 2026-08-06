package com.androidperformancestudio.network.analysis

import com.androidperformancestudio.network.model.CacheDisposition
import com.androidperformancestudio.network.model.CallOutcome
import com.androidperformancestudio.network.model.HttpCall
import com.androidperformancestudio.network.model.HttpExchange
import com.androidperformancestudio.network.model.NetworkConfidence
import com.androidperformancestudio.network.model.NetworkEvidenceSource
import com.androidperformancestudio.network.model.NetworkPhase
import com.androidperformancestudio.network.model.NetworkPhaseKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NetworkAnalyzerTest {
    @Test
    fun `summarizes failures bytes and percentiles`() {
        val calls =
            listOf(
                call("1", 10_000_000, CallOutcome.SUCCESS),
                call("2", 20_000_000, CallOutcome.FAILED),
            )
        val summary = NetworkAnalyzer().summarize(calls)
        assertEquals(2, summary.callCount)
        assertEquals(1, summary.failureCount)
        assertEquals(20, summary.totalRequestBytes)
        assertEquals(40, summary.totalResponseBytes)
    }

    @Test
    fun `computes connection reuse metrics`() {
        val calls =
            listOf(
                call("1", 10_000_000, CallOutcome.SUCCESS, reused = true),
                call("2", 20_000_000, CallOutcome.SUCCESS, reused = false),
                call("3", 15_000_000, CallOutcome.SUCCESS, reused = true),
            )
        val summary = NetworkAnalyzer().summarize(calls)
        assertEquals(2, summary.connectionReuse.reusedExchangeCount)
        assertEquals(1, summary.connectionReuse.coldExchangeCount)
        assertTrue(summary.connectionReuse.reuseRatio != null && summary.connectionReuse.reuseRatio > 0.6)
    }

    @Test
    fun `computes concurrency with sweep line`() {
        // Two overlapping calls: call1 [0, 10M], call2 [5M, 15M]
        val calls =
            listOf(
                call("1", 10_000_000, CallOutcome.SUCCESS, start = 0),
                call("2", 10_000_000, CallOutcome.SUCCESS, start = 5_000_000),
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
                call("1", 10_000_000, CallOutcome.SUCCESS, start = 0),
                call("2", 8_000_000, CallOutcome.SUCCESS, start = 12_000_000),
            )
        val summary = NetworkAnalyzer().summarize(calls)
        assertEquals(1, summary.concurrency.peakConcurrency)
    }

    private fun call(
        id: String,
        duration: Long,
        outcome: CallOutcome,
        reused: Boolean = false,
        start: Long? = null,
    ): HttpCall {
        val startedNs = start ?: 0
        val heldPhase = if (reused) {
            listOf(NetworkPhase(NetworkPhaseKind.CONNECTION_HELD, 1_000_000, duration, NetworkConfidence.EXACT))
        } else {
            listOf(
                NetworkPhase(NetworkPhaseKind.DNS, 1_000_000, 2_000_000, NetworkConfidence.EXACT),
                NetworkPhase(NetworkPhaseKind.CONNECT, 2_000_000, 3_000_000, NetworkConfidence.EXACT),
                NetworkPhase(NetworkPhaseKind.TLS, 3_000_000, 5_000_000, NetworkConfidence.EXACT),
            )
        }
        return HttpCall(
            id,
            "GET",
            "https://example.test",
            startedNs,
            startedNs + duration,
            listOf(
                HttpExchange(
                    0,
                    null,
                    "h2",
                    200,
                    10,
                    20,
                    heldPhase + listOf(NetworkPhase(NetworkPhaseKind.TOTAL, startedNs, startedNs + duration, NetworkConfidence.EXACT)),
                    CacheDisposition.HIT,
                    null,
                    connectionReused = reused,
                ),
            ),
            outcome,
            NetworkEvidenceSource.HAR_IMPORT,
        )
    }
}
