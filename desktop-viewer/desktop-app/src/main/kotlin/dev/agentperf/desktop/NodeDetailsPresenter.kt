package dev.agentperf.desktop

import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.EdgeInsets
import dev.agentperf.protocol.UiNode
import dev.agentperf.protocol.ViewAttributes
import dev.agentperf.protocol.ViewNode

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
    val highlightsRenderingRisk: Boolean = false,
)

internal object NodeDetailsPresenter {
    fun present(
        node: UiNode,
        treeDepth: Int,
        strings: ViewerStrings = ViewerStrings.English,
    ): List<DetailSectionModel> {
        val attributes = (node as? ViewNode)?.attributes ?: ViewAttributes()
        val complexity = node.complexity()
        val overlap = node.overlapStats()
        return listOf(
            DetailSectionModel(
                title = strings.detailSection("RENDER RISKS"),
                rows = riskRows(node, attributes, complexity, overlap, strings),
                highlightsRenderingRisk = true,
            ),
            DetailSectionModel(
                title = strings.detailSection("IDENTITY"),
                rows = listOf(
                    row(strings, "Class", node.className),
                    row(strings, "ID", node.id),
                    row(strings, "Resource", (node as? ViewNode)?.resourceName),
                    row(strings, "Text", (node as? ViewNode)?.text),
                    row(strings, "Content description", attributes.contentDescription),
                ),
            ),
            DetailSectionModel(
                title = strings.detailSection("LAYOUT"),
                rows = listOf(
                    row(strings, "Bounds", node.bounds.format()),
                    row(strings, "Size", "${node.bounds.width} × ${node.bounds.height}"),
                    row(strings, "Local layout bounds", attributes.layoutBounds?.format()),
                    row(
                        strings,
                        "Local layout size",
                        attributes.layoutBounds?.let { "${it.width} × ${it.height}" },
                    ),
                    row(strings, "Visibility", attributes.visibility ?: node.visible.toString()),
                    row(strings, "Tree depth", treeDepth.toString()),
                    row(strings, "Direct children", node.children.size.toString()),
                    row(strings, "Descendants", complexity.descendants.toString()),
                    row(strings, "Subtree depth", complexity.depth.toString()),
                    row(strings, "Layout width", attributes.layoutWidth.formatDimension()),
                    row(strings, "Layout height", attributes.layoutHeight.formatDimension()),
                    row(strings, "Layout params class", attributes.layoutParamsClass),
                    row(
                        strings,
                        "Measured size",
                        attributes.measuredWidth?.let { width ->
                            attributes.measuredHeight?.let { height -> "$width × $height" }
                        },
                    ),
                    row(
                        strings,
                        "Minimum size",
                        attributes.minWidth?.let { width ->
                            attributes.minHeight?.let { height -> "$width × $height" }
                        },
                    ),
                    row(strings, "Padding", attributes.padding.format()),
                    row(strings, "Margin", attributes.margin.format()),
                    row(
                        strings,
                        "Scroll",
                        attributes.scrollX?.let { x ->
                            attributes.scrollY?.let { y -> "$x, $y" }
                        },
                    ),
                    row(strings, "Layout requested", attributes.layoutRequested),
                ),
            ),
            DetailSectionModel(
                title = strings.detailSection("DRAWING"),
                rows = listOf(
                    row(strings, "Alpha", node.alpha),
                    row(strings, "Z", attributes.z),
                    row(strings, "Elevation", attributes.elevation),
                    row(
                        strings,
                        "Translation",
                        attributes.translationX?.let { x ->
                            val y = attributes.translationY ?: 0f
                            val z = attributes.translationZ ?: 0f
                            "$x, $y, $z"
                        },
                    ),
                    row(
                        strings,
                        "Rotation",
                        attributes.rotation?.let { z ->
                            "${attributes.rotationX ?: 0f}, ${attributes.rotationY ?: 0f}, $z"
                        },
                    ),
                    row(
                        strings,
                        "Scale",
                        attributes.scaleX?.let { x -> "$x, ${attributes.scaleY ?: 1f}" },
                    ),
                    row(
                        strings,
                        "Pivot",
                        attributes.pivotX?.let { x -> "$x, ${attributes.pivotY ?: 0f}" },
                    ),
                    row(strings, "Background", attributes.background),
                    row(strings, "Background color", attributes.backgroundColor),
                    row(strings, "Foreground", attributes.foreground),
                    row(strings, "Clip bounds", attributes.clipBounds?.format()),
                    row(strings, "Clip children", attributes.clipChildren),
                    row(strings, "Clip to padding", attributes.clipToPadding),
                    row(strings, "Opaque", attributes.opaque),
                    row(strings, "Will not draw", attributes.willNotDraw),
                    row(strings, "Hardware accelerated", attributes.hardwareAccelerated),
                    row(strings, "Layer type", attributes.layerType),
                ),
            ),
            DetailSectionModel(
                title = strings.detailSection("INTERACTION"),
                rows = listOf(
                    row(strings, "Enabled", attributes.enabled),
                    row(strings, "Clickable", attributes.clickable),
                    row(strings, "Long clickable", attributes.longClickable),
                    row(strings, "Focusable", attributes.focusable),
                    row(strings, "Focused", attributes.focused),
                    row(strings, "Selected", attributes.selected),
                ),
            ),
        ) + rawPropertiesSection(attributes, strings)
    }

    private fun rawPropertiesSection(
        attributes: ViewAttributes,
        strings: ViewerStrings,
    ): List<DetailSectionModel> {
        if (attributes.rawProperties.isEmpty()) return emptyList()
        return listOf(
            DetailSectionModel(
                title = strings.detailSection("RAW PROPERTIES"),
                rows = attributes.rawProperties
                    .toSortedMap()
                    .map { (name, value) -> DetailRowModel(label = name, value = value) },
            ),
        )
    }

    private fun riskRows(
        node: UiNode,
        attributes: ViewAttributes,
        complexity: Complexity,
        overlap: OverlapStats,
        strings: ViewerStrings,
    ): List<DetailRowModel> {
        val complexityRisk = complexity.depth > COMPLEXITY_DEPTH_WARNING ||
            complexity.descendants > DESCENDANT_WARNING
        return listOf(
            DetailRowModel(
                label = strings.detailLabel("Overdraw estimate"),
                value = if (overlap.pairs == 0) {
                    strings.noHighOverlapPairs()
                } else {
                    strings.overlapPairs(overlap.pairs, overlap.maxRatio)
                },
                tone = if (overlap.pairs > 0) DetailTone.WARNING else DetailTone.NORMAL,
            ),
            DetailRowModel(
                label = strings.detailLabel("Subtree complexity"),
                value = strings.subtreeComplexity(complexity.descendants, complexity.depth),
                tone = if (complexityRisk) DetailTone.WARNING else DetailTone.NORMAL,
            ),
            DetailRowModel(
                label = strings.detailLabel("Hidden descendants"),
                value = complexity.hidden.toString(),
                tone = if (complexity.hidden > 0) DetailTone.INFO else DetailTone.NORMAL,
            ),
            DetailRowModel(
                label = strings.detailLabel("Blending"),
                value = strings.blending(node.alpha),
                tone = if (node.alpha < 1f) DetailTone.WARNING else DetailTone.NORMAL,
            ),
            DetailRowModel(
                label = strings.detailLabel("Layer cost"),
                value = attributes.layerType ?: strings.unavailable,
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
        strings: ViewerStrings,
        label: String,
        value: Any?,
    ): DetailRowModel = DetailRowModel(
        label = strings.detailLabel(label),
        value = value?.toString() ?: "—",
    )

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
