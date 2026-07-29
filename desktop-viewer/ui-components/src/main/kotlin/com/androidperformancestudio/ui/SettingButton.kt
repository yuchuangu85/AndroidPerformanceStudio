@file:Suppress("FunctionNaming", "MagicNumber")

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
public fun SettingsButton(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    enabled: Boolean = true,
    colors: ViewerColors? = null,
    onClick: () -> Unit,
) {
    val iconColor =
        (colors?.secondaryText ?: LocalViewerColors.current.mutedText)
            .copy(alpha = if (enabled) 1f else DISABLED_SETTINGS_CONTENT_ALPHA)
    val accessibilityModifier =
        if (contentDescription == null) {
            modifier
        } else {
            modifier.semantics { this.contentDescription = contentDescription }
        }
    Box(
        modifier =
            accessibilityModifier
                .width(28.dp)
                .height(ViewerDimensions.buttonHeight)
                .clickable(enabled = enabled, onClick = onClick)
                .border(
                    ViewerDimensions.hairline,
                    MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(ViewerDimensions.controlRadius),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) {
            drawSettingsGear(iconColor)
        }
    }
}

private fun DrawScope.drawSettingsGear(iconColor: Color) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val strokeWidth = 1.2.dp.toPx()
    val innerRadius = 2.2.dp.toPx()
    val outerRadius = 5.2.dp.toPx()
    drawCircle(iconColor, innerRadius, center, style = Stroke(width = strokeWidth))
    drawCircle(iconColor, outerRadius, center, style = Stroke(width = strokeWidth))
    repeat(SETTINGS_GEAR_TOOTH_COUNT) { index ->
        val angle =
            Math.toRadians(
                (index * SETTINGS_GEAR_TOOTH_ANGLE_DEGREES) + SETTINGS_GEAR_START_ANGLE_DEGREES,
            )
        val direction = Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
        drawLine(
            color = iconColor,
            start = center + direction * outerRadius,
            end = center + direction * (outerRadius + 2.dp.toPx()),
            strokeWidth = strokeWidth,
        )
    }
}

private const val DISABLED_SETTINGS_CONTENT_ALPHA = 0.48f
private const val SETTINGS_GEAR_TOOTH_COUNT = 8
private const val SETTINGS_GEAR_TOOTH_ANGLE_DEGREES = 45.0
private const val SETTINGS_GEAR_START_ANGLE_DEGREES = -90.0
