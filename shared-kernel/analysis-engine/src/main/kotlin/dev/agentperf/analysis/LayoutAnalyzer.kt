package dev.agentperf.analysis

import dev.agentperf.protocol.UiNode
import java.util.ArrayDeque

class LayoutAnalyzer(
    private val config: AnalysisConfig = AnalysisConfig(),
) {
    fun analyze(root: UiNode): AnalysisReport {
        var nodeCount = 0
        var maxDepth = 0
        val nodesPerDepth = mutableMapOf<Int, Int>()
        val findings = mutableListOf<Finding>()
        val pending = ArrayDeque<NodeAtDepth>().apply {
            add(NodeAtDepth(root, 1))
        }

        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeFirst()
            nodeCount += 1
            maxDepth = maxOf(maxDepth, depth)
            nodesPerDepth[depth] = nodesPerDepth.getOrDefault(depth, 0) + 1

            if (!node.visible || node.alpha <= 0f) {
                findings += Finding(
                    ruleId = "layout.invisible-node",
                    severity = Severity.INFO,
                    nodeId = node.id,
                    message = "${node.className} is present but not visible",
                )
            }
            if (node.children.size > config.maxChildrenPerNode) {
                findings += Finding(
                    ruleId = "layout.excessive-children",
                    severity = Severity.WARNING,
                    nodeId = node.id,
                    message = "${node.children.size} direct children exceed ${config.maxChildrenPerNode}",
                )
            }
            node.children.forEach { child ->
                pending.addLast(NodeAtDepth(child, depth + 1))
            }
        }

        if (maxDepth > config.maxDepth) {
            findings += Finding(
                ruleId = "layout.deep-hierarchy",
                severity = Severity.WARNING,
                nodeId = root.id,
                message = "Hierarchy depth $maxDepth exceeds ${config.maxDepth}",
            )
        }

        return AnalysisReport(
            metrics = LayoutMetrics(
                nodeCount = nodeCount,
                maxDepth = maxDepth,
                widestLevel = nodesPerDepth.values.maxOrNull() ?: 0,
            ),
            findings = findings,
        )
    }

    private data class NodeAtDepth(
        val node: UiNode,
        val depth: Int,
    )
}
