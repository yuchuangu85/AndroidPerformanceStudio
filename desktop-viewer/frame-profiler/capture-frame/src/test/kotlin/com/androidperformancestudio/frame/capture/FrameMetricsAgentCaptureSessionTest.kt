package com.androidperformancestudio.frame.capture

import com.androidperformancestudio.frame.agent.protocol.AgentExpectedDurationSource
import com.androidperformancestudio.frame.agent.protocol.AgentFrameBatch
import com.androidperformancestudio.frame.agent.protocol.AgentFrameSample
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameMetricsAgentCaptureSessionTest {
    @Test
    fun `starts from the agent cursor and maps incremental frames`() =
        runBlocking {
            val transport =
                FakeAgentFrameTransport(
                    initial = AgentFrameBatch(cursor = 40, warnings = listOf("agent warning")),
                    polled =
                        AgentFrameBatch(
                            cursor = 42,
                            frames =
                                listOf(
                                    AgentFrameSample(
                                        sequence = 41,
                                        packageName = "dev.example",
                                        intendedVsyncNs = 1_000,
                                        totalDurationNs = 9_000_000,
                                        expectedDurationNs = 8_333_333,
                                        expectedDurationSource = AgentExpectedDurationSource.REFRESH_RATE,
                                        refreshRateHz = 120.0,
                                        frameTimelineVsyncId = 99,
                                    ),
                                    AgentFrameSample(
                                        sequence = 42,
                                        packageName = "dev.example",
                                        totalDurationNs = 20_000_000,
                                    ),
                                ),
                            droppedFrames = 3,
                        ),
                )
            val session =
                FrameMetricsAgentCaptureSession(
                    target = GfxInfoCaptureTarget("device", "dev.example", 123),
                    sessionId = "session",
                    transport = transport,
                )

            assertEquals(listOf("agent warning"), session.start())
            val batch = session.poll()

            assertEquals(40L, transport.requestedCursor)
            assertEquals(listOf(0L, 1L), batch.frames.map { it.frameId })
            assertEquals(123, batch.frames.first().processId)
            assertEquals(3L, batch.frames.first().droppedBeforeSample)
            assertEquals(120.0, batch.frames.first().refreshRateHz)
            assertEquals(99L, batch.frames.first().frameTimelineVsyncId)
            assertEquals(0L, batch.frames.last().droppedBeforeSample)
            assertEquals(
                "REFRESH_RATE",
                batch.frames
                    .first()
                    .expectedDurationSource.name,
            )
            assertEquals(1, batch.warnings.size)
        }

    private class FakeAgentFrameTransport(
        private val initial: AgentFrameBatch,
        private val polled: AgentFrameBatch,
    ) : AgentFrameTransport {
        var requestedCursor: Long? = null

        override suspend fun start(): AgentFrameBatch = initial

        override suspend fun fetch(afterCursor: Long): AgentFrameBatch {
            requestedCursor = afterCursor
            return polled
        }

        override suspend fun stop(): List<String> = emptyList()
    }
}
