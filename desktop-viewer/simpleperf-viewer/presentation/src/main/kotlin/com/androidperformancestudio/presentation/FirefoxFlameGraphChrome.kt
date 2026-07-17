package com.androidperformancestudio.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import com.androidperformancestudio.visualization.FirefoxFlameGraphStyle
import com.androidperformancestudio.visualization.FlameGraphColor
import com.androidperformancestudio.visualization.FlameTheme

@Composable
internal fun rememberFirefoxFlameGraphStyle(): FirefoxFlameGraphStyle {
    val background = MaterialTheme.colorScheme.background
    val density = LocalDensity.current.density
    val theme = if (background.luminance() < DARK_THEME_LUMINANCE_THRESHOLD) FlameTheme.DARK else FlameTheme.LIGHT
    return remember(theme, density) { FirefoxFlameGraphStyle.resolve(theme, density) }
}

internal fun FlameGraphColor.toComposeColor(): Color = Color(argb)

private const val DARK_THEME_LUMINANCE_THRESHOLD = 0.5f
