package dev.agentperf.android.frame

import com.androidperformancestudio.frame.agent.protocol.AgentFrameBatch
import com.androidperformancestudio.frame.agent.protocol.AgentFrameSample
import java.util.ArrayDeque

internal class FrameMetricsStore(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maximumBatchSize: Int = DEFAULT_BATCH_SIZE,
) {
    private val frames = ArrayDeque<AgentFrameSample>()
    private var nextSequence = 0L

    init {
        require(capacity > 0) { "Frame buffer capacity must be positive" }
        require(maximumBatchSize > 0) { "Frame batch size must be positive" }
    }

    @Synchronized
    fun add(frame: AgentFrameSample) {
        frames.addLast(frame.copy(sequence = nextSequence++))
        while (frames.size > capacity) frames.removeFirst()
    }

    @Synchronized
    fun cursor(): Long = nextSequence - 1L

    @Synchronized
    fun after(cursor: Long): AgentFrameBatch {
        val oldestSequence = frames.firstOrNull()?.sequence ?: nextSequence
        val dropped = (oldestSequence - cursor - 1L).coerceAtLeast(0L)
        val selected = frames.asSequence().filter { it.sequence > cursor }.take(maximumBatchSize).toList()
        val responseCursor = selected.lastOrNull()?.sequence ?: cursor()
        return AgentFrameBatch(
            cursor = responseCursor,
            frames = selected,
            droppedFrames = dropped,
        )
    }

    private companion object {
        const val DEFAULT_CAPACITY = 6_000
        const val DEFAULT_BATCH_SIZE = 512
    }
}
