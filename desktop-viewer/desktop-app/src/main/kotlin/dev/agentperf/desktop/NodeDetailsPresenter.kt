package dev.agentperf.desktop

import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.EdgeInsets
import dev.agentperf.protocol.UiNode
import dev.agentperf.protocol.ViewAttributes
import dev.agentperf.protocol.ViewNode
import kotlin.math.roundToInt

enum class DetailTone {
    NORMAL,
    INFO,
    WARNING,
    ERROR,
}

data class DetailRowModel(
    val label: String,
    val value: String,
    val tone: DetailTone = DetailTone.NORMAL,
)

data class DetailSectionModel(
    val title: String,
    val rows: List<DetailRowModel>,
)

internal object NodeDetailsPresenter {
    fun present(
        node: UiNode,
        treeDepth: Int,
    ): List<DetailSectionModel> {
        val attributes = (node as? ViewNode)?.attributes ?: ViewAttributes()
        val complexity = node.complexity()
        val overlap = node.overlapStats()
        return listOf(
            DetailSectionModel(
                title = "RENDER RISKS",
                rows = riskRows(node, attributes, complexity, overlap),
            ),
            DetailSectionModel(
                title = "IDENTITY",
                rows = listOf(
                    row("Class", node.className),
                    row("ID", node.id),
                    row("Resource", (node as? ViewNode)?.resourceName),
                    row("Text", (node as? ViewNode)?.text),
                    row("Content description", attributes.contentDescription),
                ),
            ),
            DetailSectionModel(
                title = "LAYOUT",
                rows = listOf(
                    row("Bounds", node.bounds.format()),
                    row("Size", "${node.bounds.width} × ${node.bounds.height}"),
                    row("Visibility", attributes.visibility ?: node.visible.toString()),
                    row("Tree depth", treeDepth.toString()),
                    row("Direct children", node.children.size.toString()),
                    row("Descendants", complexity.descendants.toString()),
                    row("Subtree depth", complexity.depth.toString()),
                    row("Layout width", attributes.layoutWidth.formatDimension()),
                    row("Layout height", attributes.layoutHeight.formatDimension()),
                    row(
                        "Measured size",
                        attributes.measuredWidth?.let { width ->
                            attributes.measuredHeight?.let { height -> "$width × $height" }
                        },
                    ),
                    row(
                        "Minimum size",
                        attributes.minWidth?.let { width ->
                            attributes.minHeight?.let { height -> "$width × $height" }
                        },
                    ),
                    row("Padding", attributes.padding.format()),
                    row("Margin", attributes.margin.format()),
                    row(
                        "Scroll",
                        attributes.scrollX?.let { x ->
                            attributes.scrollY?.let { y -> "$x, $y" }
                        },
                    ),
                    row("Layout requested", attributes.layoutRequested),
                ),
            ),
            DetailSectionModel(
                title = "DRAWING",
                rows = listOf(
                    row("Alpha", node.alpha),
                    row("Z", attributes.z),
                    row("Elevation", attributes.elevation),
                    row(
                        "Translation",
                        attributes.translationX?.let { x ->
                            val y = attributes.translationY ?: 0f
                            val z = attributes.translationZ ?: 0f
                            "$x, $y, $z"
                        },
                    ),
                    row(
                        "Rotation",
                        attributes.rotation?.let { z ->
                            "${attributes.rotationX ?: 0f}, ${attributes.rotationY ?: 0f}, $z"
                        },
                    ),
                    row(
                        "Scale",
                        attributes.scaleX?.let { x -> "$x, ${attributes.scaleY ?: 1f}" },
                    ),
                    row(
                        "Pivot",
                        attributes.pivotX?.let { x -> "$x, ${attributes.pivotY ?: 0f}" },
                    ),
                    row("Background", attributes.background),
                    row("Background color", attributes.backgroundColor),
                    row("Foreground", attributes.foreground),
                    row("Clip bounds", attributes.clipBounds?.format()),
                    row("Clip children", attributes.clipChildren),
                    row("Clip to padding", attributes.clipToPadding),
                    row("Opaque", attributes.opaque),
                    row("Will not draw", attributes.willNotDraw),
                    row("Hardware accelerated", attributes.hardwareAccelerated),
                    row("Layer type", attributes.layerType),
                ),
            ),
            DetailSectionModel(
                title = "INTERACTION",
                rows = listOf(
                    row("Enabled", attributes.enabled),
                    row("Clickable", attributes.clickable),
                    row("Long clickable", attributes.longClickable),
                    row("Focusable", attributes.focusable),
                    row("Focused", attributes.focused),
                    row("Selected", attributes.selected),
                ),
            ),
        )
    }

    private fun riskRows(
        node: UiNode,
        attributes: ViewAttributes,
        complexity: Complexity,
        overlap: OverlapStats,
    ): List<DetailRowModel> {
        val complexityRisk = complexity.depth > COMPLEXITY_DEPTH_WARNING ||
            complexity.descendants > DESCENDANT_WARNING
        return listOf(
            DetailRowModel(
                label = "Overdraw estimate",
                value = if (overlap.pairs == 0) {
                    "No high-overlap child pairs · structural"
                } else {
                    "${overlap.pairs} ${if (overlap.pairs == 1) "pair" else "pairs"} · " +
                        "max ${(overlap.maxRatio * 100).roundToInt()}% · structural"
                },
                tone = if (overlap.pairs > 0) DetailTone.WARNING else DetailTone.NORMAL,
            ),
            DetailRowModel(
                label = "Subtree complexity",
                value = "${complexity.descendants} descendants · depth ${complexity.depth}",
                tone = if (complexityRisk) DetailTone.WARNING else DetailTone.NORMAL,
            ),
            DetailRowModel(
                label = "Hidden descendants",
                value = complexity.hidden.toString(),
                tone = if (complexity.hidden > 0) DetailTone.INFO else DetailTone.NORMAL,
            ),
            DetailRowModel(
                label = "Blending",
                value = if (node.alpha < 1f) {
                    "Alpha ${node.alpha} requires blending"
                } else {
                    "Alpha 1.0"
                },
                tone = if (node.alpha < 1f) DetailTone.WARNING else DetailTone.NORMAL,
            ),
            DetailRowModel(
                label = "Layer cost",
                value = attributes.layerType ?: "Unavailable",
                tone = if (attributes.layerType == "SOFTWARE") {
                    DetailTone.WARNING
                } else {
                    DetailTone.NORMAL
                },
            ),
        )
    }

    private fun UiNode.complexity(): Complexity {
        if (children.isEmpty()) return Complexity(descendants = 0, depth = 1, hidden = 0)
        val childrenComplexity = children.map { it.complexity() }
        return Complexity(
            descendants = children.size + childrenComplexity.sumOf { it.descendants },
            depth = 1 + childrenComplexity.maxOf { it.depth },
            hidden = children.count { !it.visible || it.alpha <= 0f } +
                childrenComplexity.sumOf { it.hidden },
        )
    }

    private fun UiNode.overlapStats(): OverlapStats {
        var pairs = 0
        var maxRatio = 0f
        for (firstIndex in children.indices) {
            for (secondIndex in firstIndex + 1 until children.size) {
                val ratio = overlapRatio(children[firstIndex].bounds, children[secondIndex].bounds)
                if (ratio >= SIGNIFICANT_OVERLAP_RATIO) {
                    pairs += 1
                    maxRatio = maxOf(maxRatio, ratio)
                }
            }
        }
        return OverlapStats(pairs = pairs, maxRatio = maxRatio)
    }

    private fun overlapRatio(first: Bounds, second: Bounds): Float {
        val width = (minOf(first.right, second.right) - maxOf(first.left, second.left))
            .coerceAtLeast(0)
        val height = (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top))
            .coerceAtLeast(0)
        val smallerArea = minOf(
            first.width.toLong() * first.height,
            second.width.toLong() * second.height,
        )
        if (smallerArea == 0L) return 0f
        return width.toLong() * height / smallerArea.toFloat()
    }

    private fun row(
        label: String,
        value: Any?,
    ): DetailRowModel = DetailRowModel(label = label, value = value?.toString() ?: "—")

    private fun Bounds.format(): String = "$left, $top, $right, $bottom"

    private fun EdgeInsets?.format(): String? =
        this?.let { "$left, $top, $right, $bottom" }

    private fun Int?.formatDimension(): String? = when (this) {
        null -> null
        -1 -> "MATCH_PARENT (-1)"
        -2 -> "WRAP_CONTENT (-2)"
        else -> toString()
    }

    private data class Complexity(
        val descendants: Int,
        val depth: Int,
        val hidden: Int,
    )

    private data class OverlapStats(
        val pairs: Int,
        val maxRatio: Float,
    )

    private const val SIGNIFICANT_OVERLAP_RATIO = 0.8f
    private const val COMPLEXITY_DEPTH_WARNING = 10
    private const val DESCENDANT_WARNING = 50
}
