@file:Suppress("MagicNumber", "MaxLineLength")

package com.androidperformancestudio.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.presentation.generated.resources.ViewerRes
import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.StackChartBlockId
import com.androidperformancestudio.profileanalysis.StackChartSnapshot
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.localizedStringResource
import kotlin.math.max
import kotlin.math.min

@Composable
@Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "LongMethod",
    "LongParameterList",
    "ktlint:standard:function-naming",
)
internal fun StackChartCanvas(
    snapshot: StackChartSnapshot,
    viewport: StackChartViewport,
    selectedBlockId: StackChartBlockId?,
    style: ViewerColors,
    onSelect: (StackChartBlockId?) -> Unit,
    onCommitRange: (Long, Long) -> Unit,
) {
    val callStacksDescription =
        localizedStringResource(
            ViewerRes.sp_stack_call_stacks_description,
            currentSimpleperfLanguage(),
        )
    var widthPixels by remember { mutableIntStateOf(0) }
    var heightPixels by remember { mutableIntStateOf(0) }
    var dragStartX by remember { mutableFloatStateOf(Float.NaN) }
    var dragEndX by remember { mutableFloatStateOf(Float.NaN) }
    val density = LocalDensity.current
    val visible = remember(snapshot, viewport) { StackChartPresenter.visibleBlocks(snapshot.blocks, viewport) }
    val rowHeightPx = with(density) { STACK_CHART_ROW_HEIGHT_DP.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .testTag("stack-chart-canvas")
            .background(style.panel)
            .onSizeChanged {
                widthPixels = it.width
                heightPixels = it.height
            }.pointerInput(snapshot, viewport, widthPixels, rowHeightPx) {
                detectTapGestures { point ->
                    val timestamp = StackChartPresenter.timeAtX(point.x, viewport, widthPixels.toFloat())
                    val depth = (point.y / rowHeightPx).toInt()
                    onSelect(StackChartPresenter.hitTest(visible, timestamp, depth))
                }
            }.pointerInput(viewport, widthPixels) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragStartX = it.x
                        dragEndX = it.x
                    },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragEndX = (dragEndX + amount).coerceIn(0f, widthPixels.toFloat())
                    },
                    onDragCancel = {
                        dragStartX = Float.NaN
                        dragEndX = Float.NaN
                    },
                    onDragEnd = {
                        if (dragStartX.isFinite() && dragEndX.isFinite() && dragStartX != dragEndX) {
                            val start = StackChartPresenter.timeAtX(min(dragStartX, dragEndX), viewport, widthPixels.toFloat())
                            val end = StackChartPresenter.timeAtX(max(dragStartX, dragEndX), viewport, widthPixels.toFloat())
                            if (start < end) onCommitRange(start, end)
                        }
                        dragStartX = Float.NaN
                        dragEndX = Float.NaN
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = callStacksDescription }) {
            visible.forEach { block ->
                val rect = StackChartPresenter.blockRect(block, viewport, size.width, rowHeightPx)
                val right = (rect.right - STACK_CHART_GAP_PX).coerceAtLeast(rect.left)
                if (right > rect.left && rect.top < size.height) {
                    drawRect(
                        color = stackChartColor(snapshot.framesById[block.frameId], block.id == selectedBlockId, style),
                        topLeft = Offset(rect.left, rect.top),
                        size = Size(right - rect.left, min(rect.height, size.height - rect.top)),
                    )
                }
            }
            if (dragStartX.isFinite() && dragEndX.isFinite()) {
                val left = min(dragStartX, dragEndX)
                drawRect(
                    color = style.accent.copy(alpha = 0.2f),
                    topLeft = Offset(left, 0f),
                    size = Size(max(1f, kotlin.math.abs(dragEndX - dragStartX)), size.height),
                )
            }
        }
        visible.forEach { block ->
            val rect = StackChartPresenter.blockRect(block, viewport, widthPixels.toFloat(), rowHeightPx)
            if (rect.width > 0f && rect.top < heightPixels) {
                val frame = snapshot.framesById[block.frameId]
                Box(
                    Modifier
                        .offset(x = with(density) { rect.left.toDp() }, y = with(density) { rect.top.toDp() })
                        .width(with(density) { rect.width.toDp() })
                        .height(STACK_CHART_ROW_HEIGHT_DP.dp)
                        .testTag("stack-block-${block.id.value}")
                        .clickable { onSelect(block.id) }
                        .semantics {
                            contentDescription = frame?.let { "${it.symbolName}, depth ${block.depth}" } ?: block.id.value
                            selected = block.id == selectedBlockId
                        },
                )
            }
        }
    }
}

private fun stackChartColor(
    frame: CallStackFrame?,
    selected: Boolean,
    style: ViewerColors,
): Color {
    if (selected) return style.accent
    val palette = listOf(0xFF5B8FF9, 0xFF61DDAA, 0xFF65789B, 0xFFF6BD16, 0xFF7262FD, 0xFF78D3F8)
    return Color(palette[kotlin.math.abs((frame?.symbolName ?: "unknown").hashCode()) % palette.size])
}

private const val STACK_CHART_ROW_HEIGHT_DP = 16
private const val STACK_CHART_GAP_PX = 1f
