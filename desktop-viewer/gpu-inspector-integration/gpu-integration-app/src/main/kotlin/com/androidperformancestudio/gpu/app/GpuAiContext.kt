package com.androidperformancestudio.gpu.app

import com.androidperformancestudio.gpu.presentation.GpuIntegrationState
import com.androidperformancestudio.ui.UiAiContext
import com.androidperformancestudio.ui.UiAiEvidence
import com.androidperformancestudio.ui.uiAiPayload
import com.androidperformancestudio.ui.uiAiEvidenceId

internal fun gpuAiContext(state: GpuIntegrationState): UiAiContext? {

    if (state.isBusy || state.artifacts.isEmpty()) return null
    val selected = state.selectedArtifactId?.let { id -> state.artifacts.firstOrNull { it.id == id } ?: return null }
    val rows = if (selected != null) listOf(selected) else state.artifacts.take(8)
    val payload = uiAiPayload(
        "scope" to "artifact-metadata-only", "gpuTimingMetricsAvailable" to false,
        "artifactCount" to state.artifacts.size, "includedArtifacts" to rows.size, "omittedArtifacts" to if (selected == null) state.artifacts.size - rows.size else 0,
        "artifacts" to rows.map { mapOf("contentSha256" to it.sha256, "sizeBytes" to it.sizeBytes, "kind" to it.kind.name, "graphicsApi" to it.graphicsApi?.name, "warningCount" to it.warnings.size) },
    )
    val kind = if (selected != null) "gpu-selection" else "gpu-summary"
    return UiAiContext("gpu", "GPU artifact evidence", selected != null, listOf(UiAiEvidence(uiAiEvidenceId(kind, rows.joinToString("|") { it.sha256 }, payload), kind, "Artifact identity metadata only; no invented GPU timings", payload)))
}
