package com.androidperformancestudio.application

import com.androidperformancestudio.analysis.AiAnalysisReport
import com.androidperformancestudio.analysis.AnalysisReport
import com.androidperformancestudio.analysis.LayoutAnalyzer
import com.androidperformancestudio.compose.inspection.ComposeInspectionDocument
import com.androidperformancestudio.compose.inspection.ComposableDetail
import com.androidperformancestudio.compose.inspection.ComposeDetailCoverage
import com.androidperformancestudio.compose.inspection.ComposeDetailCoverageState
import com.androidperformancestudio.compose.inspection.ComposeParameterReference
import com.androidperformancestudio.compose.inspection.ComposeValue
import com.androidperformancestudio.compose.inspection.CapabilityAvailability
import com.androidperformancestudio.compose.inspection.ComposeCapability
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.effectiveDefaultWindowId
import com.androidperformancestudio.protocol.effectiveWindows

data class ManualScreenshotTarget(
    val capturedAtEpochMillis: Long,
    val timelineFrameIndex: Int?,
)

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
        analysisOverride: AnalysisReport? = null,
        timelineDiff: TimelineDiff? = null,
        timelineFrames: List<TimelineFrame> = previous.timelineFrames,
        selectedTimelineFrameIndex: Int? = previous.selectedTimelineFrameIndex,
        composeInspection: ComposeInspectionDocument? = previous.composeInspection,
        composeInspectionWarning: String? = previous.composeInspectionWarning,
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
            composeInspection = composeInspection,
            composeInspectionWarning = composeInspectionWarning,
        )
    }

    fun loadCapture(
        snapshot: LayoutSnapshot,
        screenshotPng: ByteArray,
        composeInspection: ComposeInspectionDocument? = null,
    ) {
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
            composeInspection = composeInspection,
        )
    }

    fun loadComposeDetail(frameId: String, detail: ComposableDetail): Boolean {
        val document = state.composeInspection?.takeIf { it.frame.frameId == frameId } ?: return false
        val fields = mapOf(
            "parameters" to detail.parameters,
            "modifiers" to detail.modifiers,
            "mergedSemantics" to detail.mergedSemantics,
            "unmergedSemantics" to detail.unmergedSemantics,
        )
        val detailCapabilities = setOf(
            ComposeCapability.PARAMETERS,
            ComposeCapability.MODIFIERS,
            ComposeCapability.MERGED_SEMANTICS,
            ComposeCapability.UNMERGED_SEMANTICS,
        )
        state = state.copy(
            composeInspection = document.copy(
                frame = document.frame.copy(
                    details = document.frame.details + (detail.nodeId to detail),
                    coverage = document.frame.coverage.filterNot {
                        it.nodeId == detail.nodeId && it.field in fields.keys
                    } + fields.map { (field, values) ->
                        ComposeDetailCoverage(
                            nodeId = detail.nodeId,
                            field = field,
                            state = if (values.any(ComposeValue::containsTruncation)) {
                                ComposeDetailCoverageState.TRUNCATED
                            } else {
                                ComposeDetailCoverageState.COLLECTED
                            },
                            recursionDepth = 2,
                            loadedElements = values.sumOf(ComposeValue::valueCount),
                        )
                    },
                    capabilities = document.frame.capabilities.map { capability ->
                        if (capability.capability in detailCapabilities) {
                            capability.copy(availability = CapabilityAvailability.AVAILABLE, reason = null)
                        } else {
                            capability
                        }
                    },
                ),
            ),
        )
        return true
    }

    fun loadComposeParameterDetails(
        frameId: String,
        reference: ComposeParameterReference,
        expanded: ComposeValue,
    ): Boolean {
        val document = state.composeInspection?.takeIf { it.frame.frameId == frameId } ?: return false
        val detail = document.frame.details[reference.composableId] ?: return false
        fun List<ComposeValue>.replace(): List<ComposeValue> = map { value ->
            if (value.reference == reference) {
                expanded
            } else {
                value.copy(elements = value.elements.replace())
            }
        }
        val updated = detail.copy(
            parameters = detail.parameters.replace(),
            modifiers = detail.modifiers.replace(),
            mergedSemantics = detail.mergedSemantics.replace(),
            unmergedSemantics = detail.unmergedSemantics.replace(),
        )
        val field = mapOf(
            "parameters" to detail.parameters,
            "modifiers" to detail.modifiers,
            "mergedSemantics" to detail.mergedSemantics,
            "unmergedSemantics" to detail.unmergedSemantics,
        ).entries.firstOrNull { (_, values) -> values.any { it.contains(reference) } }?.key
        val updatedValues = field?.let {
            when (it) {
                "parameters" -> updated.parameters
                "modifiers" -> updated.modifiers
                "mergedSemantics" -> updated.mergedSemantics
                else -> updated.unmergedSemantics
            }
        }
        state = state.copy(
            composeInspection = document.copy(
                frame = document.frame.copy(
                    details = document.frame.details + (detail.nodeId to updated),
                    coverage = if (field == null || updatedValues == null) {
                        document.frame.coverage
                    } else {
                        document.frame.coverage.filterNot { it.nodeId == detail.nodeId && it.field == field } +
                            ComposeDetailCoverage(
                                nodeId = detail.nodeId,
                                field = field,
                                state = if (updatedValues.any(ComposeValue::containsTruncation)) {
                                    ComposeDetailCoverageState.TRUNCATED
                                } else {
                                    ComposeDetailCoverageState.COLLECTED
                                },
                                recursionDepth = 2,
                                loadedElements = updatedValues.sumOf(ComposeValue::valueCount),
                            )
                    },
                ),
            ),
        )
        return true
    }

    fun manualScreenshotTarget(): ManualScreenshotTarget? = state.snapshot?.let { snapshot ->
        ManualScreenshotTarget(
            capturedAtEpochMillis = snapshot.capturedAtEpochMillis,
            timelineFrameIndex = state.selectedTimelineFrameIndex,
        )
    }

    fun loadManualScreenshot(
        target: ManualScreenshotTarget,
        screenshotPng: ByteArray,
    ): Boolean {
        val snapshot = state.snapshot ?: return false
        if (snapshot.capturedAtEpochMillis != target.capturedAtEpochMillis ||
            state.selectedTimelineFrameIndex != target.timelineFrameIndex
        ) return false
        val updatedSnapshot = snapshot.copy(
            capabilities = snapshot.capabilities.copy(screenshots = true),
        )
        val updatedFrames = state.timelineFrames.map { frame ->
            if (target.timelineFrameIndex != null && frame.index == target.timelineFrameIndex) {
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
            aiAnalysis = null,
        )
        return true
    }

    fun loadArchive(
        snapshot: LayoutSnapshot,
        screenshotPng: ByteArray?,
        analysis: AnalysisReport? = null,
        aiAnalysis: AiAnalysisReport? = null,
        timelineFrames: List<TimelineFrame> = emptyList(),
        composeInspection: ComposeInspectionDocument? = null,
        composeInspectionWarning: String? = null,
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
            composeInspection = composeInspection,
            composeInspectionWarning = composeInspectionWarning,
        )
        if (aiAnalysis != null) {
            state = state.copy(aiAnalysis = aiAnalysis)
        }
    }

    fun loadAiAnalysis(report: AiAnalysisReport) {
        state = state.copy(aiAnalysis = report)
    }

    private fun loadInspectedContent(
        snapshot: LayoutSnapshot,
        screenshotPng: ByteArray?,
        connectionStatus: ConnectionStatus,
        analysisOverride: AnalysisReport? = null,
        timelineDiff: TimelineDiff? = null,
        timelineFrames: List<TimelineFrame> = state.timelineFrames,
        selectedTimelineFrameIndex: Int? = state.selectedTimelineFrameIndex,
        composeInspection: ComposeInspectionDocument? = null,
        composeInspectionWarning: String? = null,
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
            composeInspection = composeInspection,
            composeInspectionWarning = composeInspectionWarning,
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
            screenshotPng = frame.screenshotPng,
            connectionStatus = state.connectionStatus,
            timelineDiff = frame.diffFromPrevious,
            timelineFrames = state.timelineFrames,
            selectedTimelineFrameIndex = index,
        )
        return true
    }

    fun removeTimelineFrame(index: Int): Boolean {
        val current = state
        if (current.timelineFrames.none { it.index == index }) return false

        val remainingFrames = current.timelineFrames.filterNot { it.index == index }
        if (current.selectedTimelineFrameIndex != index) {
            state = current.copy(timelineFrames = remainingFrames)
            return true
        }

        val replacement =
            remainingFrames.firstOrNull { it.index > index && it.snapshot != null }
                ?: remainingFrames.lastOrNull { it.snapshot != null }
        if (replacement?.snapshot != null) {
            loadInspectedContent(
                snapshot = replacement.snapshot,
                screenshotPng = replacement.screenshotPng,
                connectionStatus = current.connectionStatus,
                timelineDiff = replacement.diffFromPrevious,
                timelineFrames = remainingFrames,
                selectedTimelineFrameIndex = replacement.index,
            )
        } else {
            state = current.copy(
                timelineDiff = null,
                timelineFrames = remainingFrames,
                selectedTimelineFrameIndex = null,
                aiAnalysis = null,
            )
        }
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
            aiAnalysis = null,
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

private fun ComposeValue.containsTruncation(): Boolean = truncated || elements.any(ComposeValue::containsTruncation)

private fun ComposeValue.valueCount(): Int = 1 + elements.sumOf(ComposeValue::valueCount)

private fun ComposeValue.contains(reference: ComposeParameterReference): Boolean =
    this.reference == reference || elements.any { it.contains(reference) }
