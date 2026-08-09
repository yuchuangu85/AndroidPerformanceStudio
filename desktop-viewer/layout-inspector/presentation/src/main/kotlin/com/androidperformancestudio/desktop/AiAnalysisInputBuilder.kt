package com.androidperformancestudio.desktop

import com.androidperformancestudio.analysis.AnalysisReport
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.ComposeNode
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.ViewNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

public data class LayoutSourceEvidence(
    val nodeId: String,
    val className: String,
    val resourceName: String?,
)

public data class AiAnalysisInput(
    val json: String,
    val sourceEvidence: List<LayoutSourceEvidence> = emptyList(),
    val selectedNodeId: String? = null,
    val includeSourceSnippets: Boolean = true,
    val omittedSourceEvidenceCount: Int = 0,
    val treeTruncated: Boolean = false,
)

internal class AiAnalysisInputBuilder(
    private val json: Json = Json {
        encodeDefaults = false
        prettyPrint = true
    },
) {
    fun build(
        snapshot: LayoutSnapshot,
        activeRoot: UiNode,
        analysis: AnalysisReport,
        screenshotAvailable: Boolean,
        selectedNode: UiNode? = null,
    ): AiAnalysisInput {
        val allEvidenceNodes = selectedNode?.let(::listOf) ?: activeRoot.flatten().toList()
        val evidenceNodes = allEvidenceNodes.take(MAX_EVIDENCE_NODES)
        val tree = selectedNode?.let { it.toSelectionDto(activeRoot.parentOf(it.id)) } ?: activeRoot.toDto(depth = 0)
        val scopedNodeIds = tree.nodeIds()
        return AiAnalysisInput(
            json = json.encodeToString(
                AiAnalysisInputDto(
                    packageName = snapshot.packageName,
                    capturedAtEpochMillis = snapshot.capturedAtEpochMillis,
                    display = DisplayDto(
                        widthPx = snapshot.display.widthPx,
                        heightPx = snapshot.display.heightPx,
                        density = snapshot.display.density,
                    ),
                    screenshotAvailable = screenshotAvailable,
                    metrics = selectedNode?.let { tree.metrics() } ?: MetricsDto(
                        nodeCount = analysis.metrics.nodeCount,
                        maxDepth = analysis.metrics.maxDepth,
                        widestLevel = analysis.metrics.widestLevel,
                    ),
                    ruleFindings = analysis.findings.filter { selectedNode == null || it.nodeId in scopedNodeIds }.map {
                        RuleFindingDto(
                            ruleId = it.ruleId,
                            severity = it.severity.name,
                            nodeId = it.nodeId,
                            message = it.message,
                        )
                    },
                    tree = tree,
                ),
            ),
            sourceEvidence = evidenceNodes.map { node ->
                LayoutSourceEvidence(
                    nodeId = node.id,
                    className = node.className,
                    resourceName = (node as? ViewNode)?.resourceName,
                )
            },
            selectedNodeId = selectedNode?.id,
            omittedSourceEvidenceCount = allEvidenceNodes.size - evidenceNodes.size,
            treeTruncated = (selectedNode ?: activeRoot).exceedsTreeBudget(depth = 0),
        )
    }

    private fun UiNode.toSelectionDto(parent: UiNode?): NodeDto {
        val selected = toDto(depth = 0)
        return parent?.toDto(depth = 0, maxDepth = 0)?.copy(
            children = listOf(selected),
            truncatedChildren = (parent.children.size - 1).coerceAtLeast(0),
        ) ?: selected
    }

    private fun UiNode.toDto(depth: Int, maxDepth: Int = MAX_DEPTH): NodeDto = NodeDto(
        id = id,
        className = className,
        bounds = bounds.toDto(),
        visible = visible,
        alpha = alpha,
        resourceName = (this as? ViewNode)?.resourceName,
        textLength = when (this) {
            is ViewNode -> text?.length
            is ComposeNode -> text?.length
        },
        contentDescriptionLength = (this as? ViewNode)?.attributes?.contentDescription?.length,
        children = if (depth >= maxDepth) {
            emptyList()
        } else {
            children.take(MAX_CHILDREN_PER_NODE).map { it.toDto(depth + 1, maxDepth) }
        },
        truncatedChildren = if (depth >= maxDepth) {
            children.size
        } else {
            (children.size - MAX_CHILDREN_PER_NODE).coerceAtLeast(0)
        },
    )

    private fun UiNode.exceedsTreeBudget(depth: Int): Boolean =
        (depth >= MAX_DEPTH && children.isNotEmpty()) ||
            children.size > MAX_CHILDREN_PER_NODE ||
            children.take(MAX_CHILDREN_PER_NODE).any { it.exceedsTreeBudget(depth + 1) }

    private fun UiNode.parentOf(nodeId: String): UiNode? =
        takeIf { parent -> parent.children.any { it.id == nodeId } }
            ?: children.firstNotNullOfOrNull { it.parentOf(nodeId) }

    private fun Bounds.toDto() = BoundsDto(left, top, right, bottom)

    private companion object {
        const val MAX_DEPTH = 8
        const val MAX_CHILDREN_PER_NODE = 24
        const val MAX_EVIDENCE_NODES = 200
    }
}

private fun UiNode.flatten(): Sequence<UiNode> = sequence {
    yield(this@flatten)
    children.forEach { child -> yieldAll(child.flatten()) }
}

@Serializable
private data class AiAnalysisInputDto(
    val packageName: String,
    val capturedAtEpochMillis: Long,
    val display: DisplayDto,
    val screenshotAvailable: Boolean,
    val metrics: MetricsDto,
    val ruleFindings: List<RuleFindingDto>,
    val tree: NodeDto,
)

@Serializable
private data class DisplayDto(val widthPx: Int, val heightPx: Int, val density: Float)

@Serializable
private data class MetricsDto(val nodeCount: Int, val maxDepth: Int, val widestLevel: Int)

@Serializable
private data class RuleFindingDto(
    val ruleId: String,
    val severity: String,
    val nodeId: String,
    val message: String,
)

@Serializable
private data class NodeDto(
    val id: String,
    val className: String,
    val bounds: BoundsDto,
    val visible: Boolean,
    val alpha: Float,
    val resourceName: String? = null,
    val textLength: Int? = null,
    val contentDescriptionLength: Int? = null,
    val children: List<NodeDto> = emptyList(),
    val truncatedChildren: Int = 0,
)

private fun NodeDto.nodeIds(): Set<String> = buildSet {
    add(id)
    children.forEach { addAll(it.nodeIds()) }
}

private fun NodeDto.metrics(): MetricsDto {
    val levels = generateSequence(listOf(this)) { level ->
        level.flatMap(NodeDto::children).takeIf(List<NodeDto>::isNotEmpty)
    }.toList()
    return MetricsDto(
        nodeCount = levels.sumOf(List<NodeDto>::size),
        maxDepth = levels.size,
        widestLevel = levels.maxOf(List<NodeDto>::size),
    )
}

@Serializable
private data class BoundsDto(val left: Int, val top: Int, val right: Int, val bottom: Int)
