package com.androidperformancestudio.frame.agent.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentFrameBatchCodecTest {
    private val codec = AgentFrameBatchCodec()

    @Test
    fun `round trips a bounded frame batch`() {
        val expected =
            AgentFrameBatch(
                cursor = 7,
                frames =
                    listOf(
                        AgentFrameSample(
                            sequence = 7,
                            packageName = "dev.example",
                            totalDurationNs = 12_000_000,
                            refreshRateHz = 120.0,
                            frameTimelineVsyncId = 99,
                            stages = AgentFrameStages(drawNs = 4_000_000),
                        ),
                    ),
                droppedFrames = 2,
            )
        val output = ByteArrayOutputStream()

        codec.write(expected, output)

        assertEquals(expected, codec.read(ByteArrayInputStream(output.toByteArray())))
    }

    @Test
    fun `surfaces stable remote errors`() {
        val error =
            assertFailsWith<AgentFrameRemoteException> {
                codec.read(ByteArrayInputStream("ERROR FRAME_UNAVAILABLE API 24 required\n".toByteArray()))
            }

        assertEquals("FRAME_UNAVAILABLE", error.code)
    }

    @Test
    fun `parses the existing agent session descriptor`() {
        val descriptor =
            AgentSessionDescriptor.parse(
                """{"protocolMajor":1,"protocolMinor":0,"socketName":"agentperf.dev_example","token":"secret"}""",
            )

        assertEquals("agentperf.dev_example", descriptor.socketName)
        assertEquals("secret", descriptor.token)
    }
}
