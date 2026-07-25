@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.androidperformancestudio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Compact macOS-style home navigation control shared by desktop profiler toolbars. */
@Composable
public fun ProfilerHomeButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier =
            modifier
                .width(26.dp)
                .height(21.dp)
                .semantics { this.contentDescription = contentDescription }
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) {
            val strokeWidth = 1.2.dp.toPx()
            val roofLeft = Offset(1.5.dp.toPx(), 7.dp.toPx())
            val roofPeak = Offset(size.width / 2f, 1.8.dp.toPx())
            val roofRight = Offset(size.width - 1.5.dp.toPx(), 7.dp.toPx())
            val wallLeft = 3.2.dp.toPx()
            val wallRight = size.width - 3.2.dp.toPx()
            val wallTop = 6.2.dp.toPx()
            val wallBottom = size.height - 1.8.dp.toPx()
            val doorWidth = 3.6.dp.toPx()

            drawLine(iconColor, roofLeft, roofPeak, strokeWidth)
            drawLine(iconColor, roofPeak, roofRight, strokeWidth)
            drawLine(iconColor, Offset(wallLeft, wallTop), Offset(wallLeft, wallBottom), strokeWidth)
            drawLine(iconColor, Offset(wallRight, wallTop), Offset(wallRight, wallBottom), strokeWidth)
            drawLine(iconColor, Offset(wallLeft, wallBottom), Offset(wallRight, wallBottom), strokeWidth)
            drawRect(
                color = iconColor,
                topLeft = Offset((size.width - doorWidth) / 2f, 9.dp.toPx()),
                size = Size(doorWidth, wallBottom - 9.dp.toPx()),
                style = Stroke(width = strokeWidth),
            )
        }
    }
}
