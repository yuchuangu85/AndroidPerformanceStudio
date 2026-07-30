package com.androidperformancestudio.desktop

import com.androidperformancestudio.analysis.AnalysisReport
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.ComposeNode
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.ViewNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class AiAnalysisInput(val json: String)

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
    ): AiAnalysisInput = AiAnalysisInput(
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
                metrics = MetricsDto(
                    nodeCount = analysis.metrics.nodeCount,
                    maxDepth = analysis.metrics.maxDepth,
                    widestLevel = analysis.metrics.widestLevel,
                ),
                ruleFindings = analysis.findings.map {
                    RuleFindingDto(
                        ruleId = it.ruleId,
                        severity = it.severity.name,
                        nodeId = it.nodeId,
                        message = it.message,
                    )
                },
                tree = activeRoot.toDto(depth = 0),
            ),
        ),
    )

    private fun UiNode.toDto(depth: Int): NodeDto = NodeDto(
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
        children = if (depth >= MAX_DEPTH) {
            emptyList()
        } else {
            children.take(MAX_CHILDREN_PER_NODE).map { it.toDto(depth + 1) }
        },
        truncatedChildren = (children.size - MAX_CHILDREN_PER_NODE).coerceAtLeast(0),
    )

    private fun Bounds.toDto() = BoundsDto(left, top, right, bottom)

    private companion object {
        const val MAX_DEPTH = 8
        const val MAX_CHILDREN_PER_NODE = 24
    }
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

@Serializable
private data class BoundsDto(val left: Int, val top: Int, val right: Int, val bottom: Int)
