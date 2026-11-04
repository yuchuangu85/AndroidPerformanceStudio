package com.androidperformancestudio.battery.app

import com.androidperformancestudio.battery.presentation.BatteryProfilerState
import com.androidperformancestudio.ui.UiAiContext
import com.androidperformancestudio.ui.UiAiEvidence
import com.androidperformancestudio.ui.uiAiPayload
import com.androidperformancestudio.ui.uiAiEvidenceId

internal fun batteryAiContext(state: BatteryProfilerState): UiAiContext? {

    val analysis = state.analysis ?: return null
    if (state.isRunning || state.isInteractiveActive || state.isLoadingSession || analysis.runs.isEmpty()) return null
    val selected = state.selectedRunId?.let { id -> analysis.runs.firstOrNull { it.runId == id } ?: return null }
    val rows = if (selected != null) listOf(selected) else analysis.runs.take(4)
    val payload = uiAiPayload(
        "durationUnit" to "milliseconds", "networkUnit" to "bytes", "energyUnit" to "mAh or microWattSeconds as declared",
        "runCount" to analysis.runs.size, "includedRuns" to rows.size, "omittedRuns" to if (selected == null) analysis.runs.size - rows.size else 0,
        "runs" to rows.map { run -> mapOf("iteration" to run.iteration, "durationMs" to run.durationMs, "networkBytes" to if (run.networkCountersAvailable) run.networkTotalBytes else null, "networkCountersAvailable" to run.networkCountersAvailable, "peakTemperatureCelsius" to run.peakTemperatureCelsius, "maxThermalStatus" to run.maxThermalStatus, "warningCount" to run.warnings.size, "energyEntryCount" to run.energy.size, "energy" to run.energy.take(8).map { mapOf("mAh" to it.energyMah, "microWattSeconds" to it.energyUws, "source" to it.source.name, "attributionScope" to it.attributionScope.name, "confidence" to it.confidence.name) }, "omittedEnergyEntries" to (run.energy.size - 8).coerceAtLeast(0)) },
    )
    val kind = if (selected != null) "battery-selection" else "battery-summary"
    return UiAiContext("battery", "Battery evidence", selected != null, listOf(UiAiEvidence(uiAiEvidenceId(kind, analysis.runs.first().sessionId, payload), kind, "Energy source, attribution scope and missing counters remain explicit", payload)))
}
