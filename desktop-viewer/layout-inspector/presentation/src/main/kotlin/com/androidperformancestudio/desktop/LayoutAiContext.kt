package com.androidperformancestudio.desktop

import com.androidperformancestudio.ui.UiAiContext
import com.androidperformancestudio.ui.UiAiEvidence
import com.androidperformancestudio.ui.uiAiEvidenceId

/** Reuses the bounded/redacted layout projection, never screenshots or View runtime text. */
internal fun layoutUiAiContext(snapshotIdentity: String, input: AiAnalysisInput?): UiAiContext? {
    if (input == null || input.json.encodeToByteArray().size > 65_536) return null
    val partial = input.hasBoundedSummary
    if ((input.treeTruncated || input.omittedSourceEvidenceCount > 0) && !partial) return null
    val id = uiAiEvidenceId("layout-snapshot", snapshotIdentity, input.selectedNodeId.orEmpty(), input.json)
    val title = if (input.selectedNodeId == null) "Layout report summary" else "Layout selected node"
    val summary = "Bounded layout metrics and rules; runtime text and screenshots excluded"
    return UiAiContext(
        "layout-inspector",
        if (partial) "Partial/incomplete: $title" else title,
        input.selectedNodeId != null,
        listOf(UiAiEvidence(id, "layout-snapshot", if (partial) "Partial/incomplete layout (included nodes only). $summary" else summary, input.json)),
    )
}
