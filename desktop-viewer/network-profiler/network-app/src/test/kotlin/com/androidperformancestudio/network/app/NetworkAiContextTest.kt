package com.androidperformancestudio.network.app

import com.androidperformancestudio.network.model.*
import com.androidperformancestudio.network.presentation.NetworkProfilerState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkAiContextTest {
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
