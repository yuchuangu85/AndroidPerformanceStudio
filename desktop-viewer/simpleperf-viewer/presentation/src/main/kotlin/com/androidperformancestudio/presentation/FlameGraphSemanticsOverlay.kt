package com.androidperformancestudio.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.visualization.VisibleFlameLayout
import com.androidperformancestudio.visualization.VisibleFlameNode
import kotlin.math.roundToInt

internal data class FlameGraphSemanticNode(
    val nodeId: FlameCallNodeId,
    val contentDescription: String,
    val stateDescription: String,
    val selected: Boolean,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

internal object FlameGraphSemanticsPresenter {
    @Suppress("LongParameterList")
    fun nodes(
        snapshot: FlameGraphSnapshot,
        layout: VisibleFlameLayout,
        selectedNodeId: FlameCallNodeId?,
        hoveredNodeId: FlameCallNodeId? = null,
        contextNodeId: FlameCallNodeId? = null,
        language: UiLanguage = UiLanguage.ENGLISH,
    ): List<FlameGraphSemanticNode> {
        val visibleNodes =
            layout.nodes.mapNotNull { node ->
                node.toSemanticNode(snapshot, selectedNodeId, hoveredNodeId, contextNodeId, language)
            }
        val visibleIds = visibleNodes.mapTo(mutableSetOf()) { it.nodeId }
        val selectedNode =
            selectedNodeId
                ?.takeUnless(visibleIds::contains)
                ?.let { selected -> selected.toSemanticNode(snapshot, language) }
        return visibleNodes + listOfNotNull(selectedNode)
    }

    private fun VisibleFlameNode.toSemanticNode(
        snapshot: FlameGraphSnapshot,
        selectedNodeId: FlameCallNodeId?,
        hoveredNodeId: FlameCallNodeId?,
        contextNodeId: FlameCallNodeId?,
        language: UiLanguage,
    ): FlameGraphSemanticNode? {
        val node = semanticFacts(snapshot, nodeId, language) ?: return null
        val states =
            listOfNotNull(
                localizedStringResource(SimpleperfViewerRes.sp_accessibility_selected_state, language)
                    .takeIf { nodeId == selectedNodeId },
                localizedStringResource(SimpleperfViewerRes.sp_accessibility_hovered_state, language)
                    .takeIf { nodeId == hoveredNodeId },
                localizedStringResource(
                    SimpleperfViewerRes.sp_accessibility_context_menu_open_state,
                    language,
                ).takeIf { nodeId == contextNodeId },
            )
        val stateDescription =
            listOf(node.stateDescription, states.joinToString())
                .filter(String::isNotBlank)
                .joinToString(", ")
        return FlameGraphSemanticNode(
            nodeId = nodeId,
            contentDescription = node.contentDescription,
            stateDescription = stateDescription,
            selected = nodeId == selectedNodeId,
            x = x.roundToInt(),
            y = y.roundToInt(),
            width = width.roundToInt().coerceAtLeast(1),
            height = height.roundToInt().coerceAtLeast(1),
        )
    }

    private fun FlameCallNodeId.toSemanticNode(
        snapshot: FlameGraphSnapshot,
        language: UiLanguage,
    ): FlameGraphSemanticNode? {
        val node = semanticFacts(snapshot, this, language) ?: return null
        return FlameGraphSemanticNode(
            nodeId = this,
            contentDescription = node.contentDescription,
            stateDescription = node.stateDescription,
            selected = true,
            x = 0,
            y = 0,
            width = 1,
            height = 1,
        )
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
internal fun FlameGraphSemanticsOverlay(
    snapshot: FlameGraphSnapshot,
    layout: VisibleFlameLayout,
    selectedNodeId: FlameCallNodeId?,
    hoveredNodeId: FlameCallNodeId? = null,
    contextNodeId: FlameCallNodeId? = null,
    onSelect: (FlameCallNodeId) -> Unit,
    onOpenDetails: (FlameCallNodeId) -> Unit,
    onOpenContextMenu: (FlameCallNodeId, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val language = currentSimpleperfLanguage()
    val selectLabel = localizedStringResource(SimpleperfViewerRes.sp_accessibility_select, language)
    val openDetailsLabel = localizedStringResource(SimpleperfViewerRes.sp_accessibility_open_details, language)
    val openContextMenuLabel = localizedStringResource(SimpleperfViewerRes.sp_accessibility_open_context_menu, language)
    Box(modifier) {
        val nodes =
            FlameGraphSemanticsPresenter.nodes(
                snapshot,
                layout,
                selectedNodeId,
                hoveredNodeId,
                contextNodeId,
                currentSimpleperfLanguage(),
            )
        nodes.forEach { node ->
            Box(
                modifier =
                    Modifier
                        .absoluteOffset { IntOffset(node.x, node.y) }
                        .size(
                            with(density) { node.width.toDp() },
                            with(density) { node.height.toDp() },
                        ).testTag("flame-node-${node.nodeId.value}")
                        .semantics {
                            contentDescription = node.contentDescription
                            stateDescription = node.stateDescription
                            selected = node.selected
                            onClick(label = selectLabel) {
                                onSelect(node.nodeId)
                                true
                            }
                            customActions =
                                listOf(
                                    CustomAccessibilityAction(openDetailsLabel) {
                                        onOpenDetails(node.nodeId)
                                        true
                                    },
                                    CustomAccessibilityAction(openContextMenuLabel) {
                                        onOpenContextMenu(node.nodeId, Offset(node.x.toFloat(), node.y.toFloat()))
                                        true
                                    },
                                )
                        },
            )
        }
    }
}

private data class SemanticFacts(
    val contentDescription: String,
    val stateDescription: String,
)

@Suppress("ReturnCount")
private fun semanticFacts(
    snapshot: FlameGraphSnapshot,
    nodeId: FlameCallNodeId,
    language: UiLanguage,
): SemanticFacts? {
    val index = snapshot.callNodes.indexOf(nodeId) ?: return null
    val frame = snapshot.callNodes.frameAt(index) ?: return null
    val category = snapshot.callNodes.categoryAt(index) ?: frame.implementation.label
    val inclusiveWeight = snapshot.callNodes.inclusiveWeightAt(index) ?: return null
    val sampleCount = snapshot.callNodes.sampleCountAt(index) ?: 0L
    val percent = percentage(inclusiveWeight, snapshot.totalWeight)
    return SemanticFacts(
        contentDescription =
            localizedStringResource(
                SimpleperfViewerRes.sp_accessibility_flame_content_description_format,
                language,
                frame.symbolName,
                percent,
                category,
            ),
        stateDescription =
            localizedStringResource(
                if (sampleCount == 1L) {
                    SimpleperfViewerRes.sp_accessibility_flame_single_sample_state_format
                } else {
                    SimpleperfViewerRes.sp_accessibility_flame_multiple_samples_state_format
                },
                language,
                frame.implementation.label,
                inclusiveWeight,
                sampleCount,
            ),
    )
}

private val com.androidperformancestudio.profileanalysis.FrameImplementation.label: String
    get() = name.lowercase().replaceFirstChar(Char::titlecase)

private fun percentage(
    weight: Long,
    total: Long,
): Long = if (total <= 0L) 0L else ((weight.toDouble() / total.toDouble()) * PERCENT_SCALE).roundToInt().toLong()

private const val PERCENT_SCALE = 100
