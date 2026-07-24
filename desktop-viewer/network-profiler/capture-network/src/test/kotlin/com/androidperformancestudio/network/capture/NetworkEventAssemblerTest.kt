package com.androidperformancestudio.network.capture

import com.androidperformancestudio.network.model.CallOutcome
import com.androidperformancestudio.network.model.NetworkPhaseKind
import com.androidperformancestudio.network.protocol.AgentNetworkEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NetworkEventAssemblerTest {
    @Test
    fun `assembles request while preserving unavailable reused connection phases`() {
        val events =
            listOf(
                event(1, "callStart", 0),
                event(2, "requestHeadersStart", 1),
                event(3, "requestHeadersEnd", 2),
                event(4, "responseHeadersStart", 5),
                event(5, "responseHeadersEnd", 6, status = 200),
                event(6, "callEnd", 8),
            )
        val call = NetworkEventAssembler().assemble(events).single()
        assertEquals(CallOutcome.SUCCESS, call.outcome)
        assertNotNull(call.exchanges.single().phases.firstOrNull { it.kind == NetworkPhaseKind.SERVER_WAIT })
        assertNull(call.exchanges.single().phases.firstOrNull { it.kind == NetworkPhaseKind.DNS })
    }

    private fun event(
        sequence: Long,
        kind: String,
        time: Long,
        status: Int? = null,
    ): AgentNetworkEvent =
        AgentNetworkEvent(
            sequence,
            "call",
            kind,
            time,
            method = "GET",
            url = "https://example.test/path",
            statusCode = status,
        )
}
