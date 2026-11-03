package com.androidperformancestudio.desktop

import com.androidperformancestudio.analysis.AnalysisReport
import com.androidperformancestudio.analysis.Finding
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.ComposeNode
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.ViewNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

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

private const val MAX_SUMMARY_NODES = 200
private const val MAX_SUMMARY_DEPTH = 8
private const val MAX_SUMMARY_CHILDREN = 24
private const val MAX_SUMMARY_BYTES = 49_152
private const val MAX_FINDINGS = 24
private const val MAX_METADATA_CHARS = 160
private const val MAX_PARENT_SEARCH_NODES = 20_000
private val PERFORMANCE_RULE_PARAMETERS = setOf("count", "threshold", "depth", "ratioPercent")

/** A truncation flag alone is not proof of a bounded, explicitly incomplete projection. */
public val AiAnalysisInput.hasBoundedSummary: Boolean
    get() {
        if (!treeTruncated || json.encodeToByteArray().size > MAX_SUMMARY_BYTES) return false
        return runCatching {
            val document = Json.parseToJsonElement(json) as? JsonObject ?: return@runCatching false
            val coverage = document["coverage"] as? JsonObject ?: return@runCatching false
            val version = coverage["schemaVersion"] as? JsonPrimitive ?: return@runCatching false
            val complete = coverage["complete"] as? JsonPrimitive ?: return@runCatching false
            val included = coverage["includedNodeCount"] as? JsonPrimitive ?: return@runCatching false
            if (version.isString || version.intOrNull != 1 || complete.isString || complete.booleanOrNull != false || included.isString) {
                return@runCatching false
            }
            val expected = included.intOrNull ?: return@runCatching false
            if (expected !in 1..MAX_SUMMARY_NODES) return@runCatching false
            val tree = document["tree"] as? JsonObject ?: return@runCatching false
            val pending = ArrayDeque<Pair<JsonObject, Int>>()
            pending.addLast(tree to 0)
            var count = 0
            var selectedIncluded = selectedNodeId == null
            while (pending.isNotEmpty()) {
                val (node, depth) = pending.removeLast()
                if (++count > MAX_SUMMARY_NODES || depth > MAX_SUMMARY_DEPTH + 1) return@runCatching false
                val id = node["id"] as? JsonPrimitive ?: return@runCatching false
                if (!id.isString) return@runCatching false
                if (id.content == selectedNodeId) selectedIncluded = true
                val children = node["children"]
                if (children != null && children !is JsonArray) return@runCatching false
                val entries = children as? JsonArray ?: JsonArray(emptyList())
                if (entries.size > MAX_SUMMARY_CHILDREN) return@runCatching false
                entries.forEach { child ->
                    val childNode = child as? JsonObject ?: return@runCatching false
                    pending.addLast(childNode to depth + 1)
                }
            }
            count == expected && selectedIncluded
        }.getOrDefault(false)
    }

internal class AiAnalysisInputBuilder(
    private val json: Json = Json {
        encodeDefaults = false
    },
) {
    fun build(
        snapshot: LayoutSnapshot,
        activeRoot: UiNode,
        analysis: AnalysisReport,
        selectedNode: UiNode? = null,
    ): AiAnalysisInput {
        val evidenceNodes = selectedNode?.let(::listOf) ?: activeRoot.boundedNodes(MAX_SUMMARY_NODES + 1)
        val omittedSources = if (evidenceNodes.size > MAX_SUMMARY_NODES) {
            (analysis.metrics.nodeCount - MAX_SUMMARY_NODES).coerceAtLeast(1)
        } else 0
        val sourceEvidence = evidenceNodes.take(MAX_SUMMARY_NODES).map { node ->
            LayoutSourceEvidence(node.id, node.className, (node as? ViewNode)?.resourceName)
        }
        val parent = selectedNode?.takeUnless { it.id == activeRoot.id }?.let { activeRoot.parentOf(it.id) }
        val minimumNodes = if (parent == null) 1 else 2
        var nodeLimit = MAX_SUMMARY_NODES
        var findingLimit = MAX_FINDINGS
        while (true) {
            val budget = ProjectionBudget(nodeLimit - if (parent == null) 0 else 1)
            val selected = (selectedNode ?: activeRoot).toDto(0, budget)
            val tree = parent?.toContextDto(selected, budget) ?: selected
            val scopedNodeIds = tree.nodeIds()
            val findings = analysis.findings.asSequence()
                .filter { it.nodeId in scopedNodeIds }
                .take(findingLimit + 1)
                .toList()
            val projectedFindings = findings.take(findingLimit).map { finding ->
                RuleFindingDto(
                    ruleId = finding.ruleId.bounded(MAX_METADATA_CHARS),
                    severity = finding.severity.name,
                    nodeId = finding.nodeId,
                    parameters = finding.performanceParameters(),
                ).also {
                    if (it.ruleId != finding.ruleId) budget.textTruncated = true
                }
            }
            val metrics = tree.metrics()
            val findingsTruncated = findings.size > findingLimit ||
                ((selectedNode == null || selectedNode.id == activeRoot.id) && analysis.findings.size > projectedFindings.size)
            val complete = !budget.treeTruncated && !budget.textTruncated &&
                !findingsTruncated && omittedSources == 0
            val encoded = json.encodeToString(
                AiAnalysisInputDto(
                    display = DisplayDto(
                        widthPx = snapshot.display.widthPx,
                        heightPx = snapshot.display.heightPx,
                        density = snapshot.display.density,
                    ),
                    metrics = metrics,
                    layoutMetrics = MetricsDto(
                        nodeCount = analysis.metrics.nodeCount,
                        maxDepth = analysis.metrics.maxDepth,
                        widestLevel = analysis.metrics.widestLevel,
                    ),
                    ruleFindings = projectedFindings,
                    tree = tree,
                    coverage = CoverageDto(
                        schemaVersion = 1,
                        complete = complete,
                        includedNodeCount = metrics.nodeCount,
                        findingsTruncated = findingsTruncated,
                        metadataTruncated = budget.textTruncated,
                        scope = if (selectedNode == null) "active-window" else "selected-subtree-with-parent-context",
                        limitations = if (complete) "Included layout scope is complete." else
                            "Partial layout evidence only. Omitted nodes and findings were not inspected; do not infer their absence.",
                    ),
                ),
            )
            if (encoded.encodeToByteArray().size <= MAX_SUMMARY_BYTES) {
                return AiAnalysisInput(
                    json = encoded,
                    sourceEvidence = sourceEvidence,
                    selectedNodeId = selectedNode?.id,
                    omittedSourceEvidenceCount = omittedSources,
                    treeTruncated = !complete,
                )
            }
            // Rebuild from the immutable capture; never cut serialized JSON or lose the selected node.
            when {
                findingLimit > 0 -> findingLimit /= 2
                nodeLimit > minimumNodes -> nodeLimit = (nodeLimit / 2).coerceAtLeast(minimumNodes)
                else -> error("Layout selection metadata exceeds the analysis byte budget")
            }
        }
    }

    private class ProjectionBudget(var remaining: Int, var treeTruncated: Boolean = false, var textTruncated: Boolean = false)

    private fun UiNode.toDto(depth: Int, budget: ProjectionBudget): NodeDto {
        budget.remaining--
        val projectedChildren = mutableListOf<NodeDto>()
        if (depth < MAX_SUMMARY_DEPTH) {
            for (child in children.take(MAX_SUMMARY_CHILDREN)) {
                if (budget.remaining == 0) break
                projectedChildren += child.toDto(depth + 1, budget)
            }
        }
        val omitted = children.size - projectedChildren.size
        if (omitted > 0) budget.treeTruncated = true
        return shallowDto(budget).copy(children = projectedChildren, truncatedChildren = omitted)
    }

    private fun UiNode.toContextDto(selected: NodeDto, budget: ProjectionBudget): NodeDto =
        shallowDto(budget).copy(children = listOf(selected), truncatedChildren = (children.size - 1).coerceAtLeast(0), contextOnly = true)

    private fun UiNode.shallowDto(budget: ProjectionBudget): NodeDto {
        fun text(value: String): String = value.bounded(MAX_METADATA_CHARS).also {
            if (it.length != value.length) budget.textTruncated = true
        }
        return NodeDto(
            id = id,
            className = text(className.substringAfterLast('.')),
            bounds = bounds.toDto(),
            visible = visible,
            alpha = alpha,
            textLength = when (this) {
                is ViewNode -> text?.length
                is ComposeNode -> text?.length
            },
        )
    }

    private fun UiNode.parentOf(nodeId: String): UiNode? {
        val pending = ArrayDeque<Pair<UiNode, Iterator<UiNode>>>()
        pending.addLast(this to children.iterator())
        var visited = 0
        while (pending.isNotEmpty() && visited < MAX_PARENT_SEARCH_NODES) {
            val (parent, iterator) = pending.last()
            if (!iterator.hasNext()) {
                pending.removeLast()
                continue
            }
            val child = iterator.next()
            visited++
            if (child.id == nodeId) return parent
            pending.addLast(child to child.children.iterator())
        }
        return null
    }

    private fun Bounds.toDto() = BoundsDto(left, top, right, bottom)

    private fun Finding.performanceParameters(): JsonObject = JsonObject(buildMap {
        for ((key, value) in arguments) {
            if (key !in PERFORMANCE_RULE_PARAMETERS || value.length > 64) continue
            val number = value.toLongOrNull()?.let { JsonPrimitive(it) }
                ?: value.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { JsonPrimitive(it) }
                ?: continue
            put(key, number)
        }
    })
}

private fun UiNode.boundedNodes(limit: Int): List<UiNode> {
    val result = mutableListOf(this)
    val pending = ArrayDeque<Iterator<UiNode>>()
    pending.addLast(children.iterator())
    while (pending.isNotEmpty() && result.size < limit) {
        val iterator = pending.last()
        if (!iterator.hasNext()) {
            pending.removeLast()
            continue
        }
        val child = iterator.next()
        result += child
        pending.addLast(child.children.iterator())
    }
    return result
}

private fun String.bounded(limit: Int): String {
    val end = minOf(length, limit)
    return substring(0, if (end < length && end > 0 && this[end - 1].isHighSurrogate()) end - 1 else end)
}

@Serializable
private data class AiAnalysisInputDto(
    val display: DisplayDto,
    val metrics: MetricsDto,
    val layoutMetrics: MetricsDto,
    val ruleFindings: List<RuleFindingDto>,
    val tree: NodeDto,
    val coverage: CoverageDto,
)

@Serializable
private data class CoverageDto(
    val schemaVersion: Int,
    val complete: Boolean,
    val includedNodeCount: Int,
    val findingsTruncated: Boolean,
    val metadataTruncated: Boolean,
    val scope: String,
    val limitations: String,
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
    val parameters: JsonObject = JsonObject(emptyMap()),
)

@Serializable
private data class NodeDto(
    val id: String,
    val className: String,
    val bounds: BoundsDto,
    val visible: Boolean,
    val alpha: Float,
    val textLength: Int? = null,
    val children: List<NodeDto> = emptyList(),
    val truncatedChildren: Int = 0,
    val contextOnly: Boolean = false,
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
