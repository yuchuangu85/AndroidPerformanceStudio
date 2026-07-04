package dev.agentperf.desktop

import dev.agentperf.analysis.Severity
import dev.agentperf.application.ConnectionStatus
import dev.agentperf.application.InspectorState
import dev.agentperf.application.textContent
import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.UiNode

data class TreeRowModel(
    val id: String,
    val number: String,
    val label: String,
    val depth: Int,
    val selected: Boolean,
    val visible: Boolean,
    val hasChildren: Boolean,
)

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

enum class FindingTone {
    INFO,
    WARNING,
    ERROR,
}

data class FindingRowModel(
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
    val emptyMessage: String?,
    val connectionLabel: String,
    val connectionTone: ConnectionTone,
)

object InspectorPresenter {
    fun present(state: InspectorState): InspectorScreenModel {
        val nodeNumbers = mutableMapOf<String, String>()
        val nextIndexByDepth = mutableMapOf<Int, Int>()
        val rows = buildList {
            state.snapshot?.root?.appendRows(
                target = this,
                depth = 0,
                selectedNodeId = state.selectedNodeId,
                nodeNumbers = nodeNumbers,
                nextIndexByDepth = nextIndexByDepth,
            )
        }
        val selected = state.selectedNode
        val findings = state.analysis.findings
        val metrics = state.analysis.metrics
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
                    ),
                )
            } ?: NodeDetailsModel(),
            severitySummary = SeveritySummary(
                info = findings.count { it.severity == Severity.INFO },
                warning = findings.count { it.severity == Severity.WARNING },
                error = findings.count { it.severity == Severity.ERROR },
            ),
            findings = findings.map { finding ->
                FindingRowModel(
                    title = localizedFindingTitle(finding.ruleId),
                    nodeNumber = nodeNumbers[finding.nodeId] ?: "—",
                    nodeId = finding.nodeId,
                    message = finding.message,
                    tone = when (finding.severity) {
                        Severity.INFO -> FindingTone.INFO
                        Severity.WARNING -> FindingTone.WARNING
                        Severity.ERROR -> FindingTone.ERROR
                    },
                )
            },
            metricsText = "${metrics.nodeCount} nodes · depth ${metrics.maxDepth} · width ${metrics.widestLevel}",
            emptyMessage = if (state.snapshot == null) "No snapshot loaded" else null,
            connectionLabel = when (state.connectionStatus) {
                ConnectionStatus.DISCONNECTED -> "Disconnected"
                ConnectionStatus.CONNECTING -> "Connecting"
                ConnectionStatus.CONNECTED -> "Live"
                ConnectionStatus.ERROR -> state.connectionError ?: "Connection failed"
            },
            connectionTone = when (state.connectionStatus) {
                ConnectionStatus.DISCONNECTED,
                ConnectionStatus.CONNECTING,
                -> ConnectionTone.NEUTRAL
                ConnectionStatus.CONNECTED -> ConnectionTone.SUCCESS
                ConnectionStatus.ERROR -> ConnectionTone.ERROR
            },
        )
    }

    private fun localizedFindingTitle(ruleId: String): String = when (ruleId) {
        "layout.invisible-node" -> "不可见节点"
        "layout.excessive-children" -> "子节点过多"
        "layout.overlapping-siblings" -> "兄弟节点区域重叠"
        "layout.deep-hierarchy" -> "层级过深"
        else -> ruleId
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
