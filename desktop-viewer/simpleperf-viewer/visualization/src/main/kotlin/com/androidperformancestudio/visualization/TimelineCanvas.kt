package com.androidperformancestudio.visualization

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

private const val DEFAULT_TIMELINE_COLOR_ARGB = 0xFF4FC3F7
private val DefaultTimelineColor = Color(DEFAULT_TIMELINE_COLOR_ARGB)

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
fun TimelineCanvas(
    frame: TimelineFrame,
    modifier: Modifier = Modifier,
    color: Color = DefaultTimelineColor,
) {
    Canvas(modifier) {
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
