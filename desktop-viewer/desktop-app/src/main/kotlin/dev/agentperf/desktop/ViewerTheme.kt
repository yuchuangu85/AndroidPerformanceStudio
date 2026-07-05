package dev.agentperf.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal data class ViewerColors(
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
)

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
    )

    fun forDark(darkTheme: Boolean): ViewerColors = if (darkTheme) dark else light
}

internal val LocalViewerColors = staticCompositionLocalOf {
    ViewerPalettes.forDark(true)
}

@Composable
internal fun ViewerTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = ViewerPalettes.forDark(darkTheme)
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            background = colors.canvasBackground,
            surface = colors.panel,
            onSurface = colors.primaryText,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            background = colors.canvasBackground,
            surface = colors.panel,
            onSurface = colors.primaryText,
        )
    }
    CompositionLocalProvider(LocalViewerColors provides colors) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
