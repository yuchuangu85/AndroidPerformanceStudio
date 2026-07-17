package com.androidperformancestudio.visualization

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import com.androidperformancestudio.profileanalysis.CallStackFrame
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
    frameForNode: (VisibleFlameNode) -> CallStackFrame? = { null },
    style: FirefoxFlameGraphStyle,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val textStyle = remember(style.labelFontSizePx, density) { style.textStyle(density) }
    val labelCache = remember(style.labelFontSizePx) { FlameLabelCache() }
    val categoryCache = remember(layout) { HashMap<Int, FlameCategoryRole>() }
    val currentIntent by rememberUpdatedState(onIntent)
    val primaryInputModifier =
        modifier
            .onPointerEvent(PointerEventType.Enter) { event ->
                event.changes.lastOrNull()?.position?.let { position ->
                    currentIntent(FlameGraphInteraction.hover(layout, position))
                }
            }.onPointerEvent(PointerEventType.Move) { event ->
                event.changes.lastOrNull()?.position?.let { position ->
                    currentIntent(FlameGraphInteraction.hover(layout, position))
                }
            }.onPointerEvent(PointerEventType.Exit) {
                currentIntent(FlameGraphInteraction.hoverExit())
            }.pointerInput(layout) {
                detectTapGestures(
                    onDoubleTap = { position ->
                        FlameGraphInteraction.openDetails(layout, position)?.let(currentIntent)
                    },
                    onTap = { position -> currentIntent(FlameGraphInteraction.select(layout, position)) },
                )
            }
    // The inner press handler consumes secondary downs before the outer tap detector's main pass.
    val pointerModifier =
        primaryInputModifier.onPointerEvent(PointerEventType.Press) { event ->
            if (event.buttons.isSecondaryPressed) {
                event.changes.lastOrNull()?.position?.let { position ->
                    FlameGraphInteraction.openContextMenu(layout, position)?.let(currentIntent)
                }
                event.changes.forEach { change -> change.consume() }
            }
        }
    Canvas(
        modifier = pointerModifier,
    ) {
        drawRect(style.canvasBackground.toComposeColor())
        layout.nodes.forEach { node ->
            drawFlameNode(
                node = node,
                selectedNodeId = selectedNodeId,
                hoveredNodeId = hoveredNodeId,
                contextNodeId = contextNodeId,
                labelForNode = { labelForNode(node) },
                categoryRole =
                    categoryCache.getOrPut(node.nodeIndex) {
                        FlameGraphPalette.categoryRole(categoryForNode(node), frameForNode(node))
                    },
                style = style,
                textStyle = textStyle,
                textMeasurer = textMeasurer,
                labelCache = labelCache,
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
    categoryRole: FlameCategoryRole,
    style: FirefoxFlameGraphStyle,
    textStyle: TextStyle,
    textMeasurer: TextMeasurer,
    labelCache: FlameLabelCache,
) {
    val colors =
        style.nodeColors(
            role = categoryRole,
            state =
                FlameNodeVisualState(
                    selected = node.nodeId == selectedNodeId,
                    hovered = node.nodeId == hoveredNodeId,
                    context = node.nodeId == contextNodeId,
                ),
        )
    val drawableHeight = (node.height - ROW_BOTTOM_GAP_DEVICE_PX).coerceAtLeast(MINIMUM_NODE_HEIGHT_PX)
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
    if (shouldResolveFlameLabel(node, size.width, size.height, style)) {
        drawFittedLabel(
            node,
            drawableHeight,
            labelForNode(),
            colors.foreground.toComposeColor(),
            style,
            textStyle,
            textMeasurer,
            labelCache,
        )
    }
}

internal fun shouldResolveFlameLabel(
    node: VisibleFlameNode,
    canvasWidth: Float,
    canvasHeight: Float,
    style: FirefoxFlameGraphStyle = FirefoxFlameGraphStyle.resolve(FlameTheme.LIGHT),
): Boolean {
    val drawableHeight = (node.height - ROW_BOTTOM_GAP_DEVICE_PX).coerceAtLeast(MINIMUM_NODE_HEIGHT_PX)
    val maximumTextWidth = node.width - style.labelStartOffsetPx
    return canvasWidth.isFinite() &&
        canvasHeight.isFinite() &&
        maximumTextWidth > 0f &&
        drawableHeight >= MINIMUM_LABEL_HEIGHT_PX &&
        node.x < canvasWidth &&
        node.x + node.width > 0f &&
        node.y < canvasHeight &&
        node.y + drawableHeight > 0f
}

@Suppress("LongParameterList")
private fun DrawScope.drawFittedLabel(
    node: VisibleFlameNode,
    drawableHeight: Float,
    label: String,
    foreground: Color,
    style: FirefoxFlameGraphStyle,
    textStyle: TextStyle,
    textMeasurer: TextMeasurer,
    labelCache: FlameLabelCache,
) {
    val maximumTextWidth = (node.width - style.labelStartOffsetPx).roundToInt()
    if (label.isBlank() || maximumTextWidth <= 0 || drawableHeight < MINIMUM_LABEL_HEIGHT_PX) {
        return
    }
    val fittedText =
        labelCache.fitted(label, maximumTextWidth) {
            fitFlameLabel(label, maximumTextWidth.toFloat()) { candidate ->
                textMeasurer
                    .measure(candidate, textStyle)
                    .size
                    .width
                    .toFloat()
            }
        } ?: return
    val result =
        textMeasurer.measure(
            text = fittedText,
            style = textStyle.copy(color = foreground),
        )
    if (result.size.height <= drawableHeight) {
        drawText(
            textLayoutResult = result,
            topLeft =
                Offset(
                    node.x + style.labelStartOffsetPx,
                    node.y + style.labelBaselineOffsetPx - result.firstBaseline,
                ),
        )
    }
}

@Suppress("ReturnCount")
internal fun fitFlameLabel(
    label: String,
    maximumWidthPx: Float,
    measureWidth: (String) -> Float,
): String? {
    if (label.isBlank() || !maximumWidthPx.isFinite() || maximumWidthPx <= 0f) return null
    if (measureWidth(label) <= maximumWidthPx) return label
    if (measureWidth(ELLIPSIS) > maximumWidthPx) return null
    var low = 0
    var high = label.length
    while (low < high) {
        val candidateLength = (low + high + 1) / 2
        if (measureWidth(label.take(candidateLength) + ELLIPSIS) <= maximumWidthPx) {
            low = candidateLength
        } else {
            high = candidateLength - 1
        }
    }
    return label.take(low) + ELLIPSIS
}

private class FlameLabelCache(
    private val maximumEntries: Int = MAXIMUM_LABEL_CACHE_ENTRIES,
) {
    private val values =
        object : LinkedHashMap<FlameLabelCacheKey, String?>(maximumEntries, CACHE_LOAD_FACTOR, true) {
            @Suppress("MaxLineLength")
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FlameLabelCacheKey, String?>?): Boolean = size > maximumEntries
        }

    fun fitted(
        label: String,
        maximumWidthPx: Int,
        compute: () -> String?,
    ): String? {
        val key = FlameLabelCacheKey(label, maximumWidthPx)
        if (values.containsKey(key)) return values[key]
        return compute().also { values[key] = it }
    }
}

private data class FlameLabelCacheKey(
    val label: String,
    val maximumWidthPx: Int,
)

private fun FirefoxFlameGraphStyle.textStyle(density: Density): TextStyle =
    TextStyle(
        fontSize = with(density) { labelFontSizePx.toSp() },
    )

private fun VisibleFlameLayout.nodeIdAt(position: Offset): FlameCallNodeId? =
    FlameGraphLayout
        .hitTest(this, position.x, position.y)
        ?.nodeId

private fun FlameCallNodeId.contextMenuAt(position: Offset) = FlameGraphIntent.OpenContextMenu(this, position)

private fun FlameGraphColor.toComposeColor(): Color = Color(argb)

private const val ROW_BOTTOM_GAP_DEVICE_PX = 1f
private const val MINIMUM_NODE_HEIGHT_PX = 1f
private const val OUTLINE_WIDTH_PX = 1f
private const val MINIMUM_LABEL_HEIGHT_PX = 8f
private const val ELLIPSIS = "…"
private const val MAXIMUM_LABEL_CACHE_ENTRIES = 4_096
private const val CACHE_LOAD_FACTOR = 0.75f
