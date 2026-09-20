package com.androidperformancestudio.memory.leak.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class LeakCanaryAgentProtocolTest {
    @Test
    fun `command and response round trip preserve session cursor and events`() {
        val command = LeakCanaryAgentCommand(type = "POLL", token = "token", cursor = 12L, maxEvents = 17)
        val commandBytes = ByteArrayOutputStream()
        LeakCanaryAgentCodec.writeCommand(commandBytes, command)
        assertEquals(command, LeakCanaryAgentCodec.readCommand(ByteArrayInputStream(commandBytes.toByteArray())))

        val response =
            LeakCanaryAgentResponse(
                type = "EVENTS",
                packageName = "com.example",
                sessionId = "session-1",
                latestSequence = 13L,
                events =
                    listOf(
                        LeakCanaryAgentEvent(
                            sequence = 13L,
                            kind = "ACTIVITY_DESTROYED",
                            monotonicNs = 99L,
                            packageName = "com.example",
                            className = "com.example.MainActivity",
                        ),
                    ),
            )
        val responseBytes = ByteArrayOutputStream()
        LeakCanaryAgentCodec.writeResponse(responseBytes, response)
        assertEquals(response, LeakCanaryAgentCodec.readResponse(ByteArrayInputStream(responseBytes.toByteArray())))
    }
}
