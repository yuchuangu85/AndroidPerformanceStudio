package com.androidperformancestudio.desktop

import com.androidperformancestudio.presentation.generated.resources.ViewerRes
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.ComposeNode
import com.androidperformancestudio.protocol.EdgeInsets
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.ViewAttributes
import com.androidperformancestudio.protocol.ViewNode
import org.jetbrains.compose.resources.StringResource

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
        strings: ViewerStrings,
    ): List<DetailSectionModel> {
        val viewNode = node as? ViewNode
        val composeNode = node as? ComposeNode
        val attributes = viewNode?.attributes ?: ViewAttributes()
        val complexity = node.complexity()
        val overlap = node.overlapStats()
        return listOf(
            DetailSectionModel(
                title = strings.detailSection(ViewerRes.detail_section_render_risks),
                rows = riskRows(node, attributes, complexity, overlap, strings),
                highlightsRenderingRisk = true,
            ),
            DetailSectionModel(
                title = strings.detailSection(ViewerRes.detail_section_identity),
                rows = listOf(
                    row(strings, ViewerRes.detail_label_class, node.className),
                    row(strings, ViewerRes.detail_label_id, node.id),
                    row(strings, ViewerRes.detail_label_resource, viewNode?.resourceName),
                    row(strings, ViewerRes.detail_label_text, viewNode?.text ?: composeNode?.text
                        ?: viewNode?.attributes?.rawProperties?.let { props ->
                            props["text:mText"] ?: props["text:text"]
                        }),
                    row(strings, ViewerRes.detail_label_content_description, attributes.contentDescription),
                    row(strings, ViewerRes.detail_label_semantics_role, composeNode?.semanticsRole),
                ),
            ),
            DetailSectionModel(
                title = strings.detailSection(ViewerRes.detail_section_layout),
                rows = listOf(
                    row(strings, ViewerRes.detail_label_bounds, node.bounds.format()),
                    row(strings, ViewerRes.detail_label_size, "${node.bounds.width} × ${node.bounds.height}"),
                    row(strings, ViewerRes.detail_label_local_layout_bounds, attributes.layoutBounds?.format()),
                    row(
                        strings,
                        ViewerRes.detail_label_local_layout_size,
                        attributes.layoutBounds?.let { "${it.width} × ${it.height}" },
                    ),
                    row(strings, ViewerRes.detail_label_visibility, attributes.visibility ?: node.visible.toString()),
                    row(strings, ViewerRes.detail_label_tree_depth, treeDepth.toString()),
                    row(strings, ViewerRes.detail_label_direct_children, node.children.size.toString()),
                    row(strings, ViewerRes.detail_label_descendants, complexity.descendants.toString()),
                    row(strings, ViewerRes.detail_label_subtree_depth, complexity.depth.toString()),
                    row(strings, ViewerRes.detail_label_layout_width, attributes.layoutWidth.formatDimension()),
                    row(strings, ViewerRes.detail_label_layout_height, attributes.layoutHeight.formatDimension()),
                    row(strings, ViewerRes.detail_label_layout_params_class, attributes.layoutParamsClass),
                    row(
                        strings,
                        ViewerRes.detail_label_measured_size,
                        attributes.measuredWidth?.let { width ->
                            attributes.measuredHeight?.let { height -> "$width × $height" }
                        },
                    ),
                    row(
                        strings,
                        ViewerRes.detail_label_minimum_size,
                        attributes.minWidth?.let { width ->
                            attributes.minHeight?.let { height -> "$width × $height" }
                        },
                    ),
                    row(strings, ViewerRes.detail_label_padding, attributes.padding.format()),
                    row(strings, ViewerRes.detail_label_margin, attributes.margin.format()),
                    row(
                        strings,
                        ViewerRes.detail_label_scroll,
                        attributes.scrollX?.let { x ->
                            attributes.scrollY?.let { y -> "$x, $y" }
                        },
                    ),
                    row(strings, ViewerRes.detail_label_layout_requested, attributes.layoutRequested),
                ),
            ),
            DetailSectionModel(
                title = strings.detailSection(ViewerRes.detail_section_drawing),
                rows = listOf(
                    row(strings, ViewerRes.detail_label_alpha, node.alpha),
                    row(strings, ViewerRes.detail_label_z, attributes.z),
                    row(strings, ViewerRes.detail_label_elevation, attributes.elevation),
                    row(
                        strings,
                        ViewerRes.detail_label_translation,
                        attributes.translationX?.let { x ->
                            val y = attributes.translationY ?: 0f
                            val z = attributes.translationZ ?: 0f
                            "$x, $y, $z"
                        },
                    ),
                    row(
                        strings,
                        ViewerRes.detail_label_rotation,
                        attributes.rotation?.let { z ->
                            "${attributes.rotationX ?: 0f}, ${attributes.rotationY ?: 0f}, $z"
                        },
                    ),
                    row(
                        strings,
                        ViewerRes.detail_label_scale,
                        attributes.scaleX?.let { x -> "$x, ${attributes.scaleY ?: 1f}" },
                    ),
                    row(
                        strings,
                        ViewerRes.detail_label_pivot,
                        attributes.pivotX?.let { x -> "$x, ${attributes.pivotY ?: 0f}" },
                    ),
                    row(strings, ViewerRes.detail_label_background, attributes.background),
                    row(strings, ViewerRes.detail_label_background_color, attributes.backgroundColor),
                    row(strings, ViewerRes.detail_label_foreground, attributes.foreground),
                    row(strings, ViewerRes.detail_label_clip_bounds, attributes.clipBounds?.format()),
                    row(strings, ViewerRes.detail_label_clip_children, attributes.clipChildren),
                    row(strings, ViewerRes.detail_label_clip_to_padding, attributes.clipToPadding),
                    row(strings, ViewerRes.detail_label_opaque, attributes.opaque),
                    row(strings, ViewerRes.detail_label_will_not_draw, attributes.willNotDraw),
                    row(strings, ViewerRes.detail_label_hardware_accelerated, attributes.hardwareAccelerated),
                    row(strings, ViewerRes.detail_label_layer_type, attributes.layerType),
                ),
            ),
            DetailSectionModel(
                title = strings.detailSection(ViewerRes.detail_section_interaction),
                rows = listOf(
                    row(strings, ViewerRes.detail_label_enabled, attributes.enabled),
                    row(strings, ViewerRes.detail_label_clickable, attributes.clickable),
                    row(strings, ViewerRes.detail_label_long_clickable, attributes.longClickable),
                    row(strings, ViewerRes.detail_label_focusable, attributes.focusable),
                    row(strings, ViewerRes.detail_label_focused, attributes.focused),
                    row(strings, ViewerRes.detail_label_selected, attributes.selected),
                ),
            ),
        ) + rawPropertiesSection(node, attributes, strings)
    }

    private fun rawPropertiesSection(
        node: UiNode,
        attributes: ViewAttributes,
        strings: ViewerStrings,
    ): List<DetailSectionModel> {
        val rawProperties = attributes.rawProperties + ((node as? ComposeNode)?.semanticProperties ?: emptyMap())
        if (rawProperties.isEmpty()) return emptyList()
        return listOf(
            DetailSectionModel(
                title = strings.detailSection(ViewerRes.detail_section_raw_properties),
                rows = rawProperties
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
                label = strings.detailLabel(ViewerRes.detail_label_overdraw_estimate),
                value = if (overlap.pairs == 0) {
                    strings.noHighOverlapPairs()
                } else {
                    strings.overlapPairs(overlap.pairs, overlap.maxRatio)
                },
                tone = if (overlap.pairs > 0) DetailTone.WARNING else DetailTone.NORMAL,
            ),
            DetailRowModel(
                label = strings.detailLabel(ViewerRes.detail_label_subtree_complexity),
                value = strings.subtreeComplexity(complexity.descendants, complexity.depth),
                tone = if (complexityRisk) DetailTone.WARNING else DetailTone.NORMAL,
            ),
            DetailRowModel(
                label = strings.detailLabel(ViewerRes.detail_label_hidden_descendants),
                value = complexity.hidden.toString(),
                tone = if (complexity.hidden > 0) DetailTone.INFO else DetailTone.NORMAL,
            ),
            DetailRowModel(
                label = strings.detailLabel(ViewerRes.detail_label_blending),
                value = strings.blending(node.alpha),
                tone = if (node.alpha < 1f) DetailTone.WARNING else DetailTone.NORMAL,
            ),
            DetailRowModel(
                label = strings.detailLabel(ViewerRes.detail_label_layer_cost),
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
        label: StringResource,
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
