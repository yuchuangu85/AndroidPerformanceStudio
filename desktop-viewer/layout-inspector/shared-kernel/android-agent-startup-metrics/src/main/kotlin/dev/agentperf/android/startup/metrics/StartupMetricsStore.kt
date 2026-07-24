package dev.agentperf.android.startup.metrics

import com.androidperformancestudio.startup.agent.protocol.AgentEvidenceConfidence
import com.androidperformancestudio.startup.agent.protocol.AgentStartupEvent
import com.androidperformancestudio.startup.agent.protocol.AgentStartupMilestoneKind
import com.androidperformancestudio.startup.agent.protocol.AgentStartupResult

internal class StartupMetricsStore(
    private val packageName: String,
    private val processId: Int,
    private val processName: String?,
    private val apiLevel: Int,
    private val processStartElapsedRealtimeNs: Long?,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val events = ArrayDeque<AgentStartupEvent>()
    private var nextSequence = 0L
    private var droppedEvents = 0L
    private var activeRunId: String? = null

    @Synchronized
    fun arm(runId: String) {
        require(runId.isNotBlank()) { "runId must not be blank" }
        activeRunId = runId
    }

    @Synchronized
    fun associate(runId: String) {
        require(runId.isNotBlank()) { "runId must not be blank" }
        activeRunId = runId
        val updated = events.map { event -> if (event.runId == null) event.copy(runId = runId) else event }
        events.clear()
        events.addAll(updated)
    }

    @Synchronized
    fun add(
        kind: AgentStartupMilestoneKind,
        elapsedRealtimeNs: Long,
        confidence: AgentEvidenceConfidence = AgentEvidenceConfidence.EXACT,
        activityName: String? = null,
        runId: String? = activeRunId,
    ) {
        if (events.any { it.runId == runId && it.kind == kind && it.activityName == activityName }) return
        if (events.size == capacity) {
            events.removeFirst()
            droppedEvents++
        }
        events.addLast(
            AgentStartupEvent(
                sequence = nextSequence++,
                runId = runId,
                kind = kind,
                elapsedRealtimeNs = elapsedRealtimeNs,
                confidence = confidence,
                packageName = packageName,
                activityName = activityName,
                processId = processId,
                processName = processName,
            ),
        )
    }

    @Synchronized
    fun result(runId: String? = null): AgentStartupResult {
        val selected = if (runId == null) events.toList() else events.filter { it.runId == runId }
        return AgentStartupResult(
            runId = runId,
            cursor = nextSequence - 1L,
            events = selected,
            apiLevel = apiLevel,
            processId = processId,
            processStartElapsedRealtimeNs = processStartElapsedRealtimeNs,
            droppedEvents = droppedEvents,
            warnings = if (droppedEvents > 0L) listOf("$droppedEvents startup events were overwritten.") else emptyList(),
        )
    }

    @Synchronized
    fun clear(runId: String) {
        val retained = events.filterNot { it.runId == runId }
        events.clear()
        events.addAll(retained)
        if (activeRunId == runId) activeRunId = null
    }

    internal companion object {
        const val DEFAULT_CAPACITY = 4096
    }
}
