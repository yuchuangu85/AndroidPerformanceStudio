@file:Suppress("FunctionNaming", "MagicNumber")

package com.androidperformancestudio.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
public data class ViewerColors(
    val isDark: Boolean,
    val panel: Color,
    val canvasBackground: Color,
    val border: Color,
    val accent: Color,
    val primaryText: Color,
    val rowText: Color,
    val hiddenRowText: Color,
    val secondaryText: Color,
    val mutedText: Color,
    val subtleText: Color,
    val selectedRow: Color,
    val sectionBackground: Color,
    val riskSectionBackground: Color,
    val detailRowDeep: Color,
    val detailRowLight: Color,
    val switchTrackOff: Color,
    val switchThumbOff: Color,
    val previewSurface: Color,
    val previewCanvas: Color,
    val visibleViewBounds: Color,
    val previewText: Color,
    val detailLabel: Color,
    val info: Color,
    val warning: Color,
    val error: Color,
    val success: Color,
    val searchMatchRow: Color,
    val searchCurrentMatchRow: Color,
    val searchHighlightText: Color,
    val workspace: Color = canvasBackground,
    val toolbar: Color = panel,
    val field: Color = detailRowDeep,
    val strongBorder: Color = border,
    val accentText: Color = Color.White,
    val online: Color = success,
) {
    public val text: Color
        get() = primaryText
}

public object ViewerDimensions {
    public val toolbarHeight = 40.dp
    public val footerHeight = 29.dp
    public val buttonHeight = 28.dp
    public val selectorHeight = 30.dp
    public val controlRadius = 6.dp
    public val hairline = 1.dp
}

internal object ViewerPalettes {
    private val light =
        ViewerColors(
            isDark = false,
            panel = Color.White,
            canvasBackground = Color(0xFFF5F5F7),
            border = Color(0xFFD1D1D6),
            accent = Color(0xFF0A84FF),
            primaryText = Color(0xFF1D1D1F),
            rowText = Color(0xFF1D1D1F),
            hiddenRowText = Color(0xFF6E6E73),
            secondaryText = Color(0xFF6E6E73),
            mutedText = Color(0xFF6E6E73),
            subtleText = Color(0xFF6E6E73),
            selectedRow = Color(0xFFD6EAFF),
            sectionBackground = Color(0xFFFAFAFB),
            riskSectionBackground = Color(0xFFFFE5BF),
            detailRowDeep = Color.White,
            detailRowLight = Color.White,
            switchTrackOff = Color(0xFFD1D1D6),
            switchThumbOff = Color(0xFF6E6E73),
            previewSurface = Color.White,
            previewCanvas = Color(0xFFF5F5F7),
            visibleViewBounds = Color(0xFF0A84FF),
            previewText = Color(0xFF6E6E73),
            detailLabel = Color(0xFF6E6E73),
            info = Color(0xFF0A84FF),
            warning = Color(0xFFFF9F0A),
            error = Color(0xFFFF3B30),
            success = Color(0xFF34C759),
            searchMatchRow = Color(0x330A84FF),
            searchCurrentMatchRow = Color(0x660A84FF),
            searchHighlightText = Color(0xFF0A84FF),
            toolbar = Color(0xFFFAFAFB),
            field = Color.White,
            strongBorder = Color(0xFFB8B8BD),
        )

    private val dark =
        ViewerColors(
            isDark = true,
            panel = Color(0xFF2C2C2E),
            canvasBackground = Color(0xFF1E1E20),
            border = Color(0xFF48484A),
            accent = Color(0xFF0A84FF),
            primaryText = Color(0xFFF5F5F7),
            rowText = Color(0xFFF5F5F7),
            hiddenRowText = Color(0xFFAEAEB2),
            secondaryText = Color(0xFFAEAEB2),
            mutedText = Color(0xFFAEAEB2),
            subtleText = Color(0xFFAEAEB2),
            selectedRow = Color(0xFF163D66),
            sectionBackground = Color(0xFF29292B),
            riskSectionBackground = Color(0xFF4D3515),
            detailRowDeep = Color(0xFF1C1C1E),
            detailRowLight = Color(0xFF2C2C2E),
            switchTrackOff = Color(0xFF48484A),
            switchThumbOff = Color(0xFFAEAEB2),
            previewSurface = Color(0xFF2C2C2E),
            previewCanvas = Color(0xFF1E1E20),
            visibleViewBounds = Color(0xFF0A84FF),
            previewText = Color(0xFFAEAEB2),
            detailLabel = Color(0xFFAEAEB2),
            info = Color(0xFF0A84FF),
            warning = Color(0xFFFF9F0A),
            error = Color(0xFFFF453A),
            success = Color(0xFF30D158),
            searchMatchRow = Color(0x330A84FF),
            searchCurrentMatchRow = Color(0x660A84FF),
            searchHighlightText = Color(0xFF64B5FF),
            toolbar = Color(0xFF29292B),
            field = Color(0xFF1C1C1E),
            strongBorder = Color(0xFF636366),
        )

    fun forDark(darkTheme: Boolean): ViewerColors = if (darkTheme) dark else light
}

val LocalViewerColors = staticCompositionLocalOf {
    viewerColors(darkTheme = true)
}

@Composable
public fun ViewerTheme(
    darkTheme: Boolean,
    typography: Typography = MaterialTheme.typography,
    shapes: Shapes = MaterialTheme.shapes,
    content: @Composable () -> Unit,
) {
    val colors = viewerColors(darkTheme)
    val colorScheme = viewerMaterialColorScheme(darkTheme)
    CompositionLocalProvider(LocalViewerColors provides colors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = shapes,
            content = content,
        )
    }
}

public fun viewerColors(darkTheme: Boolean): ViewerColors = ViewerPalettes.forDark(darkTheme)

public fun viewerMaterialColorScheme(darkTheme: Boolean): ColorScheme {
    val colors = viewerColors(darkTheme)
    return if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            background = colors.canvasBackground,
            surface = colors.panel,
            surfaceVariant = colors.detailRowDeep,
            primaryContainer = colors.selectedRow,
            secondaryContainer = colors.sectionBackground,
            outline = colors.border,
            onBackground = colors.primaryText,
            onSurface = colors.primaryText,
            onSurfaceVariant = colors.secondaryText,
            error = colors.error,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            background = colors.canvasBackground,
            surface = colors.panel,
            surfaceVariant = colors.detailRowDeep,
            primaryContainer = colors.selectedRow,
            secondaryContainer = colors.sectionBackground,
            outline = colors.border,
            onBackground = colors.primaryText,
            onSurface = colors.primaryText,
            onSurfaceVariant = colors.secondaryText,
            error = colors.error,
        )
    }
}
