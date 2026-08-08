package com.androidperformancestudio.android.frame

import com.androidperformancestudio.frame.agent.protocol.AgentFrameBatch
import com.androidperformancestudio.frame.agent.protocol.AgentFrameSample
import com.androidperformancestudio.frame.agent.protocol.JANK_STATS_RULE_ID
import com.androidperformancestudio.frame.agent.protocol.JANK_STATS_RULE_VERSION
import java.util.ArrayDeque
import kotlin.math.abs

internal class FrameMetricsStore(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maximumBatchSize: Int = DEFAULT_BATCH_SIZE,
) {
    private val frames = ArrayDeque<StoredFrame>()
    private val pendingJankSignals = ArrayDeque<JankSignal>()
    private var nextSequence = 0L

    init {
        require(capacity > 0) { "Frame buffer capacity must be positive" }
        require(maximumBatchSize > 0) { "Frame batch size must be positive" }
    }

    @Synchronized
    fun add(frame: AgentFrameSample) {
        val sequenced = frame.copy(sequence = nextSequence++)
        val signal = pendingJankSignals.bestMatch(sequenced)
        if (signal != null) pendingJankSignals.remove(signal)
        frames.addLast(StoredFrame(sequenced.withJankSignal(signal)))
        while (frames.size > capacity) frames.removeFirst()
    }

    @Synchronized
    fun annotateJank(
        frameStartNs: Long,
        windowId: String,
        isJank: Boolean,
        states: Map<String, String>,
    ) {
        val signal = JankSignal(frameStartNs, windowId, isJank, states)
        val stored = frames.minByOrNull { it.sample.matchDistance(signal) }
        if (stored != null && stored.sample.matches(signal)) {
            stored.sample = stored.sample.withJankSignal(signal)
        } else {
            pendingJankSignals.addLast(signal)
            while (pendingJankSignals.size > MAX_PENDING_SIGNALS) pendingJankSignals.removeFirst()
        }
    }

    @Synchronized
    fun cursor(): Long = nextSequence - 1L

    @Synchronized
    fun after(cursor: Long): AgentFrameBatch {
        val oldestSequence = frames.firstOrNull()?.sample?.sequence ?: nextSequence
        val dropped = (oldestSequence - cursor - 1L).coerceAtLeast(0L)
        val selected =
            frames
                .asSequence()
                .map(StoredFrame::sample)
                .filter { it.sequence > cursor }
                .take(maximumBatchSize)
                .toList()
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
        const val MAX_PENDING_SIGNALS = 64
        const val MAX_SIGNAL_DELTA_NS = 4_000_000L
    }

    private data class StoredFrame(
        var sample: AgentFrameSample,
    )

    private data class JankSignal(
        val frameStartNs: Long,
        val windowId: String,
        val isJank: Boolean,
        val states: Map<String, String>,
    )

    private fun ArrayDeque<JankSignal>.bestMatch(frame: AgentFrameSample): JankSignal? =
        minByOrNull { signal -> frame.matchDistance(signal) }
            ?.takeIf { signal -> frame.matches(signal) }

    private fun AgentFrameSample.matches(signal: JankSignal): Boolean =
        windowId == signal.windowId && matchDistance(signal) <= MAX_SIGNAL_DELTA_NS

    private fun AgentFrameSample.matchDistance(signal: JankSignal): Long =
        intendedVsyncNs?.let { abs(it - signal.frameStartNs) } ?: Long.MAX_VALUE

    private fun AgentFrameSample.withJankSignal(signal: JankSignal?): AgentFrameSample =
        if (signal == null) {
            this
        } else {
            copy(
                platformJank = signal.isJank,
                platformJankRuleId = JANK_STATS_RULE_ID,
                platformJankRuleVersion = JANK_STATS_RULE_VERSION,
                states = states + signal.states + ("jankStats" to "true"),
            )
        }
}
