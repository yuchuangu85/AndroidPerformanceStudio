package com.androidperformancestudio.network.capture

import com.androidperformancestudio.network.model.CallOutcome
import com.androidperformancestudio.network.model.ConnectionUse
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
        assertEquals(CallOutcome.COMPLETED, call.outcome)
        assertNotNull(call.exchanges.single().phases.firstOrNull { it.kind == NetworkPhaseKind.SERVER_WAIT })
        assertNull(call.exchanges.single().phases.firstOrNull { it.kind == NetworkPhaseKind.DNS })
    }

    @Test
    fun `keeps redirect exchanges separate and marks repeated connection reused`() {
        val events = listOf(
            event(1, "callStart", 0),
            event(2, "connectionAcquired", 1, connection = "connection-1"),
            event(3, "requestHeadersStart", 2),
            event(4, "requestHeadersEnd", 3),
            event(5, "responseHeadersStart", 4),
            event(6, "responseHeadersEnd", 5, status = 302),
            event(7, "connectionReleased", 6, connection = "connection-1"),
            event(8, "connectionAcquired", 7, connection = "connection-1"),
            event(9, "requestHeadersStart", 8),
            event(10, "requestHeadersEnd", 9),
            event(11, "responseHeadersStart", 10),
            event(12, "responseHeadersEnd", 11, status = 200),
            event(13, "connectionReleased", 12, connection = "connection-1"),
            event(14, "callEnd", 13),
        )
        val call = NetworkEventAssembler().assemble(events).single()
        assertEquals(2, call.exchanges.size)
        assertEquals(ConnectionUse.UNKNOWN, call.exchanges[0].connectionUse)
        assertEquals(ConnectionUse.REUSED, call.exchanges[1].connectionUse)
    }

    @Test
    fun `normalizes device monotonic timestamps to the session origin`() {
        val call = NetworkEventAssembler().assemble(listOf(event(1, "callStart", 100), event(2, "callEnd", 110)), timeOriginNs = 100).single()
        assertEquals(0, call.startedNs)
        assertEquals(10, call.endedNs)
    }

    private fun event(
        sequence: Long,
        kind: String,
        time: Long,
        status: Int? = null,
        connection: String? = null,
    ): AgentNetworkEvent =
        AgentNetworkEvent(
            sequence,
            "call",
            kind,
            time,
            method = "GET",
            url = "https://example.test/path",
            statusCode = status,
            connectionId = connection,
        )
}
