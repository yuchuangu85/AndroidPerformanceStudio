package com.androidperformancestudio.frame.model

import java.time.Instant

public enum class FrameSource {
    JANK_STATS,
    FRAME_METRICS,
    GFXINFO,
    PERFETTO,
}

public enum class ExpectedDurationSource {
    PLATFORM_DEADLINE,
    FRAME_INTERVAL,
    REFRESH_RATE,
    INFERRED_VSYNC,
    UNKNOWN,
}

public enum class JankType {
    PLATFORM_REPORTED,
    DEADLINE_MISSED,
    SLOW_INPUT,
    SLOW_ANIMATION,
    SLOW_LAYOUT,
    SLOW_DRAW,
    SLOW_SYNC,
    SLOW_COMMAND,
    SLOW_SWAP,
    UNKNOWN,
}

public data class FrameStages(
    val inputNs: Long? = null,
    val animationNs: Long? = null,
    val layoutMeasureNs: Long? = null,
    val drawNs: Long? = null,
    val syncNs: Long? = null,
    val commandIssueNs: Long? = null,
    val swapBuffersNs: Long? = null,
    val gpuNs: Long? = null,
) {
    public fun values(): List<Pair<String, Long>> =
        listOfNotNull(
            inputNs?.let { "Input" to it },
            animationNs?.let { "Animation" to it },
            layoutMeasureNs?.let { "Layout/Measure" to it },
            drawNs?.let { "Draw" to it },
            syncNs?.let { "Sync" to it },
            commandIssueNs?.let { "Command" to it },
            swapBuffersNs?.let { "Swap" to it },
            gpuNs?.let { "GPU" to it },
        )
}

public data class FrameSample(
    val frameId: Long,
    val sessionId: String,
    val source: FrameSource,
    val packageName: String? = null,
    val processId: Int? = null,
    val activityName: String? = null,
    val windowId: String? = null,
    val intendedVsyncNs: Long? = null,
    val actualVsyncNs: Long? = null,
    val frameCompletedNs: Long? = null,
    val presentNs: Long? = null,
    val expectedDurationNs: Long? = null,
    val expectedDurationSource: ExpectedDurationSource = ExpectedDurationSource.UNKNOWN,
    val refreshRateHz: Double? = null,
    val frameTimelineVsyncId: Long? = null,
    val totalDurationNs: Long? = null,
    val stages: FrameStages = FrameStages(),
    val platformJank: Boolean? = null,
    val platformJankTypes: Set<JankType> = emptySet(),
    val platformJankRuleId: String? = null,
    val platformJankRuleVersion: String? = null,
    val states: Map<String, String> = emptyMap(),
    val eligibleForJank: Boolean = true,
    val droppedBeforeSample: Long = 0,
    val layoutSnapshotId: String? = null,
) {
    public fun resolvedDurationNs(): Long? =
        totalDurationNs?.takeIf { it >= 0L }
            ?: difference(frameCompletedNs ?: presentNs, intendedVsyncNs)

    private fun difference(
        endNs: Long?,
        startNs: Long?,
    ): Long? = if (endNs != null && startNs != null && endNs >= startNs) endNs - startNs else null
}

public data class FrameCaptureSession(
    val id: String,
    val source: FrameSource,
    val startedAt: Instant,
    val packageName: String? = null,
    val deviceSerial: String? = null,
    val deviceApiLevel: Int? = null,
    val agentProtocol: String? = null,
    val sourceCapabilities: FrameSourceCapabilities? = null,
    val observedRefreshRatesHz: Set<Double> = emptySet(),
    val importedFile: String? = null,
    val importedFileSha256: String? = null,
    val importedAt: Instant? = null,
    val provenanceComplete: Boolean = true,
    val provenanceWarnings: List<String> = emptyList(),
    val perfettoTraceFile: String? = null,
)

public data class FrameSourceCapabilities(
    val realtime: Boolean,
    val stageBreakdown: Boolean,
    val platformJankClassification: Boolean,
    val expectedFrameDeadline: Boolean,
    val appStateLabels: Boolean,
)
