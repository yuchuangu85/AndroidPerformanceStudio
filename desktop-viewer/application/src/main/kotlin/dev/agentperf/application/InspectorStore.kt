package dev.agentperf.application

import dev.agentperf.analysis.LayoutAnalyzer
import dev.agentperf.protocol.LayoutSnapshot

class InspectorStore(
    private val analyzer: LayoutAnalyzer = LayoutAnalyzer(),
) {
    var state: InspectorState = InspectorState()
        private set

    fun load(snapshot: LayoutSnapshot) {
        state = InspectorState(
            snapshot = snapshot,
            analysis = analyzer.analyze(snapshot.root),
            selectedNodeId = snapshot.root.id,
        )
    }

    fun loadCapture(snapshot: LayoutSnapshot, screenshotPng: ByteArray) {
        val next = InspectorState(
            snapshot = snapshot,
            screenshotPng = screenshotPng,
            analysis = analyzer.analyze(snapshot.root),
            selectedNodeId = state.selectedNodeId,
            connectionStatus = ConnectionStatus.CONNECTED,
        )
        state = if (next.selectedNode == null) {
            next.copy(selectedNodeId = snapshot.root.id)
        } else {
            next
        }
    }

    fun connecting() {
        state = state.copy(
            connectionStatus = ConnectionStatus.CONNECTING,
            connectionError = null,
        )
    }

    fun connectionFailed(message: String) {
        state = state.copy(
            connectionStatus = ConnectionStatus.ERROR,
            connectionError = message,
        )
    }

    fun selectNode(nodeId: String): Boolean {
        val candidate = state.copy(selectedNodeId = nodeId)
        if (candidate.selectedNode == null) return false
        state = candidate
        return true
    }
}
