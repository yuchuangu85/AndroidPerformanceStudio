package com.androidperformancestudio.benchmark.app

import com.androidperformancestudio.benchmark.presentation.BenchmarkReportsState
import com.androidperformancestudio.ui.UiAiContext
import com.androidperformancestudio.ui.UiAiEvidence
import com.androidperformancestudio.ui.uiAiPayload
import com.androidperformancestudio.ui.uiAiEvidenceId

internal fun benchmarkAiContext(state: BenchmarkReportsState): UiAiContext? {

    val report = state.report ?: return null
    if (state.busy) return null
    val selected = state.selectedComparisonKey?.let { state.selectedComparison ?: return null }
    val visible = state.visibleComparisons()
    val rows = if (selected != null) listOf(selected) else visible.take(8)
    val payload = uiAiPayload(
        "totalComparisons" to report.comparisons.size, "visibleComparisons" to visible.size,
        "includedComparisons" to rows.size, "omittedVisibleComparisons" to if (selected == null) visible.size - rows.size else 0,
        "compatibilityIssueCount" to report.compatibilityIssues.size,
        "queryFilterActive" to state.query.isNotBlank(), "classificationFilter" to state.classificationFilter?.name,
        "comparisons" to rows.map { mapOf("comparisonRef" to uiAiEvidenceId("benchmark-metric", it.caseIdentity, it.metricName), "metric" to it.metricName, "unit" to it.unit, "baseline" to it.baselineValue, "current" to it.currentValue, "absoluteDelta" to it.absoluteDelta, "relativeDeltaPercent" to it.relativeDeltaPercent, "classification" to it.classification.name, "confidence" to it.confidence.name, "reasonCount" to it.reasons.size) },
    )
    val kind = if (selected != null) "benchmark-selection" else "benchmark-summary"
    return UiAiContext("benchmark", "Benchmark comparison evidence", selected != null, listOf(UiAiEvidence(uiAiEvidenceId(kind, report.baselineRunId, report.currentRunId, report.createdAt.toString(), payload), kind, "Comparison units, compatibility and confidence remain explicit", payload)))
}
