package com.androidperformancestudio.perfetto.app

import com.androidperformancestudio.perfetto.analysis.DiagnosticQuery
import com.androidperformancestudio.perfetto.analysis.DiagnosticResult
import com.androidperformancestudio.ui.UiAiContext
import com.androidperformancestudio.ui.UiAiEvidence
import com.androidperformancestudio.ui.uiAiEvidenceId
import com.androidperformancestudio.ui.uiAiPayload

/** Artifact changes invalidate old numeric rows even before asynchronous page cleanup runs. */
internal fun traceAiContextForArtifact(
    activeArtifactId: String?,
    resultArtifactId: String?,
    query: DiagnosticQuery?,
    result: DiagnosticResult?,
): UiAiContext? =
    if (activeArtifactId != null && activeArtifactId == resultArtifactId) {
        traceAiContext(activeArtifactId, query, result)
    } else {
        null
    }

/** Query-bound numeric projection only. Thread names, SQL, arbitrary strings and paths never leave here. */
internal fun traceAiContext(traceIdentity: String?, query: DiagnosticQuery?, result: DiagnosticResult?): UiAiContext? {
    val identity = traceIdentity ?: return null
    val selected = query ?: return null
    val data = result ?: return null
    if (data.columns != selected.columns) return null
    val numericColumns = data.columns.withIndex().filter { (_, name) ->
        name.matches(Regex("[a-z0-9_]+")) && (name.endsWith("_ns") || name.endsWith("_ms") || name.endsWith("_count") || name.endsWith("_bytes") || name in setOf("cpu", "count", "frequency", "value"))
    }
    if (numericColumns.isEmpty()) return null
    val payload = uiAiPayload(
        "queryId" to selected.id,
        "queryCategory" to selected.category.name,
        "returnedRows" to data.rows.size,
        "includedRows" to minOf(20, data.rows.size),
        "omittedReturnedRows" to (data.rows.size - 20).coerceAtLeast(0),
        "queryMayBeLimited" to true,
        "omittedTextColumns" to (data.columns.size - numericColumns.size),
        "units" to numericColumns.associate { (_, name) -> name to when { name.endsWith("_ns") -> "nanoseconds"; name.endsWith("_ms") -> "milliseconds"; name.endsWith("_bytes") -> "bytes"; name.endsWith("_count") || name == "count" -> "count"; else -> "query-defined" } },
        "rows" to data.rows.take(20).map { row -> numericColumns.associate { (index, name) -> name to row.values.getOrNull(index)?.toDoubleOrNull() } },
    )
    return UiAiContext("trace", "Trace diagnostic evidence", true, listOf(UiAiEvidence(uiAiEvidenceId("trace-selection", identity, selected.id, payload), "trace-selection", "Selected diagnostic numeric results; query coverage may be limited", payload)))
}
