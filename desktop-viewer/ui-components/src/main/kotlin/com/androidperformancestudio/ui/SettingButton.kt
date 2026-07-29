package com.androidperformancestudio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun SettingsButton(onClick: () -> Unit) {
    val colors = LocalViewerColors.current
    Box(
        modifier = Modifier
            .width(28.dp)
            .height(28.dp)
            .clickable(onClick = onClick)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val strokeWidth = 1.2.dp.toPx()
            val innerRadius = 2.2.dp.toPx()
            val outerRadius = 5.2.dp.toPx()
            drawCircle(
                color = colors.mutedText,
                radius = innerRadius,
                center = center,
                style = Stroke(width = strokeWidth),
            )
            drawCircle(
                color = colors.mutedText,
                radius = outerRadius,
                center = center,
                style = Stroke(width = strokeWidth),
            )
            repeat(8) { index ->
                val angle = Math.toRadians((index * 45.0) - 90.0)
                val start = Offset(
                    x = center.x + kotlin.math.cos(angle).toFloat() * outerRadius,
                    y = center.y + kotlin.math.sin(angle).toFloat() * outerRadius,
                )
                val endRadius = outerRadius + 2.dp.toPx()
                val end = Offset(
                    x = center.x + kotlin.math.cos(angle).toFloat() * endRadius,
                    y = center.y + kotlin.math.sin(angle).toFloat() * endRadius,
                )
                drawLine(
                    color = colors.mutedText,
                    start = start,
                    end = end,
                    strokeWidth = strokeWidth,
                )
            }
        }
    }
}
