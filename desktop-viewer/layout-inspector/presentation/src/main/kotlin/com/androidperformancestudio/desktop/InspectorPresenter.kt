package com.androidperformancestudio.desktop

import com.androidperformancestudio.analysis.Severity
import com.androidperformancestudio.presentation.generated.resources.Res
import com.androidperformancestudio.presentation.generated.resources.*
import com.androidperformancestudio.application.ConnectionStatus
import com.androidperformancestudio.application.InspectorState
import com.androidperformancestudio.application.textContent
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.ViewNode
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

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
    val sourceCandidateIds: List<String> = emptyList(),
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
    private val authorizedDeviceCountError =
        Regex("""Expected exactly one authorized device, found (\d+)""")

    fun present(
        state: InspectorState,
        language: UiLanguage = UiLanguage.ENGLISH,
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
                title = findingTitle(finding.ruleId, language),
                nodeNumber = nodeNumbers[finding.nodeId] ?: "—",
                nodeId = finding.nodeId,
                message = findingMessage(
                    ruleId = finding.ruleId,
                    arguments = finding.arguments,
                    fallback = finding.message,
                    language = language,
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
                sourceCandidateIds = finding.sourceCandidateIds,
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
                        language = language,
                        composeInspection = state.composeInspection,
                        composeInspectionWarning = state.composeInspectionWarning,
                    ),
                )
            } ?: NodeDetailsModel(),
            severitySummary = SeveritySummary(
                info = findingRows.count { it.tone == FindingTone.INFO },
                warning = findingRows.count { it.tone == FindingTone.WARNING },
                error = findingRows.count { it.tone == FindingTone.ERROR },
            ),
            findings = findingRows,
            metricsText = localizedStringResource(
                Res.string.metrics_summary,
                language,
                metrics.nodeCount,
                metrics.maxDepth,
                metrics.widestLevel,
            ),
            timelineText = state.timelineDiff?.let {
                localizedStringResource(
                    Res.string.timeline_diff,
                    language,
                    it.addedNodes,
                    it.removedNodes,
                    it.boundsChangedNodes,
                )
            },
            timelineFrames = state.timelineFrames.map { frame ->
                TimelineFrameModel(
                    index = frame.index,
                    label = "#${frame.index}",
                    summary = frame.diffFromPrevious?.let { diff ->
                        localizedStringResource(
                            Res.string.timeline_frame_summary,
                            language,
                            diff.addedNodes,
                            diff.removedNodes,
                            diff.boundsChangedNodes,
                        )
                    } ?: localizedStringResource(Res.string.timeline_baseline, language),
                    selected = frame.index == state.selectedTimelineFrameIndex,
                )
            },
            emptyMessage = if (state.snapshot == null) {
                localizedStringResource(Res.string.no_snapshot_loaded, language)
            } else {
                null
            },
            connectionLabel = when (state.connectionStatus) {
                ConnectionStatus.DISCONNECTED -> localizedStringResource(Res.string.disconnected, language)
                ConnectionStatus.CONNECTING -> localizedStringResource(Res.string.connecting, language)
                ConnectionStatus.CONNECTED -> localizedStringResource(Res.string.live, language)
                ConnectionStatus.ARCHIVE -> localizedStringResource(Res.string.offline_archive, language)
                ConnectionStatus.ERROR -> state.connectionError
                    ?.let { localizeConnectionError(it, language) }
                    ?: localizedStringResource(Res.string.connection_failed, language)
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

    private fun findingTitle(ruleId: String, language: UiLanguage): String = when (ruleId) {
        "layout.invisible-node" -> localizedStringResource(Res.string.finding_invisible_node, language)
        "layout.excessive-children" -> localizedStringResource(Res.string.finding_excessive_children, language)
        "layout.overlapping-siblings" -> localizedStringResource(Res.string.finding_overlapping_siblings, language)
        "layout.deep-hierarchy" -> localizedStringResource(Res.string.finding_deep_hierarchy, language)
        else -> ruleId
    }

    private fun findingMessage(
        ruleId: String,
        arguments: Map<String, String>,
        fallback: String,
        language: UiLanguage,
    ): String = when (ruleId) {
        "layout.invisible-node" -> arguments["className"]?.let { className ->
            localizedStringResource(Res.string.finding_invisible_node_message, language, className)
        }
        "layout.excessive-children" -> {
            val count = arguments["count"]
            val threshold = arguments["threshold"]
            if (count != null && threshold != null) {
                localizedStringResource(Res.string.finding_excessive_children_message, language, count, threshold)
            } else {
                null
            }
        }
        "layout.overlapping-siblings" -> {
            val count = arguments["count"]
            val ratio = arguments["ratioPercent"]
            if (count != null && ratio != null) {
                localizedStringResource(Res.string.finding_overlapping_siblings_message, language, count, ratio)
            } else {
                null
            }
        }
        "layout.deep-hierarchy" -> {
            val depth = arguments["depth"]
            val threshold = arguments["threshold"]
            if (depth != null && threshold != null) {
                localizedStringResource(Res.string.finding_deep_hierarchy_message, language, depth, threshold)
            } else {
                null
            }
        }
        else -> null
    } ?: fallback

    private fun localizeConnectionError(message: String, language: UiLanguage): String {
        val deviceCount = authorizedDeviceCountError
            .matchEntire(message)
            ?.groupValues
            ?.get(1)
            ?: return message
        return localizedStringResource(Res.string.connection_error_device_count, language, deviceCount)
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
