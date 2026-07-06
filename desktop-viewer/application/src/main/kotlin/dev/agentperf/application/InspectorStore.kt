package dev.agentperf.application

import dev.agentperf.analysis.LayoutAnalyzer
import dev.agentperf.protocol.LayoutSnapshot
import dev.agentperf.protocol.effectiveDefaultWindowId
import dev.agentperf.protocol.effectiveWindows

class InspectorStore(
    private val analyzer: LayoutAnalyzer = LayoutAnalyzer(),
) {
    var state: InspectorState = InspectorState()
        private set

    fun load(snapshot: LayoutSnapshot) {
        state = inspectedState(
            snapshot = snapshot,
            screenshotPng = null,
            connectionStatus = ConnectionStatus.DISCONNECTED,
            previous = InspectorState(),
        )
    }

    private fun inspectedState(
        snapshot: LayoutSnapshot,
        screenshotPng: ByteArray?,
        connectionStatus: ConnectionStatus,
        previous: InspectorState,
    ): InspectorState {
        val windows = snapshot.effectiveWindows
        val windowId = previous.selectedWindowId
            ?.takeIf { candidate -> windows.any { it.id == candidate } }
            ?: snapshot.effectiveDefaultWindowId
        val activeRoot = windows.first { it.id == windowId }.root
        val previousNodeId = previous.selectedNodeIdsByWindow[windowId]
            ?: previous.selectedNodeId?.takeIf {
                previous.selectedWindowId == null || previous.selectedWindowId == windowId
            }
        val selectedNodeId = previousNodeId
            ?.takeIf { activeRoot.findById(it) != null }
            ?: activeRoot.id
        val selections = previous.selectedNodeIdsByWindow
            .filterKeys { id -> windows.any { it.id == id } }
            .plus(windowId to selectedNodeId)
        return InspectorState(
            snapshot = snapshot,
            screenshotPng = screenshotPng,
            analysis = analyzer.analyze(activeRoot),
            selectedWindowId = windowId,
            selectedNodeIdsByWindow = selections,
            selectedNodeId = selectedNodeId,
            connectionStatus = connectionStatus,
        )
    }

    fun loadCapture(snapshot: LayoutSnapshot, screenshotPng: ByteArray) {
        loadInspectedContent(
            snapshot = snapshot,
            screenshotPng = screenshotPng,
            connectionStatus = ConnectionStatus.CONNECTED,
        )
    }

    fun loadArchive(snapshot: LayoutSnapshot, screenshotPng: ByteArray) {
        loadInspectedContent(
            snapshot = snapshot,
            screenshotPng = screenshotPng,
            connectionStatus = ConnectionStatus.ARCHIVE,
        )
    }

    private fun loadInspectedContent(
        snapshot: LayoutSnapshot,
        screenshotPng: ByteArray,
        connectionStatus: ConnectionStatus,
    ) {
        state = inspectedState(
            snapshot = snapshot,
            screenshotPng = screenshotPng,
            connectionStatus = connectionStatus,
            previous = state,
        )
    }

    fun connecting() {
        state = InspectorState(
            connectionStatus = ConnectionStatus.CONNECTING,
        )
    }

    fun connectionFailed(message: String) {
        state = state.copy(
            connectionStatus = ConnectionStatus.ERROR,
            connectionError = message,
        )
    }

    fun disconnected() {
        state = state.copy(
            connectionStatus = ConnectionStatus.DISCONNECTED,
            connectionError = null,
        )
    }

    fun selectNode(nodeId: String): Boolean {
        val candidate = state.copy(selectedNodeId = nodeId)
        if (candidate.selectedNode == null) return false
        val windowId = candidate.activeWindow?.id ?: return false
        state = candidate.copy(
            selectedWindowId = windowId,
            selectedNodeIdsByWindow = candidate.selectedNodeIdsByWindow + (windowId to nodeId),
        )
        return true
    }

    fun selectWindow(windowId: String): Boolean {
        val window = state.windows.firstOrNull { it.id == windowId } ?: return false
        if (state.selectedWindowId == windowId) return true
        val selectedNodeId = state.selectedNodeIdsByWindow[windowId]
            ?.takeIf { window.root.findById(it) != null }
            ?: window.root.id
        state = state.copy(
            selectedWindowId = windowId,
            selectedNodeId = selectedNodeId,
            selectedNodeIdsByWindow = state.selectedNodeIdsByWindow + (windowId to selectedNodeId),
            hoveredNodeId = null,
            analysis = analyzer.analyze(window.root),
        )
        return true
    }

    fun setHoveredNode(nodeId: String?) {
        if (nodeId != null && state.activeRoot?.findById(nodeId) == null) return
        state = state.copy(hoveredNodeId = nodeId)
    }
}
