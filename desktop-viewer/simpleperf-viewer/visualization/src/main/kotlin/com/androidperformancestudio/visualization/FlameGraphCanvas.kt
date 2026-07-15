package com.androidperformancestudio.visualization

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.androidperformancestudio.profileanalysis.FlameCallNodeId

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
fun FlameGraphCanvas(
    layout: VisibleFlameLayout,
    selectedNodeId: FlameCallNodeId?,
    modifier: Modifier = Modifier,
    onNodeClick: (VisibleFlameNode) -> Unit,
    onBlankClick: () -> Unit,
) {
    Canvas(
        modifier =
            modifier.pointerInput(layout) {
                detectTapGestures(
                    onTap = { offset ->
                        FlameGraphLayout.hitTest(layout, offset.x, offset.y)?.let(onNodeClick) ?: onBlankClick()
                    },
                )
            },
    ) {
        layout.nodes.forEach { node ->
            val colors =
                FlameGraphPalette.colors(
                    category = null,
                    theme = FlameTheme.LIGHT,
                    state = FlameNodeVisualState(selected = node.nodeId == selectedNodeId),
                )
            drawRect(
                color = colors.fill.toComposeColor(),
                topLeft = Offset(node.x, node.y),
                size = Size(node.width, (node.height - 1f).coerceAtLeast(1f)),
            )
        }
    }
}

private fun FlameGraphColor.toComposeColor(): Color = Color(argb.toUInt().toULong())
