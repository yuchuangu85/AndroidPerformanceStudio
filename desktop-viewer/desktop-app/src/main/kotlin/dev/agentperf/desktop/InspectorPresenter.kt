package dev.agentperf.desktop

import dev.agentperf.analysis.Severity
import dev.agentperf.application.ConnectionStatus
import dev.agentperf.application.InspectorState
import dev.agentperf.application.textContent
import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.UiNode

data class TreeRowModel(
    val id: String,
    val label: String,
    val depth: Int,
    val selected: Boolean,
    val visible: Boolean,
)

data class NodeDetailsModel(
    val id: String = "—",
    val className: String = "—",
    val text: String? = null,
    val bounds: Bounds? = null,
    val childCount: Int = 0,
)

data class SeveritySummary(
    val info: Int,
    val warning: Int,
    val error: Int,
)

data class FindingRowModel(
    val title: String,
    val nodeId: String,
    val message: String,
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
        val rows = buildList {
            state.snapshot?.root?.appendRows(
                target = this,
                depth = 0,
                selectedNodeId = state.selectedNodeId,
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
                    nodeId = finding.nodeId,
                    message = finding.message,
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
    ) {
        target += TreeRowModel(
            id = id,
            label = className.substringAfterLast('.'),
            depth = depth,
            selected = id == selectedNodeId,
            visible = visible && alpha > 0f,
        )
        children.forEach { child ->
            child.appendRows(target, depth + 1, selectedNodeId)
        }
    }
}
