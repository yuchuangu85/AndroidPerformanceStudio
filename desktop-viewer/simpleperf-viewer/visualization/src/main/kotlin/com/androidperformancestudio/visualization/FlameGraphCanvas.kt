package com.androidperformancestudio.visualization

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput

@Suppress("MagicNumber")
private val FlameColor = Color(0xFFE57373)

@Suppress("MagicNumber")
private val HighlightColor = Color(0xFFFFD54F)

@Suppress("MagicNumber")
private val SelectedColor = Color(0xFF42A5F5)

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
fun FlameGraphCanvas(
    rectangles: List<FlameRectangle>,
    selectedNodeId: Long?,
    modifier: Modifier = Modifier,
    onNodeClick: (FlameRectangle) -> Unit,
    onReset: () -> Unit,
) {
    Canvas(
        modifier =
            modifier.pointerInput(rectangles) {
                detectTapGestures(
                    onDoubleTap = { onReset() },
                    onTap = { offset ->
                        FlameGraphProjector.hitTest(rectangles, offset.x, offset.y)?.let(onNodeClick)
                    },
                )
            },
    ) {
        rectangles.forEach { rectangle ->
            val color =
                when {
                    rectangle.nodeId == selectedNodeId -> SelectedColor
                    rectangle.highlighted -> HighlightColor
                    else -> FlameColor
                }
            drawRect(
                color = color,
                topLeft = Offset(rectangle.x, rectangle.y),
                size = Size(rectangle.width.coerceAtLeast(1f), (rectangle.height - 1f).coerceAtLeast(1f)),
            )
        }
    }
}
