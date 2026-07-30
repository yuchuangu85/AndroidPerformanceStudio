package com.androidperformancestudio.desktop

import com.androidperformancestudio.presentation.generated.resources.Res
import com.androidperformancestudio.presentation.generated.resources.*
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.ComposeNode
import com.androidperformancestudio.protocol.EdgeInsets
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.ViewAttributes
import com.androidperformancestudio.protocol.ViewNode
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import org.jetbrains.compose.resources.StringResource
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
    val highlightsRenderingRisk: Boolean = false,
)

internal object NodeDetailsPresenter {
    fun present(
        node: UiNode,
        treeDepth: Int,
        language: UiLanguage,
    ): List<DetailSectionModel> {
        val viewNode = node as? ViewNode
        val composeNode = node as? ComposeNode
        val attributes = viewNode?.attributes ?: ViewAttributes()
        val complexity = node.complexity()
        val overlap = node.overlapStats()
        return listOf(
            DetailSectionModel(
                title = localizedStringResource(Res.string.detail_section_render_risks, language),
                rows = riskRows(node, attributes, complexity, overlap, language),
                highlightsRenderingRisk = true,
            ),
            DetailSectionModel(
                title = localizedStringResource(Res.string.detail_section_identity, language),
                rows = listOf(
                    row(language, Res.string.detail_label_class, node.className),
                    row(language, Res.string.detail_label_id, node.id),
                    row(language, Res.string.detail_label_resource, viewNode?.resourceName),
                    row(language, Res.string.detail_label_text, viewNode?.text ?: composeNode?.text
                        ?: viewNode?.attributes?.rawProperties?.let { props ->
                            props["text:mText"] ?: props["text:text"]
                        }),
                    row(language, Res.string.detail_label_content_description, attributes.contentDescription),
                    row(language, Res.string.detail_label_semantics_role, composeNode?.semanticsRole),
                ),
            ),
            DetailSectionModel(
                title = localizedStringResource(Res.string.detail_section_layout, language),
                rows = listOf(
                    row(language, Res.string.detail_label_bounds, node.bounds.format()),
                    row(language, Res.string.detail_label_size, "${node.bounds.width} × ${node.bounds.height}"),
                    row(language, Res.string.detail_label_local_layout_bounds, attributes.layoutBounds?.format()),
                    row(
                        language,
                        Res.string.detail_label_local_layout_size,
                        attributes.layoutBounds?.let { "${it.width} × ${it.height}" },
                    ),
                    row(language, Res.string.detail_label_visibility, attributes.visibility ?: node.visible.toString()),
                    row(language, Res.string.detail_label_tree_depth, treeDepth.toString()),
                    row(language, Res.string.detail_label_direct_children, node.children.size.toString()),
                    row(language, Res.string.detail_label_descendants, complexity.descendants.toString()),
                    row(language, Res.string.detail_label_subtree_depth, complexity.depth.toString()),
                    row(language, Res.string.detail_label_layout_width, attributes.layoutWidth.formatDimension()),
                    row(language, Res.string.detail_label_layout_height, attributes.layoutHeight.formatDimension()),
                    row(language, Res.string.detail_label_layout_params_class, attributes.layoutParamsClass),
                    row(
                        language,
                        Res.string.detail_label_measured_size,
                        attributes.measuredWidth?.let { width ->
                            attributes.measuredHeight?.let { height -> "$width × $height" }
                        },
                    ),
                    row(
                        language,
                        Res.string.detail_label_minimum_size,
                        attributes.minWidth?.let { width ->
                            attributes.minHeight?.let { height -> "$width × $height" }
                        },
                    ),
                    row(language, Res.string.detail_label_padding, attributes.padding.format()),
                    row(language, Res.string.detail_label_margin, attributes.margin.format()),
                    row(
                        language,
                        Res.string.detail_label_scroll,
                        attributes.scrollX?.let { x ->
                            attributes.scrollY?.let { y -> "$x, $y" }
                        },
                    ),
                    row(language, Res.string.detail_label_layout_requested, attributes.layoutRequested),
                ),
            ),
            DetailSectionModel(
                title = localizedStringResource(Res.string.detail_section_drawing, language),
                rows = listOf(
                    row(language, Res.string.detail_label_alpha, node.alpha),
                    row(language, Res.string.detail_label_z, attributes.z),
                    row(language, Res.string.detail_label_elevation, attributes.elevation),
                    row(
                        language,
                        Res.string.detail_label_translation,
                        attributes.translationX?.let { x ->
                            val y = attributes.translationY ?: 0f
                            val z = attributes.translationZ ?: 0f
                            "$x, $y, $z"
                        },
                    ),
                    row(
                        language,
                        Res.string.detail_label_rotation,
                        attributes.rotation?.let { z ->
                            "${attributes.rotationX ?: 0f}, ${attributes.rotationY ?: 0f}, $z"
                        },
                    ),
                    row(
                        language,
                        Res.string.detail_label_scale,
                        attributes.scaleX?.let { x -> "$x, ${attributes.scaleY ?: 1f}" },
                    ),
                    row(
                        language,
                        Res.string.detail_label_pivot,
                        attributes.pivotX?.let { x -> "$x, ${attributes.pivotY ?: 0f}" },
                    ),
                    row(language, Res.string.detail_label_background, attributes.background),
                    row(language, Res.string.detail_label_background_color, attributes.backgroundColor),
                    row(language, Res.string.detail_label_foreground, attributes.foreground),
                    row(language, Res.string.detail_label_clip_bounds, attributes.clipBounds?.format()),
                    row(language, Res.string.detail_label_clip_children, attributes.clipChildren),
                    row(language, Res.string.detail_label_clip_to_padding, attributes.clipToPadding),
                    row(language, Res.string.detail_label_opaque, attributes.opaque),
                    row(language, Res.string.detail_label_will_not_draw, attributes.willNotDraw),
                    row(language, Res.string.detail_label_hardware_accelerated, attributes.hardwareAccelerated),
                    row(language, Res.string.detail_label_layer_type, attributes.layerType),
                ),
            ),
            DetailSectionModel(
                title = localizedStringResource(Res.string.detail_section_interaction, language),
                rows = listOf(
                    row(language, Res.string.detail_label_enabled, attributes.enabled),
                    row(language, Res.string.detail_label_clickable, attributes.clickable),
                    row(language, Res.string.detail_label_long_clickable, attributes.longClickable),
                    row(language, Res.string.detail_label_focusable, attributes.focusable),
                    row(language, Res.string.detail_label_focused, attributes.focused),
                    row(language, Res.string.detail_label_selected, attributes.selected),
                ),
            ),
        ) + rawPropertiesSection(node, attributes, language)
    }

    private fun rawPropertiesSection(
        node: UiNode,
        attributes: ViewAttributes,
        language: UiLanguage,
    ): List<DetailSectionModel> {
        val rawProperties = attributes.rawProperties + ((node as? ComposeNode)?.semanticProperties ?: emptyMap())
        if (rawProperties.isEmpty()) return emptyList()
        return listOf(
            DetailSectionModel(
                title = localizedStringResource(Res.string.detail_section_raw_properties, language),
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
        language: UiLanguage,
    ): List<DetailRowModel> {
        val complexityRisk = complexity.depth > COMPLEXITY_DEPTH_WARNING ||
            complexity.descendants > DESCENDANT_WARNING
        return listOf(
            DetailRowModel(
                label = localizedStringResource(Res.string.detail_label_overdraw_estimate, language),
                value = if (overlap.pairs == 0) {
                    localizedStringResource(Res.string.no_high_overlap_pairs, language)
                } else {
                    localizedStringResource(
                        if (overlap.pairs == 1) Res.string.overlap_pair_single else Res.string.overlap_pairs,
                        language,
                        overlap.pairs,
                        (overlap.maxRatio * 100).roundToInt(),
                    )
                },
                tone = if (overlap.pairs > 0) DetailTone.WARNING else DetailTone.NORMAL,
            ),
            DetailRowModel(
                label = localizedStringResource(Res.string.detail_label_subtree_complexity, language),
                value = localizedStringResource(
                    Res.string.subtree_complexity,
                    language,
                    complexity.descendants,
                    complexity.depth,
                ),
                tone = if (complexityRisk) DetailTone.WARNING else DetailTone.NORMAL,
            ),
            DetailRowModel(
                label = localizedStringResource(Res.string.detail_label_hidden_descendants, language),
                value = complexity.hidden.toString(),
                tone = if (complexity.hidden > 0) DetailTone.INFO else DetailTone.NORMAL,
            ),
            DetailRowModel(
                label = localizedStringResource(Res.string.detail_label_blending, language),
                value = if (node.alpha < 1f) {
                    localizedStringResource(Res.string.blending_alpha, language, node.alpha.toString())
                } else {
                    localizedStringResource(Res.string.alpha_one, language)
                },
                tone = if (node.alpha < 1f) DetailTone.WARNING else DetailTone.NORMAL,
            ),
            DetailRowModel(
                label = localizedStringResource(Res.string.detail_label_layer_cost, language),
                value = attributes.layerType ?: localizedStringResource(Res.string.unavailable, language),
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
        language: UiLanguage,
        label: StringResource,
        value: Any?,
    ): DetailRowModel = DetailRowModel(
        label = localizedStringResource(label, language),
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
