package com.androidperformancestudio.desktop

import com.androidperformancestudio.analysis.Severity
import com.androidperformancestudio.application.ConnectionStatus
import com.androidperformancestudio.application.InspectorState
import com.androidperformancestudio.application.textContent
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.ViewNode

data class TreeRowModel(
    val id: String,
    val number: String,
    val label: String,
    val depth: Int,
    val selected: Boolean,
    val visible: Boolean,
    val hasChildren: Boolean,
    val resourceLabel: String? = null,
)

data class WindowChoiceModel(val id: String, val title: String)

data class NodeDetailsModel(
    val id: String = "—",
    val className: String = "—",
    val text: String? = null,
    val bounds: Bounds? = null,
    val childCount: Int = 0,
    val sections: List<DetailSectionModel> = emptyList(),
)

data class SeveritySummary(
    val info: Int,
    val warning: Int,
    val error: Int,
)

data class TimelineFrameModel(
    val index: Int,
    val label: String,
    val summary: String,
    val selected: Boolean,
)

enum class FindingTone {
    INFO,
    WARNING,
    ERROR,
}

data class FindingRowModel(
    val key: String,
    val title: String,
    val nodeNumber: String,
    val nodeId: String,
    val message: String,
    val tone: FindingTone,
)

enum class ConnectionTone {
    NEUTRAL,
    SUCCESS,
    ERROR,
}

data class InspectorScreenModel(
    val packageName: String?,
    val rows: List<TreeRowModel>,
    val details: NodeDetailsModel,
    val severitySummary: SeveritySummary,
    val findings: List<FindingRowModel>,
    val metricsText: String,
    val timelineText: String?,
    val timelineFrames: List<TimelineFrameModel>,
    val emptyMessage: String?,
    val connectionLabel: String,
    val connectionTone: ConnectionTone,
    val windows: List<WindowChoiceModel>,
    val selectedWindowId: String?,
)

internal object InspectorPresenter {
    fun present(
        state: InspectorState,
        strings: ViewerStrings = ViewerStrings.English,
    ): InspectorScreenModel {
        val nodeNumbers = mutableMapOf<String, String>()
        val nextIndexByDepth = mutableMapOf<Int, Int>()
        val rows = buildList {
            state.activeRoot?.appendRows(
                target = this,
                depth = 0,
                selectedNodeId = state.selectedNodeId,
                nodeNumbers = nodeNumbers,
                nextIndexByDepth = nextIndexByDepth,
            )
        }
        val selected = state.selectedNode
        val findings = state.analysis.findings
        val aiFindings = state.aiAnalysis?.findings.orEmpty()
        val metrics = state.analysis.metrics
        val findingRows = findings.mapIndexed { index, finding ->
            FindingRowModel(
                key = "${finding.ruleId}:${finding.nodeId}:$index",
                title = strings.findingTitle(finding.ruleId),
                nodeNumber = nodeNumbers[finding.nodeId] ?: "—",
                nodeId = finding.nodeId,
                message = strings.findingMessage(
                    ruleId = finding.ruleId,
                    arguments = finding.arguments,
                    fallback = finding.message,
                ),
                tone = finding.severity.toTone(),
            )
        } + aiFindings.mapIndexed { index, finding ->
            FindingRowModel(
                key = "ai:${finding.ruleId}:${finding.nodeId}:$index",
                title = "AI · ${finding.title}",
                nodeNumber = nodeNumbers[finding.nodeId] ?: "—",
                nodeId = finding.nodeId,
                message = "${finding.message} · ${finding.recommendation}",
                tone = finding.severity.toTone(),
            )
        }
        return InspectorScreenModel(
            packageName = state.snapshot?.packageName,
            rows = rows,
            details = selected?.let {
                NodeDetailsModel(
                    id = it.id,
                    className = it.className,
                    text = it.textContent,
                    bounds = it.bounds,
                    childCount = it.children.size,
                    sections = NodeDetailsPresenter.present(
                        node = it,
                        treeDepth = rows.firstOrNull { row -> row.id == it.id }
                            ?.depth
                            ?.plus(1)
                            ?: 1,
                        strings = strings,
                    ),
                )
            } ?: NodeDetailsModel(),
            severitySummary = SeveritySummary(
                info = findingRows.count { it.tone == FindingTone.INFO },
                warning = findingRows.count { it.tone == FindingTone.WARNING },
                error = findingRows.count { it.tone == FindingTone.ERROR },
            ),
            findings = findingRows,
            metricsText = strings.metrics(metrics.nodeCount, metrics.maxDepth, metrics.widestLevel),
            timelineText = state.timelineDiff?.let { strings.timelineDiff(it.addedNodes, it.removedNodes, it.boundsChangedNodes) },
            timelineFrames = state.timelineFrames.map { frame ->
                TimelineFrameModel(
                    index = frame.index,
                    label = "#${frame.index}",
                    summary = frame.diffFromPrevious?.let { diff ->
                        strings.timelineFrameSummary(diff.addedNodes, diff.removedNodes, diff.boundsChangedNodes)
                    } ?: strings.timelineBaseline,
                    selected = frame.index == state.selectedTimelineFrameIndex,
                )
            },
            emptyMessage = if (state.snapshot == null) strings.noSnapshotLoaded else null,
            connectionLabel = when (state.connectionStatus) {
                ConnectionStatus.DISCONNECTED -> strings.disconnected
                ConnectionStatus.CONNECTING -> strings.connecting
                ConnectionStatus.CONNECTED -> strings.live
                ConnectionStatus.ARCHIVE -> strings.offlineArchive
                ConnectionStatus.ERROR -> state.connectionError
                    ?.let(strings::connectionError)
                    ?: strings.connectionFailed
            },
            connectionTone = when (state.connectionStatus) {
                ConnectionStatus.DISCONNECTED,
                ConnectionStatus.CONNECTING,
                ConnectionStatus.ARCHIVE,
                -> ConnectionTone.NEUTRAL
                ConnectionStatus.CONNECTED -> ConnectionTone.SUCCESS
                ConnectionStatus.ERROR -> ConnectionTone.ERROR
            },
            windows = state.windows.map { WindowChoiceModel(it.id, it.title) },
            selectedWindowId = state.activeWindow?.id,
        )
    }

    private fun Severity.toTone(): FindingTone = when (this) {
        Severity.INFO -> FindingTone.INFO
        Severity.WARNING -> FindingTone.WARNING
        Severity.ERROR -> FindingTone.ERROR
    }

    private fun UiNode.appendRows(
        target: MutableList<TreeRowModel>,
        depth: Int,
        selectedNodeId: String?,
        nodeNumbers: MutableMap<String, String>,
        nextIndexByDepth: MutableMap<Int, Int>,
    ) {
        val index = nextIndexByDepth.getOrDefault(depth, 0)
        nextIndexByDepth[depth] = index + 1
        val number = "$depth-$index"
        nodeNumbers.putIfAbsent(id, number)
        target += TreeRowModel(
            id = id,
            number = number,
            label = className.substringAfterLast('.'),
            depth = depth,
            selected = id == selectedNodeId,
            visible = visible && alpha > 0f,
            hasChildren = children.isNotEmpty(),
            resourceLabel = (this as? ViewNode)
                ?.resourceName
                ?.substringAfterLast('/')
                ?.takeIf(String::isNotBlank)
                ?.let { "id/$it" },
        )
        children.forEach { child ->
            child.appendRows(
                target = target,
                depth = depth + 1,
                selectedNodeId = selectedNodeId,
                nodeNumbers = nodeNumbers,
                nextIndexByDepth = nextIndexByDepth,
            )
        }
    }
}
