package com.androidperformancestudio.desktop

import com.androidperformancestudio.ui.UiAiContext
import com.androidperformancestudio.ui.UiAiEvidence
import com.androidperformancestudio.ui.uiAiEvidenceId

/** Reuses the bounded/redacted layout projection, never screenshots or View runtime text. */
internal fun layoutUiAiContext(snapshotIdentity: String, input: AiAnalysisInput?): UiAiContext? {
    if (input == null || input.treeTruncated || input.omittedSourceEvidenceCount > 0 || input.json.length > 65_536) return null
    val id = uiAiEvidenceId("layout-snapshot", snapshotIdentity, input.selectedNodeId.orEmpty(), input.json)
    return UiAiContext(
        "layout-inspector",
        if (input.selectedNodeId == null) "Layout report summary" else "Layout selected node",
        input.selectedNodeId != null,
        listOf(UiAiEvidence(id, "layout-snapshot", "Bounded layout metrics and rules; runtime text and screenshots excluded", input.json)),
    )
}
