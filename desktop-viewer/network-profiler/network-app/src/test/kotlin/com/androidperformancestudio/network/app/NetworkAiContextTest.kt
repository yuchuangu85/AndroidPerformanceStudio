package com.androidperformancestudio.network.app

import com.androidperformancestudio.network.model.*
import com.androidperformancestudio.network.presentation.NetworkProfilerState
import com.androidperformancestudio.ui.boundedAiContext
import com.androidperformancestudio.ui.uiAiEvidenceId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NetworkAiContextTest {
    @Test
    fun `large reports use deterministic longest six calls with bounded detail and omissions`() {
        val coverage = NetworkCoverage(emptySet(), emptySet(), emptySet(), InstrumentationMode.HAR_IMPORT, emptySet(), setOf("partial"), null, null)
        val completeness = NetworkEvidenceCompleteness(EvidenceCompleteness.PARTIAL, 2, 1, 3, 4)
        val session = NetworkSession("session-1", "private-serial", "private-package", Instant.EPOCH, null, coverage, completeness, NetworkTimeDomain.SESSION_RELATIVE, 0, null, NetworkSessionStatus.PARTIAL, 1)
        val phases = List(20) {
            NetworkPhase(NetworkPhaseKind.RESPONSE_HEADERS, null, null, NetworkConfidence.APPROXIMATED, Long.MAX_VALUE, TimingAvailability.NOT_APPLICABLE)
        }
        val exchanges = List(20) { index ->
            HttpExchange(index, null, ConnectionUse.UNKNOWN, null, 200 + index, null, null, null, phases, CacheDisposition.UNKNOWN, null)
        }
        val calls = (1..20).map { index ->
            HttpCall("call-$index", null, "GET", "https://private/token=secret", 0, index.toLong() / 2, exchanges, CallOutcome.COMPLETED, NetworkEvidenceSource.OKHTTP_EVENT_LISTENER)
        } + HttpCall("call-unknown", null, "GET", "https://private/token=secret", 0, null, exchanges, CallOutcome.INCOMPLETE, NetworkEvidenceSource.HAR_IMPORT)
        val state = NetworkProfilerState(result = NetworkCaptureResult(session, calls))
        val context = assertNotNull(networkAiContext(state))
        val payload = context.evidence.single().payloadJson

        assertFalse(context.currentSelection)
        assertTrue("\"callCount\":21" in payload)
        assertTrue("\"includedCalls\":6" in payload)
        assertTrue("\"omittedCalls\":15" in payload)
        assertEquals(6, Regex("\"exchangeCount\":20").findAll(payload).count())
        assertEquals(6, Regex("\"statusCodes\":\\[200,201,202,203]").findAll(payload).count())
        assertEquals(6, Regex("\"omittedExchanges\":16").findAll(payload).count())
        assertEquals(24, Regex("\"kind\":\"RESPONSE_HEADERS\"").findAll(payload).count())
        assertEquals(6, Regex("\"omittedPhases\":396").findAll(payload).count())
        assertFalse("private" in payload)
        assertTrue(payload.toByteArray(Charsets.UTF_8).size <= 8 * 1024)
        assertSame(context, boundedAiContext(context))

        val expectedIds = listOf("call-20", "call-18", "call-19", "call-16", "call-17", "call-14")
        val positions = expectedIds.map { payload.indexOf(uiAiEvidenceId("network-call", session.id, it)) }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
        assertEquals(context, networkAiContext(state.copy(result = NetworkCaptureResult(session, calls.reversed()))))

        val selected = assertNotNull(networkAiContext(state.copy(selectedCallId = "call-1")))
        assertTrue(selected.currentSelection)
        assertTrue("\"includedCalls\":1" in selected.evidence.single().payloadJson)
        assertTrue("\"omittedCalls\":0" in selected.evidence.single().payloadJson)
        assertSame(selected, boundedAiContext(selected))
    }

    @Test
    fun `network metrics exclude credentials URL headers body and preserve incompleteness`() {
        val coverage = NetworkCoverage(emptySet(), emptySet(), emptySet(), InstrumentationMode.HAR_IMPORT, emptySet(), setOf("partial"), null, null)
        val completeness = NetworkEvidenceCompleteness(EvidenceCompleteness.PARTIAL, 2, 1, 3, 4)
        val session = NetworkSession("session-1", "private-serial", "private-package", Instant.EPOCH, null, coverage, completeness, NetworkTimeDomain.SESSION_RELATIVE, 0, null, NetworkSessionStatus.PARTIAL, 1)
        val call = HttpCall("call-1", null, "GET", "https://private/token=secret", 1, 101, emptyList(), CallOutcome.FAILED, NetworkEvidenceSource.HAR_IMPORT)
        val state = NetworkProfilerState(result = NetworkCaptureResult(session, listOf(call)), selectedCallId = call.callId)
        val context = networkAiContext(state)!!
        val payload = context.evidence.single().payloadJson
        assertTrue(context.currentSelection)
        assertTrue("\"durationNs\":100" in payload)
        assertTrue("\"droppedEvents\":2" in payload)
        assertFalse("private" in payload)
        assertFalse("token" in payload)
        assertEquals(null, networkAiContext(state.copy(selectedCallId = "stale")))
    }
}
