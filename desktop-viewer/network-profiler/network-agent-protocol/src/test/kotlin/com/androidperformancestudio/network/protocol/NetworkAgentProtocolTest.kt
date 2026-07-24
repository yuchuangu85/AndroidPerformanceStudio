package com.androidperformancestudio.network.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkAgentProtocolTest {
    @Test fun `round trips bounded response frame`() {
        val out = ByteArrayOutputStream()
        val expected = AgentResponse("EVENTS", events = listOf(AgentNetworkEvent(1, "c", "callStart", 2, method = "GET", url = "https://example.test/")))
        NetworkAgentCodec.writeResponse(out, expected)
        assertEquals(expected, NetworkAgentCodec.readResponse(ByteArrayInputStream(out.toByteArray())))
    }
}
