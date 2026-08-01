package com.androidperformancestudio.ui.button

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerDimensions

private const val DISABLED_CONTAINER_ALPHA = 0.55f
private const val DISABLED_CONTENT_ALPHA = 0.48f
@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
fun MacOSButton(
    label: String,
    onClick: () -> Unit,
    style: ViewerColors,
    height: Dp = ViewerDimensions.buttonHeight,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val container = if (primary) style.accent else style.panel
    val content = if (primary) style.accentText else style.text
    Box(
        modifier =
            Modifier
                .height(height)
                .clip(RoundedCornerShape(ViewerDimensions.controlRadius))
                .background(container.copy(alpha = if (enabled) 1f else DISABLED_CONTAINER_ALPHA))
                .border(
                    ViewerDimensions.hairline,
                    if (primary) style.accent else style.strongBorder,
                    RoundedCornerShape(ViewerDimensions.controlRadius),
                ).clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = content.copy(alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}
