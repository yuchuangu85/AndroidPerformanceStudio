package dev.agentperf.android.frame

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

    private fun frame(durationNs: Long): AgentFrameSample =
        AgentFrameSample(
            sequence = -1,
            packageName = "dev.example",
            totalDurationNs = durationNs,
        )
}
