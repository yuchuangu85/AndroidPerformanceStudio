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
                    message = "${node.className} 节点存在但当前不可见",
                    arguments = mapOf("className" to node.className),
                )
            }
            if (node.children.size > config.maxChildrenPerNode) {
                findings += Finding(
                    ruleId = "layout.excessive-children",
                    severity = Severity.WARNING,
                    nodeId = node.id,
                    message = "直接子节点数量 ${node.children.size}，超过阈值 ${config.maxChildrenPerNode}",
                    arguments = mapOf(
                        "count" to node.children.size.toString(),
                        "threshold" to config.maxChildrenPerNode.toString(),
                    ),
                )
            }
            val overlappingSiblings = substantiallyOverlappingSiblings(node.children)
            if (overlappingSiblings >= config.minOverlappingSiblings) {
                findings += Finding(
                    ruleId = "layout.overlapping-siblings",
                    severity = Severity.WARNING,
                    nodeId = node.id,
                    message =
                        "$overlappingSiblings 个兄弟节点的边界重叠比例至少为 " +
                            "${(config.minSiblingOverlapRatio * 100).toInt()}%；" +
                            "这是结构性渲染风险，请使用 GPU 工具进一步确认",
                    arguments = mapOf(
                        "count" to overlappingSiblings.toString(),
                        "ratioPercent" to (config.minSiblingOverlapRatio * 100).toInt().toString(),
                    ),
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
                message = "层级深度 $maxDepth，超过阈值 ${config.maxDepth}",
                arguments = mapOf(
                    "depth" to maxDepth.toString(),
                    "threshold" to config.maxDepth.toString(),
                ),
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

    private fun substantiallyOverlappingSiblings(children: List<UiNode>): Int {
        val overlappingIndexes = mutableSetOf<Int>()
        for (firstIndex in children.indices) {
            for (secondIndex in firstIndex + 1 until children.size) {
                if (overlapRatio(children[firstIndex], children[secondIndex]) >= config.minSiblingOverlapRatio) {
                    overlappingIndexes += firstIndex
                    overlappingIndexes += secondIndex
                }
            }
        }
        return overlappingIndexes.size
    }

    private fun overlapRatio(first: UiNode, second: UiNode): Float {
        val intersectionWidth =
            (minOf(first.bounds.right, second.bounds.right) -
                maxOf(first.bounds.left, second.bounds.left)).coerceAtLeast(0)
        val intersectionHeight =
            (minOf(first.bounds.bottom, second.bounds.bottom) -
                maxOf(first.bounds.top, second.bounds.top)).coerceAtLeast(0)
        val smallerArea = minOf(first.bounds.width * first.bounds.height, second.bounds.width * second.bounds.height)
        if (smallerArea == 0) return 0f
        return intersectionWidth.toFloat() * intersectionHeight / smallerArea
    }

    private data class NodeAtDepth(
        val node: UiNode,
        val depth: Int,
    )
}
