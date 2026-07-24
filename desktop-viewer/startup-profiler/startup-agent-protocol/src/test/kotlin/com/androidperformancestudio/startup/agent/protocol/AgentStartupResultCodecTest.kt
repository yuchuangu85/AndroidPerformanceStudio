package com.androidperformancestudio.startup.agent.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentStartupResultCodecTest {
    @Test
    fun `round trips startup events`() {
        val expected =
            AgentStartupResult(
                runId = "run-1",
                cursor = 2,
                events =
                    listOf(
                        AgentStartupEvent(
                            sequence = 2,
                            runId = "run-1",
                            kind = AgentStartupMilestoneKind.FIRST_FRAME,
                            elapsedRealtimeNs = 42,
                            packageName = "dev.sample",
                        ),
                    ),
            )
        val output = ByteArrayOutputStream()

        AgentStartupResultCodec().write(expected, output)

        assertEquals(expected, AgentStartupResultCodec().read(ByteArrayInputStream(output.toByteArray())))
    }

    @Test
    fun `rejects oversized declared payload`() {
        assertFailsWith<AgentStartupProtocolException> {
            AgentStartupResultCodec().read(
                ByteArrayInputStream("STARTUP ${AgentStartupResultCodec.MAX_RESULT_BYTES + 1}\n".toByteArray()),
            )
        }
    }
}
