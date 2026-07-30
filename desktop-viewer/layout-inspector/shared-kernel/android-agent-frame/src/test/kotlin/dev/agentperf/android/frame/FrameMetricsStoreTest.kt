package com.androidperformancestudio.android.frame

import com.androidperformancestudio.frame.agent.protocol.AgentFrameSample
import com.androidperformancestudio.frame.agent.protocol.AgentFrameBatchCodec
import com.androidperformancestudio.frame.agent.protocol.AgentFrameRemoteException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FrameMetricsStoreTest {
    @Test
    fun `returns only frames newer than the caller cursor`() {
        val store = FrameMetricsStore(capacity = 4, maximumBatchSize = 4)
        store.add(frame(10))
        val cursor = store.cursor()
        store.add(frame(20))
        store.add(frame(30))

        val batch = store.after(cursor)

        assertEquals(listOf(20L, 30L), batch.frames.map { it.totalDurationNs })
        assertEquals(2L, batch.cursor)
        assertEquals(0L, batch.droppedFrames)
    }

    @Test
    fun `reports frames evicted before a slow consumer could read them`() {
        val store = FrameMetricsStore(capacity = 2, maximumBatchSize = 2)
        repeat(4) { store.add(frame(it.toLong())) }

        val batch = store.after(-1)

        assertEquals(listOf(2L, 3L), batch.frames.map { it.totalDurationNs })
        assertEquals(2L, batch.droppedFrames)
    }

    @Test
    fun `reports an unavailable agent so desktop can fall back below API 24`() {
        val output = ByteArrayOutputStream()
        val extension = FrameMetricsRequestExtension(FrameMetricsStore(), available = false)

        extension.handle("FRAME_CURSOR", emptyList(), output)

        val error =
            assertThrows(AgentFrameRemoteException::class.java) {
                AgentFrameBatchCodec().read(ByteArrayInputStream(output.toByteArray()))
            }
        assertEquals("FRAME_UNAVAILABLE", error.code)
    }

    @Test
    fun `merges JankStats classification that arrives after FrameMetrics`() {
        val store = FrameMetricsStore()
        store.add(frame(durationNs = 20, intendedVsyncNs = 100, windowId = "window:1"))

        store.annotateJank(
            frameStartNs = 100,
            windowId = "window:1",
            isJank = true,
            states = mapOf("scroll" to "feed"),
        )

        val sample = store.after(-1).frames.single()
        assertEquals(true, sample.platformJank)
        assertEquals("feed", sample.states["scroll"])
    }

    @Test
    fun `merges JankStats classification that arrives before FrameMetrics`() {
        val store = FrameMetricsStore()
        store.annotateJank(
            frameStartNs = 200,
            windowId = "window:2",
            isJank = false,
            states = mapOf("screen" to "details"),
        )

        store.add(frame(durationNs = 8, intendedVsyncNs = 200, windowId = "window:2"))

        val sample = store.after(-1).frames.single()
        assertEquals(false, sample.platformJank)
        assertEquals("details", sample.states["screen"])
    }

    private fun frame(
        durationNs: Long,
        intendedVsyncNs: Long? = null,
        windowId: String? = null,
    ): AgentFrameSample =
        AgentFrameSample(
            sequence = -1,
            packageName = "dev.example",
            totalDurationNs = durationNs,
            intendedVsyncNs = intendedVsyncNs,
            windowId = windowId,
        )
}
