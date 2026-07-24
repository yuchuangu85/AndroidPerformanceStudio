package com.androidperformancestudio.frame.app

import com.androidperformancestudio.frame.capture.GfxInfoPollBatch
import com.androidperformancestudio.frame.model.FrameCaptureSession
import com.androidperformancestudio.frame.model.FrameSource
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentPreferredOnlineFrameCaptureTest {
    @Test
    fun `uses FrameMetrics agent when its handshake succeeds`() =
        runBlocking {
            val agent = FakeCapture(FrameSource.FRAME_METRICS)
            val fallback = FakeCapture(FrameSource.GFXINFO)
            val capture = AgentPreferredOnlineFrameCapture(agent, fallback)

            capture.start()
            capture.poll()
            capture.stop()

            assertEquals(FrameSource.FRAME_METRICS, capture.metadata.source)
            assertEquals(1, agent.pollCalls)
            assertEquals(1, agent.stopCalls)
            assertEquals(0, fallback.startCalls)
        }

    @Test
    fun `falls back to gfxinfo when agent handshake fails`() =
        runBlocking {
            val agent = FakeCapture(FrameSource.FRAME_METRICS, startFailure = "session file missing")
            val fallback = FakeCapture(FrameSource.GFXINFO, startWarnings = listOf("reset warning"))
            val capture = AgentPreferredOnlineFrameCapture(agent, fallback)

            val warnings = capture.start()
            capture.poll()
            capture.stop()

            assertEquals(FrameSource.GFXINFO, capture.metadata.source)
            assertTrue(warnings.first().contains("using gfxinfo polling"))
            assertEquals("reset warning", warnings.last())
            assertEquals(1, fallback.pollCalls)
            assertEquals(1, fallback.stopCalls)
        }

    private class FakeCapture(
        source: FrameSource,
        private val startFailure: String? = null,
        private val startWarnings: List<String> = emptyList(),
    ) : OnlineFrameCapture {
        var startCalls = 0
        var pollCalls = 0
        var stopCalls = 0

        override val metadata =
            FrameCaptureSession(
                id = "session",
                source = source,
                startedAt = Instant.EPOCH,
                packageName = "dev.example",
                deviceSerial = "device",
            )

        override suspend fun start(): List<String> {
            startCalls += 1
            startFailure?.let { throw IllegalStateException(it) }
            return startWarnings
        }

        override suspend fun poll(): GfxInfoPollBatch {
            pollCalls += 1
            return GfxInfoPollBatch(emptyList())
        }

        override suspend fun stop(): List<String> {
            stopCalls += 1
            return emptyList()
        }
    }
}
