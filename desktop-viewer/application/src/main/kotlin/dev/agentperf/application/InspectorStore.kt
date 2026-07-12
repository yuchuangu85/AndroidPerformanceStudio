package dev.agentperf.application

import dev.agentperf.analysis.LayoutAnalyzer
import dev.agentperf.protocol.DisplayInfo
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
        analysisOverride: dev.agentperf.analysis.AnalysisReport? = null,
        timelineDiff: TimelineDiff? = null,
        timelineFrames: List<TimelineFrame> = previous.timelineFrames,
        selectedTimelineFrameIndex: Int? = previous.selectedTimelineFrameIndex,
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
            analysis = analysisOverride ?: analyzer.analyze(activeRoot),
            selectedWindowId = windowId,
            selectedNodeIdsByWindow = selections,
            selectedNodeId = selectedNodeId,
            timelineDiff = timelineDiff,
            timelineFrames = timelineFrames,
            selectedTimelineFrameIndex = selectedTimelineFrameIndex,
            connectionStatus = connectionStatus,
        )
    }

    fun loadCapture(snapshot: LayoutSnapshot, screenshotPng: ByteArray) {
        val previousSnapshot = state.timelineFrames.lastOrNull()?.snapshot ?: state.snapshot
        val diff = previousSnapshot?.let { diffSnapshots(it, snapshot) }
        val nextIndex = (state.timelineFrames.lastOrNull()?.index ?: -1) + 1
        val nextFrames = (state.timelineFrames + TimelineFrame(
            index = nextIndex,
            snapshot = snapshot,
            screenshotPng = screenshotPng,
            diffFromPrevious = diff,
        )).takeLast(MAX_TIMELINE_FRAMES)
        loadInspectedContent(
            snapshot = snapshot,
            screenshotPng = screenshotPng,
            connectionStatus = ConnectionStatus.CONNECTED,
            timelineDiff = diff,
            timelineFrames = nextFrames,
            selectedTimelineFrameIndex = nextIndex,
        )
    }

    fun loadManualScreenshot(
        screenshotPng: ByteArray,
        display: DisplayInfo? = null,
    ): Boolean {
        val snapshot = state.snapshot ?: return false
        val updatedSnapshot = display?.let {
            snapshot.copy(
                display = it,
                capabilities = snapshot.capabilities.copy(screenshots = true),
            )
        } ?: snapshot
        val updatedFrames = state.timelineFrames.map { frame ->
            if (frame.capturedAtEpochMillis == snapshot.capturedAtEpochMillis) {
                frame.copy(
                    snapshot = frame.snapshot?.let { updatedSnapshot },
                    screenshotPng = screenshotPng,
                )
            } else {
                frame
            }
        }
        state = state.copy(
            snapshot = updatedSnapshot,
            screenshotPng = screenshotPng,
            timelineFrames = updatedFrames,
        )
        return true
    }

    fun loadArchive(
        snapshot: LayoutSnapshot,
        screenshotPng: ByteArray,
        analysis: dev.agentperf.analysis.AnalysisReport? = null,
        timelineFrames: List<TimelineFrame> = emptyList(),
    ) {
        val selectedTimelineFrameIndex = timelineFrames.lastOrNull {
            it.capturedAtEpochMillis == snapshot.capturedAtEpochMillis
        }?.index
        val frames = timelineFrames.map { frame ->
            if (frame.capturedAtEpochMillis == snapshot.capturedAtEpochMillis && frame.snapshot == null) {
                frame.copy(snapshot = snapshot, screenshotPng = screenshotPng)
            } else {
                frame
            }
        }
        loadInspectedContent(
            snapshot = snapshot,
            screenshotPng = screenshotPng,
            connectionStatus = ConnectionStatus.ARCHIVE,
            analysisOverride = analysis,
            timelineDiff = frames.firstOrNull { it.index == selectedTimelineFrameIndex }?.diffFromPrevious,
            timelineFrames = frames,
            selectedTimelineFrameIndex = selectedTimelineFrameIndex,
        )
    }

    private fun loadInspectedContent(
        snapshot: LayoutSnapshot,
        screenshotPng: ByteArray,
        connectionStatus: ConnectionStatus,
        analysisOverride: dev.agentperf.analysis.AnalysisReport? = null,
        timelineDiff: TimelineDiff? = null,
        timelineFrames: List<TimelineFrame> = state.timelineFrames,
        selectedTimelineFrameIndex: Int? = state.selectedTimelineFrameIndex,
    ) {
        state = inspectedState(
            snapshot = snapshot,
            screenshotPng = screenshotPng,
            connectionStatus = connectionStatus,
            previous = state,
            analysisOverride = analysisOverride,
            timelineDiff = timelineDiff,
            timelineFrames = timelineFrames,
            selectedTimelineFrameIndex = selectedTimelineFrameIndex,
        )
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

    fun selectTimelineFrame(index: Int): Boolean {
        val frame = state.timelineFrames.firstOrNull { it.index == index } ?: return false
        val snapshot = frame.snapshot ?: return false
        loadInspectedContent(
            snapshot = snapshot,
            screenshotPng = frame.screenshotPng ?: byteArrayOf(),
            connectionStatus = state.connectionStatus,
            timelineDiff = frame.diffFromPrevious,
            timelineFrames = state.timelineFrames,
            selectedTimelineFrameIndex = index,
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
            timelineDiff = state.timelineDiff,
            analysis = analyzer.analyze(window.root),
        )
        return true
    }

    private companion object {
        const val MAX_TIMELINE_FRAMES = 50
    }

    fun setHoveredNode(nodeId: String?) {
        if (nodeId != null && state.activeRoot?.findById(nodeId) == null) return
        state = state.copy(hoveredNodeId = nodeId)
    }
}
