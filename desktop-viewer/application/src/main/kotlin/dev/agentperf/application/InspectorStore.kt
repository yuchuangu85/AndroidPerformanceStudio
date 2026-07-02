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

    fun selectNode(nodeId: String): Boolean {
        val candidate = state.copy(selectedNodeId = nodeId)
        if (candidate.selectedNode == null) return false
        state = candidate
        return true
    }
}
