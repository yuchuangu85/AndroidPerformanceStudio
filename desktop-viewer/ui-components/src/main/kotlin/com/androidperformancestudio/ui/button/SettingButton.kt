@file:Suppress("FunctionNaming", "MagicNumber")

package com.androidperformancestudio.ui.button

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.ViewerColors
import com.androidperformancestudio.ui.ViewerDimensions
import com.androidperformancestudio.ui_components.generated.resources.Res
import com.androidperformancestudio.ui_components.generated.resources.ic_home
import com.androidperformancestudio.ui_components.generated.resources.ic_settings
import org.jetbrains.compose.resources.painterResource
import kotlin.math.cos
import kotlin.math.sin

@Composable
public fun SettingsButton(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    enabled: Boolean = true,
    colors: ViewerColors? = null,
    onClick: () -> Unit,
) {
    val iconColor =
        (colors?.accent ?: LocalViewerColors.current.accent)
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
                    colors?.accent ?: LocalViewerColors.current.accent,
                    RoundedCornerShape(ViewerDimensions.controlRadius),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_settings),
            contentDescription = contentDescription,
            tint = iconColor,
        )
    }
}

private const val DISABLED_SETTINGS_CONTENT_ALPHA = 0.48f
private const val SETTINGS_GEAR_TOOTH_COUNT = 8
private const val SETTINGS_GEAR_TOOTH_ANGLE_DEGREES = 45.0
private const val SETTINGS_GEAR_START_ANGLE_DEGREES = -90.0
