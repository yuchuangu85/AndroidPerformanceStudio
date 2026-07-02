package dev.agentperf.application

import dev.agentperf.analysis.AnalysisReport
import dev.agentperf.analysis.LayoutMetrics
import dev.agentperf.protocol.ComposeNode
import dev.agentperf.protocol.LayoutSnapshot
import dev.agentperf.protocol.UiNode
import dev.agentperf.protocol.ViewNode

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class InspectorState(
    val snapshot: LayoutSnapshot? = null,
    val screenshotPng: ByteArray? = null,
    val analysis: AnalysisReport = AnalysisReport(
        metrics = LayoutMetrics(nodeCount = 0, maxDepth = 0, widestLevel = 0),
        findings = emptyList(),
    ),
    val selectedNodeId: String? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val connectionError: String? = null,
) {
    val selectedNode: UiNode?
        get() = snapshot?.root?.findById(selectedNodeId)
}

val UiNode.textContent: String?
    get() = when (this) {
        is ViewNode -> text
        is ComposeNode -> text
    }

private fun UiNode.findById(targetId: String?): UiNode? {
    if (targetId == null) return null
    if (id == targetId) return this
    return children.firstNotNullOfOrNull { it.findById(targetId) }
}
