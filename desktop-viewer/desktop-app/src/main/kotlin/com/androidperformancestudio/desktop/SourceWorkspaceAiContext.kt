package com.androidperformancestudio.desktop

import com.androidperformancestudio.source.SourceFile
import com.androidperformancestudio.source.SourceLocation
import com.androidperformancestudio.source.SourceSnapshot
import com.androidperformancestudio.source.VerifiedSourceContent
import com.androidperformancestudio.ui.UiAiContext
import com.androidperformancestudio.ui.UiAiEvidence
import com.androidperformancestudio.ui.uiAiEvidenceId
import com.androidperformancestudio.ui.uiAiPayload

internal fun sourceWorkspaceAiContext(
    selectedLocation: SourceLocation?,
    content: VerifiedSourceContent?,
    snapshot: SourceSnapshot?,
    file: SourceFile?,
): UiAiContext? {
    val location = selectedLocation ?: return null
    if (content == null || snapshot == null || file == null) return null
    if (content.location != location || snapshot.id != location.snapshotId || snapshot.workspaceId != location.workspaceId) return null
    if (file.snapshotId != location.snapshotId || file.relativePath != location.relativePath || file.contentHash != location.contentHash) return null
    if (!location.contentHash.matches(Regex("[a-f0-9]{64}")) || file.sizeBytes < 0) return null

    val lineCount = content.text.lineSequence().count()
    val range = location.range
    if (range != null && (range.startLine < 1 || range.endLine < range.startLine || range.endLine > lineCount)) return null
    val kind = "source-workspace-selection"
    val payload = uiAiPayload(
        "evidenceType" to "source-metadata",
        "performanceMeasurement" to false,
        "snapshotId" to uiAiEvidenceId("source-snapshot", snapshot.id.value),
        "contentHash" to location.contentHash,
        "language" to file.language.name,
        "contentState" to content.state.name,
        "sizeBytes" to file.sizeBytes,
        "lineCount" to lineCount,
        "startLine" to range?.startLine,
        "endLine" to range?.endLine,
    )
    return UiAiContext(
        key = "source-workspace",
        title = "Selected source metadata",
        currentSelection = true,
        evidence = listOf(
            UiAiEvidence(
                uiAiEvidenceId(kind, location.workspaceId.value, location.snapshotId.value, location.relativePath, payload),
                kind,
                "Verified snapshot source metadata only; not performance measurement or current working-tree contents",
                payload,
            ),
        ),
    )
}
