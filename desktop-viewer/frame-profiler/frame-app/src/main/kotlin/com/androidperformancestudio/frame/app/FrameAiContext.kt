package com.androidperformancestudio.frame.app

import com.androidperformancestudio.frame.presentation.FrameProfilerState
import com.androidperformancestudio.ui.UiAiContext
import com.androidperformancestudio.ui.UiAiEvidence
import com.androidperformancestudio.ui.uiAiPayload
import com.androidperformancestudio.ui.uiAiEvidenceId

internal fun frameAiContext(state: FrameProfilerState): UiAiContext? {

    val analysis = state.analysis ?: return null
    if (state.isLoading || state.isCapturing || analysis.frames.isEmpty()) return null
    val selected = state.selectedFrameId?.let { id -> analysis.frames.firstOrNull { it.sample.frameId == id } ?: return null }
    val rows = if (selected != null) listOf(selected) else analysis.frames.take(8)
    val payload = uiAiPayload(
        "timeUnit" to "nanoseconds", "totalFrames" to analysis.summary.totalFrames,
        "deadlineMissFrames" to analysis.summary.deadlineMissFrames, "deadlineUnknownFrames" to analysis.summary.deadlineUnknownFrames,
        "platformJankFrames" to analysis.summary.platformJankFrames, "platformUnknownFrames" to analysis.summary.platformUnknownFrames,
        "p95DurationNs" to analysis.summary.p95DurationNs, "includedFrames" to rows.size,
        "omittedFrames" to if (selected == null) (analysis.frames.size - rows.size) else 0,
        "frames" to rows.map { mapOf("frameId" to it.sample.frameId, "durationNs" to it.sample.resolvedDurationNs(), "expectedDurationNs" to it.sample.knownExpectedDurationNs(), "deadlineVerdict" to it.deadlineVerdict.name, "platformJank" to it.sample.platformJank, "droppedBeforeSample" to it.sample.droppedBeforeSample, "source" to it.sample.source.name) },
    )
    val kind = if (selected != null) "frame-selection" else "frame-summary"
    return UiAiContext("frame", "Frame evidence", selected != null, listOf(UiAiEvidence(uiAiEvidenceId(kind, analysis.frames.first().sample.sessionId, payload), kind, "Deadline misses and platform Jank are distinct signals", payload)))
}
