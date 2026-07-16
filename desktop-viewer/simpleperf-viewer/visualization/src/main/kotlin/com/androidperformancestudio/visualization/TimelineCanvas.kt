package com.androidperformancestudio.visualization

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput

private const val DEFAULT_TIMELINE_COLOR_ARGB = 0xFF4FC3F7
private val DefaultTimelineColor = Color(DEFAULT_TIMELINE_COLOR_ARGB)

@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
fun TimelineCanvas(
    frame: TimelineFrame,
    modifier: Modifier = Modifier,
    color: Color = DefaultTimelineColor,
    viewport: TimeViewport? = null,
    onRangePreview: (TimeViewport) -> Unit = {},
    onRangeCommit: (TimeViewport) -> Unit = {},
    onRangeCancel: () -> Unit = {},
) {
    val currentRangePreview by rememberUpdatedState(onRangePreview)
    val currentRangeCommit by rememberUpdatedState(onRangeCommit)
    val currentRangeCancel by rememberUpdatedState(onRangeCancel)
    val rangeModifier =
        if (viewport == null) {
            modifier
        } else {
            modifier.pointerInput(viewport) {
                var interaction: TimelineRangeInteraction? = null

                fun cancelActiveInteraction() {
                    val active = interaction ?: return
                    active.cancel()
                    interaction = null
                    currentRangeCancel()
                }

                try {
                    detectDragGestures(
                        onDragStart = { position ->
                            interaction = TimelineRangeInteraction(viewport, size.width.toFloat())
                            interaction?.start(position.x)?.let(currentRangePreview)
                        },
                        onDragCancel = ::cancelActiveInteraction,
                        onDragEnd = {
                            interaction?.commit()?.let(currentRangeCommit)
                            interaction = null
                        },
                    ) { change, _ ->
                        interaction?.drag(change.position.x)?.let(currentRangePreview)
                    }
                } finally {
                    cancelActiveInteraction()
                }
            }
        }
    Canvas(rangeModifier) {
        if (frame.maximumWeight == 0L || frame.columns.isEmpty()) return@Canvas
        val columnWidth = size.width / frame.columns.size
        frame.columns.forEachIndexed { index, column ->
            val height = size.height * column.weight / frame.maximumWeight
            drawRect(
                color = color,
                topLeft = Offset(index * columnWidth, size.height - height),
                size = Size(columnWidth.coerceAtLeast(1f), height),
            )
        }
    }
}
