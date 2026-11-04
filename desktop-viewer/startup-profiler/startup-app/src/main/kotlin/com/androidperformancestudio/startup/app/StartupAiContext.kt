package com.androidperformancestudio.startup.app

import com.androidperformancestudio.startup.presentation.StartupProfilerState
import com.androidperformancestudio.ui.UiAiContext
import com.androidperformancestudio.ui.UiAiEvidence
import com.androidperformancestudio.ui.uiAiPayload
import com.androidperformancestudio.ui.uiAiEvidenceId

internal fun startupAiContext(state: StartupProfilerState): UiAiContext? {

    val analysis = state.analysis ?: return null
    if (state.isRunning || analysis.runs.isEmpty()) return null
    val selected = state.selectedRunId?.let { id -> analysis.runs.firstOrNull { it.id == id } ?: return null }
    val rows = if (selected != null) listOf(selected) else analysis.runs.take(8)
    val payload = uiAiPayload(
        "timeUnit" to "milliseconds", "runCount" to analysis.runs.size, "includedRuns" to rows.size,
        "omittedRuns" to if (selected == null) (analysis.runs.size - rows.size) else 0,
        "ttidMissingCount" to analysis.firstFrame.missingCount, "ttfdMissingCount" to analysis.fullyDrawn.missingCount,
        "runs" to rows.map { mapOf("iteration" to it.iteration, "requestedType" to it.requestedType.name, "observedType" to it.observedType.name, "totalTimeMs" to it.platform.totalTimeMs, "ttidMs" to it.platform.displayedTimeMs, "ttfdMs" to it.platform.fullyDrawnTimeMs, "platformComplete" to it.platform.complete, "warningCount" to it.warnings.size) },
    )
    val kind = if (selected != null) "startup-selection" else "startup-summary"
    return UiAiContext("startup", "Startup evidence", selected != null, listOf(UiAiEvidence(uiAiEvidenceId(kind, analysis.runs.first().sessionId, payload), kind, "TTFD stays missing without reportFullyDrawn; requested mode is not observed mode", payload)))
}
