package com.androidperformancestudio.visualization

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import kotlin.math.roundToInt

sealed interface FlameGraphIntent {
    data class Hover(
        val nodeId: FlameCallNodeId?,
    ) : FlameGraphIntent

    data class Select(
        val nodeId: FlameCallNodeId?,
    ) : FlameGraphIntent

    data class OpenContextMenu(
        val nodeId: FlameCallNodeId,
        val position: Offset,
    ) : FlameGraphIntent

    data class OpenDetails(
        val nodeId: FlameCallNodeId,
    ) : FlameGraphIntent
}

internal object FlameGraphInteraction {
    fun hover(
        layout: VisibleFlameLayout,
        position: Offset,
    ): FlameGraphIntent.Hover = FlameGraphIntent.Hover(layout.nodeIdAt(position))

    fun hoverExit(): FlameGraphIntent.Hover = FlameGraphIntent.Hover(null)

    fun select(
        layout: VisibleFlameLayout,
        position: Offset,
    ): FlameGraphIntent.Select = FlameGraphIntent.Select(layout.nodeIdAt(position))

    fun openContextMenu(
        layout: VisibleFlameLayout,
        position: Offset,
    ): FlameGraphIntent.OpenContextMenu? = layout.nodeIdAt(position)?.contextMenuAt(position)

    fun openDetails(
        layout: VisibleFlameLayout,
        position: Offset,
    ): FlameGraphIntent.OpenDetails? = layout.nodeIdAt(position)?.let(FlameGraphIntent::OpenDetails)
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
fun FlameGraphCanvas(
    layout: VisibleFlameLayout,
    selectedNodeId: FlameCallNodeId?,
    onIntent: (FlameGraphIntent) -> Unit,
    modifier: Modifier = Modifier,
    hoveredNodeId: FlameCallNodeId? = null,
    contextNodeId: FlameCallNodeId? = null,
    labelForNode: (VisibleFlameNode) -> String = { "" },
    categoryForNode: (VisibleFlameNode) -> String? = { null },
    theme: FlameTheme? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    val resolvedTheme = theme ?: if (isSystemInDarkTheme()) FlameTheme.DARK else FlameTheme.LIGHT
    val primaryInputModifier =
        modifier
            .onPointerEvent(PointerEventType.Enter) { event ->
                event.changes.lastOrNull()?.position?.let { position ->
                    onIntent(FlameGraphInteraction.hover(layout, position))
                }
            }.onPointerEvent(PointerEventType.Move) { event ->
                event.changes.lastOrNull()?.position?.let { position ->
                    onIntent(FlameGraphInteraction.hover(layout, position))
                }
            }.onPointerEvent(PointerEventType.Exit) {
                onIntent(FlameGraphInteraction.hoverExit())
            }.pointerInput(layout, onIntent) {
                detectTapGestures(
                    onDoubleTap = { position ->
                        FlameGraphInteraction.openDetails(layout, position)?.let(onIntent)
                    },
                    onTap = { position -> onIntent(FlameGraphInteraction.select(layout, position)) },
                )
            }
    // The inner press handler consumes secondary downs before the outer tap detector's main pass.
    val pointerModifier =
        primaryInputModifier.onPointerEvent(PointerEventType.Press) { event ->
            if (event.buttons.isSecondaryPressed) {
                event.changes.lastOrNull()?.position?.let { position ->
                    FlameGraphInteraction.openContextMenu(layout, position)?.let(onIntent)
                }
                event.changes.forEach { change -> change.consume() }
            }
        }
    Canvas(
        modifier = pointerModifier,
    ) {
        layout.nodes.forEach { node ->
            drawFlameNode(
                node = node,
                selectedNodeId = selectedNodeId,
                hoveredNodeId = hoveredNodeId,
                contextNodeId = contextNodeId,
                labelForNode = { labelForNode(node) },
                category = categoryForNode(node),
                theme = resolvedTheme,
                textMeasurer = textMeasurer,
            )
        }
    }
}

@Suppress("LongParameterList")
private fun DrawScope.drawFlameNode(
    node: VisibleFlameNode,
    selectedNodeId: FlameCallNodeId?,
    hoveredNodeId: FlameCallNodeId?,
    contextNodeId: FlameCallNodeId?,
    labelForNode: () -> String,
    category: String?,
    theme: FlameTheme,
    textMeasurer: TextMeasurer,
) {
    val colors =
        FlameGraphPalette.colors(
            category = category,
            theme = theme,
            state =
                FlameNodeVisualState(
                    selected = node.nodeId == selectedNodeId,
                    hovered = node.nodeId == hoveredNodeId,
                    context = node.nodeId == contextNodeId,
                ),
        )
    val drawableHeight = (node.height - NODE_GAP_PX).coerceAtLeast(MINIMUM_NODE_HEIGHT_PX)
    drawRect(
        color = colors.fill.toComposeColor(),
        topLeft = Offset(node.x, node.y),
        size = Size(node.width, drawableHeight),
    )
    colors.outline?.let { outline ->
        drawRect(
            color = outline.toComposeColor(),
            topLeft = Offset(node.x, node.y),
            size = Size(node.width, drawableHeight),
            style = Stroke(width = OUTLINE_WIDTH_PX),
        )
    }
    if (shouldResolveFlameLabel(node, size.width, size.height)) {
        drawFittedLabel(
            node,
            drawableHeight,
            labelForNode(),
            colors.foreground.toComposeColor(),
            textMeasurer,
        )
    }
}

internal fun shouldResolveFlameLabel(
    node: VisibleFlameNode,
    canvasWidth: Float,
    canvasHeight: Float,
): Boolean {
    val drawableHeight = (node.height - NODE_GAP_PX).coerceAtLeast(MINIMUM_NODE_HEIGHT_PX)
    val maximumTextWidth = node.width - HORIZONTAL_LABEL_PADDING_PX * 2
    return canvasWidth.isFinite() &&
        canvasHeight.isFinite() &&
        maximumTextWidth >= MINIMUM_LABEL_WIDTH_PX &&
        drawableHeight >= MINIMUM_LABEL_HEIGHT_PX &&
        node.x < canvasWidth &&
        node.x + node.width > 0f &&
        node.y < canvasHeight &&
        node.y + drawableHeight > 0f
}

private fun DrawScope.drawFittedLabel(
    node: VisibleFlameNode,
    drawableHeight: Float,
    label: String,
    foreground: Color,
    textMeasurer: TextMeasurer,
) {
    val maximumTextWidth = (node.width - HORIZONTAL_LABEL_PADDING_PX * 2).roundToInt()
    if (label.isBlank() || maximumTextWidth < MINIMUM_LABEL_WIDTH_PX || drawableHeight < MINIMUM_LABEL_HEIGHT_PX) {
        return
    }
    val result =
        textMeasurer.measure(
            text = label,
            style = TextStyle(color = foreground, fontSize = LABEL_FONT_SIZE_SP.sp),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            constraints = Constraints(maxWidth = maximumTextWidth),
        )
    if (result.size.height <= drawableHeight) {
        drawText(
            textLayoutResult = result,
            topLeft = Offset(node.x + HORIZONTAL_LABEL_PADDING_PX, node.y + (drawableHeight - result.size.height) / 2f),
        )
    }
}

private fun VisibleFlameLayout.nodeIdAt(position: Offset): FlameCallNodeId? =
    FlameGraphLayout
        .hitTest(this, position.x, position.y)
        ?.nodeId

private fun FlameCallNodeId.contextMenuAt(position: Offset) = FlameGraphIntent.OpenContextMenu(this, position)

private fun FlameGraphColor.toComposeColor(): Color = Color(argb.toUInt().toULong())

private const val NODE_GAP_PX = 1f
private const val MINIMUM_NODE_HEIGHT_PX = 1f
private const val OUTLINE_WIDTH_PX = 1f
private const val HORIZONTAL_LABEL_PADDING_PX = 3f
private const val MINIMUM_LABEL_WIDTH_PX = 12
private const val MINIMUM_LABEL_HEIGHT_PX = 8f
private const val LABEL_FONT_SIZE_SP = 10
