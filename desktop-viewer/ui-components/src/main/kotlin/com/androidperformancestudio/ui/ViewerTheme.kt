@file:Suppress("FunctionNaming", "MagicNumber")

package com.androidperformancestudio.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
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

public enum class ViewerThemeVariant {
    STANDARD,
    MAC_OS,
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
    private val light = ViewerColors(
        isDark = false,
        panel = Color(0xFFF8FAFC),
        canvasBackground = Color(0xFFE2E8F0),
        border = Color(0xFFCBD5E1),
        accent = Color(0xFF2563EB),
        primaryText = Color(0xFF0F172A),
        rowText = Color(0xFF1E293B),
        hiddenRowText = Color(0xFF94A3B8),
        secondaryText = Color(0xFF475569),
        mutedText = Color(0xFF64748B),
        subtleText = Color(0xFF64748B),
        selectedRow = Color(0xFFDBEAFE),
        sectionBackground = Color(0xFFB9D1F2),
        riskSectionBackground = Color(0xFFFFD9A1),
        detailRowDeep = Color(0xFFE6EBF2),
        detailRowLight = Color(0xFFF8FAFC),
        switchTrackOff = Color(0xFFCBD5E1),
        switchThumbOff = Color(0xFF64748B),
        previewSurface = Color.White,
        previewCanvas = Color(0xFFF8FAFC),
        visibleViewBounds = Color(0xFF7DD3FC),
        previewText = Color(0xFF475569),
        detailLabel = Color(0xFF64748B),
        info = Color(0xFF2563EB),
        warning = Color(0xFFB45309),
        error = Color(0xFFDC2626),
        success = Color(0xFF15803D),
        searchMatchRow = Color(0x332563EB),
        searchCurrentMatchRow = Color(0x662563EB),
        searchHighlightText = Color(0xFF1D4ED8),
    )

    private val dark = ViewerColors(
        isDark = true,
        panel = Color(0xFF141820),
        canvasBackground = Color(0xFF0D1016),
        border = Color(0xFF2B3240),
        accent = Color(0xFF70A5FF),
        primaryText = Color(0xFFDCE4F2),
        rowText = Color(0xFFD8E0ED),
        hiddenRowText = Color(0xFF687386),
        secondaryText = Color(0xFF95A2B6),
        mutedText = Color(0xFF687386),
        subtleText = Color(0xFF8E9AAF),
        selectedRow = Color(0xFF253B5F),
        sectionBackground = Color(0xFF304766),
        riskSectionBackground = Color(0xFF50351C),
        detailRowDeep = Color(0xFF151B24),
        detailRowLight = Color(0xFF252E3A),
        switchTrackOff = Color(0xFF353D4B),
        switchThumbOff = Color(0xFF8E9AAF),
        previewSurface = Color(0xFFEEF2F6),
        previewCanvas = Color(0xFFF8FAFC),
        visibleViewBounds = Color(0xFF7DD3FC),
        previewText = Color(0xFF64748B),
        detailLabel = Color(0xFF758197),
        info = Color(0xFF4BA3FF),
        warning = Color(0xFFF5A524),
        error = Color(0xFFEF5350),
        success = Color(0xFF55D187),
        searchMatchRow = Color(0x3370A5FF),
        searchCurrentMatchRow = Color(0x6670A5FF),
        searchHighlightText = Color(0xFFA5C8FF),
    )

    private val macOsLight =
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

    private val macOsDark =
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

    fun forDark(
        darkTheme: Boolean,
        variant: ViewerThemeVariant,
    ): ViewerColors =
        when (variant) {
            ViewerThemeVariant.STANDARD -> if (darkTheme) dark else light
            ViewerThemeVariant.MAC_OS -> if (darkTheme) macOsDark else macOsLight
        }
}

val LocalViewerColors = staticCompositionLocalOf {
    viewerColors(darkTheme = true)
}

@Composable
public fun ViewerTheme(
    darkTheme: Boolean,
    variant: ViewerThemeVariant = ViewerThemeVariant.STANDARD,
    content: @Composable () -> Unit,
) {
    val colors = viewerColors(darkTheme, variant)
    val colorScheme = viewerMaterialColorScheme(darkTheme, variant)
    CompositionLocalProvider(LocalViewerColors provides colors) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}

public fun viewerColors(
    darkTheme: Boolean,
    variant: ViewerThemeVariant = ViewerThemeVariant.STANDARD,
): ViewerColors = ViewerPalettes.forDark(darkTheme, variant)

public fun viewerMaterialColorScheme(
    darkTheme: Boolean,
    variant: ViewerThemeVariant = ViewerThemeVariant.STANDARD,
): ColorScheme {
    val colors = viewerColors(darkTheme, variant)
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
