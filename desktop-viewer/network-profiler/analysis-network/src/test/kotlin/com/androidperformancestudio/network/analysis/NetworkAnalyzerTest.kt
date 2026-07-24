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

    private fun call(id: String, duration: Long, outcome: CallOutcome): HttpCall =
        HttpCall(
            id,
            "GET",
            "https://example.test",
            0,
            duration,
            listOf(
                HttpExchange(
                    0,
                    null,
                    "h2",
                    200,
                    10,
                    20,
                    listOf(NetworkPhase(NetworkPhaseKind.TOTAL, 0, duration, NetworkConfidence.EXACT)),
                    CacheDisposition.HIT,
                    null,
                ),
            ),
            outcome,
            NetworkEvidenceSource.HAR_IMPORT,
        )
}
