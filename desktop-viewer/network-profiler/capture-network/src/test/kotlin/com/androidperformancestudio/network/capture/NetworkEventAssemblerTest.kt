package com.androidperformancestudio.network.capture

import com.androidperformancestudio.network.model.CallOutcome
import com.androidperformancestudio.network.model.ConnectionUse
import com.androidperformancestudio.network.model.NetworkPhaseKind
import com.androidperformancestudio.network.protocol.AgentNetworkEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        // Connection reused: no DNS, CONNECT, or TLS phases
        assertEquals(ConnectionUse.REUSED, call.exchanges.single().connectionUse)
    }

    @Test
    fun `assembles call with dispatcher queue phase`() {
        val events =
            listOf(
                event(1, "callStart", 0),
                event(2, "dnsStart", 5_000_000),
                event(3, "dnsEnd", 10_000_000),
                event(4, "requestHeadersStart", 11_000_000),
                event(5, "requestHeadersEnd", 12_000_000),
                event(6, "responseHeadersStart", 20_000_000),
                event(7, "responseHeadersEnd", 21_000_000, status = 200),
                event(8, "callEnd", 22_000_000),
            )
        val call = NetworkEventAssembler().assemble(events).single()
        val queue = call.exchanges.single().phases.firstOrNull { it.kind == NetworkPhaseKind.DISPATCHER_QUEUE }
        assertNotNull(queue, "DISPATCHER_QUEUE phase should be present")
        assertEquals(0, queue.startNs)
        assertEquals(5_000_000, queue.endNs)
    }

    @Test
    fun `detects connection not reused when TLS phase is present`() {
        val events =
            listOf(
                event(1, "callStart", 0),
                event(2, "dnsStart", 1_000_000),
                event(3, "dnsEnd", 3_000_000),
                event(4, "connectStart", 3_000_000),
                event(5, "secureConnectStart", 5_000_000),
                event(6, "secureConnectEnd", 8_000_000, tlsVersion = "TLSv1.3"),
                event(7, "connectEnd", 9_000_000),
                event(8, "requestHeadersStart", 10_000_000),
                event(9, "requestHeadersEnd", 11_000_000),
                event(10, "responseHeadersStart", 20_000_000),
                event(11, "responseHeadersEnd", 21_000_000, status = 200),
                event(12, "callEnd", 22_000_000),
            )
        val call = NetworkEventAssembler().assemble(events).single()
        assertEquals(ConnectionUse.NEW, call.exchanges.single().connectionUse)
        assertNotNull(call.exchanges.single().tlsHandshake)
        assertEquals("TLSv1.3", call.exchanges.single().tlsHandshake?.tlsVersion)
    }

    @Test
    fun `assembles connection held phase from acquire and release events`() {
        val events =
            listOf(
                event(1, "callStart", 0),
                event(2, "connectionAcquired", 1, connection = "conn-1"),
                event(3, "requestHeadersStart", 1_000_000),
                event(4, "requestHeadersEnd", 2_000_000),
                event(5, "responseHeadersStart", 5_000_000),
                event(6, "responseHeadersEnd", 6_000_000, status = 200),
                event(7, "connectionReleased", 7_000_000, connection = "conn-1"),
                event(8, "callEnd", 8_000_000),
            )
        val call = NetworkEventAssembler().assemble(events).single()
        val held = call.exchanges.single().phases.firstOrNull { it.kind == NetworkPhaseKind.CONNECTION_HELD }
        assertNotNull(held, "CONNECTION_HELD phase should be present")
        assertEquals(1, held.startNs)
        assertEquals(7_000_000, held.endNs)
        assertEquals(ConnectionUse.REUSED, call.exchanges.single().connectionUse)
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
        assertEquals(ConnectionUse.REUSED, call.exchanges[0].connectionUse)
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
    tlsVersion: String? = null,
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
        tlsVersion = tlsVersion,
        connectionId = connection,
    )
}
