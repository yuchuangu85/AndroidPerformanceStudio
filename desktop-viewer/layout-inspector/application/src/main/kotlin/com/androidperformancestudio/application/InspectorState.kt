package com.androidperformancestudio.application

import com.androidperformancestudio.analysis.AiAnalysisReport
import com.androidperformancestudio.analysis.AnalysisReport
import com.androidperformancestudio.analysis.LayoutMetrics
import com.androidperformancestudio.compose.inspection.ComposeInspectionDocument
import com.androidperformancestudio.protocol.ComposeNode
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.ViewNode
import com.androidperformancestudio.protocol.WindowSnapshot
import com.androidperformancestudio.protocol.effectiveWindows

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ARCHIVE,
    ERROR,
}

data class TimelineFrame(
    val index: Int,
    val capturedAtEpochMillis: Long,
    val snapshot: LayoutSnapshot? = null,
    val screenshotPng: ByteArray? = null,
    val diffFromPrevious: TimelineDiff? = null,
) {
    constructor(
        index: Int,
        snapshot: LayoutSnapshot,
        screenshotPng: ByteArray?,
        diffFromPrevious: TimelineDiff? = null,
    ) : this(
        index = index,
        capturedAtEpochMillis = snapshot.capturedAtEpochMillis,
        snapshot = snapshot,
        screenshotPng = screenshotPng,
        diffFromPrevious = diffFromPrevious,
    )
}

data class InspectorState(
    val snapshot: LayoutSnapshot? = null,
    val screenshotPng: ByteArray? = null,
    val analysis: AnalysisReport = AnalysisReport(
        metrics = LayoutMetrics(nodeCount = 0, maxDepth = 0, widestLevel = 0),
        findings = emptyList(),
    ),
    val aiAnalysis: AiAnalysisReport? = null,
    val selectedWindowId: String? = null,
    val selectedNodeIdsByWindow: Map<String, String> = emptyMap(),
    val selectedNodeId: String? = null,
    val hoveredNodeId: String? = null,
    val timelineDiff: TimelineDiff? = null,
    val timelineFrames: List<TimelineFrame> = emptyList(),
    val selectedTimelineFrameIndex: Int? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val connectionError: String? = null,
    val composeInspection: ComposeInspectionDocument? = null,
    val composeInspectionWarning: String? = null,
) {
    val windows: List<WindowSnapshot>
        get() = snapshot?.effectiveWindows.orEmpty()

    val activeWindow: WindowSnapshot?
        get() = windows.firstOrNull { it.id == selectedWindowId } ?: windows.firstOrNull()

    val activeRoot: UiNode?
        get() = activeWindow?.root

    val selectedNode: UiNode?
        get() = activeRoot?.findById(selectedNodeId)
}

val UiNode.textContent: String?
    get() = when (this) {
        is ViewNode -> text
        is ComposeNode -> text
    }

internal fun UiNode.findById(targetId: String?): UiNode? {
    if (targetId == null) return null
    if (id == targetId) return this
    return children.firstNotNullOfOrNull { it.findById(targetId) }
}
