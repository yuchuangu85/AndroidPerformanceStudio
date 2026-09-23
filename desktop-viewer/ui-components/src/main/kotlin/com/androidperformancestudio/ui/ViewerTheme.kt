@file:Suppress("FunctionNaming", "MagicNumber")

package com.androidperformancestudio.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val transparent: Color = Color.Transparent,
    val flameTooltipMeter: Color = accent,
    val flameTooltipTrack: Color = primaryText.copy(alpha = 0.10f),
    val firefoxActivity: Color = accent,
    val firefoxLocalTrack: Color = canvasBackground,
    val timeline: Color = ViewerVisualizationColors.timeline,
) {
    public val text: Color
        get() = primaryText

    /** Translucent pill surfaces remain theme-aware over the parent workspace color. */
    public val segmentedTrack: Color
        get() = primaryText.copy(alpha = if (isDark) 0.10f else 0.08f)

    public val segmentedSelected: Color
        get() = if (isDark) primaryText.copy(alpha = 0.18f) else panel.copy(alpha = 0.65f)
}

public object ViewerDimensions {
    public val footerHeight = 29.dp
    public val buttonHeight = 28.dp
    public val selectorHeight = 30.dp
    public val segmentedControlHeight = 28.dp
    public val segmentedItemHeight = 24.dp
    public val segmentedRadius = 14.dp
    public val segmentedMinItemWidth = 40.dp
    public val segmentedMaxWidth = 360.dp
    /** Matches the existing profiler toolbar action buttons. */
    public val compactControlHeight = 24.dp
    public val compactControlRadius = 4.dp
    public val controlRadius = 6.dp
    public val hairline = 1.dp
}

/**
 * The single desktop viewer text hierarchy. UI code selects a semantic role instead of owning sizes.
 */
public object ViewerTypography {
    public val flameGraphLabelPixels: Float = 10f
    public val homeHero: TextStyle = TextStyle(fontSize = 30.sp, lineHeight = 36.sp)
    public val pageTitle: TextStyle = TextStyle(fontSize = 18.sp, lineHeight = 22.sp)
    public val sectionTitle: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 20.sp)
    public val cardTitle: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 19.sp)
    public val subsectionTitle: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 17.sp)
    public val lead: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 19.sp)
    public val body: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    public val bodyMedium: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)
    public val bodyCompact: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    public val secondary: TextStyle = TextStyle(fontSize = 11.sp, lineHeight = 15.sp)
    public val label: TextStyle = TextStyle(fontSize = 10.sp, lineHeight = 14.sp)
    public val dense: TextStyle = TextStyle(fontSize = 9.sp, lineHeight = 11.sp)
    public val micro: TextStyle = TextStyle(fontSize = 8.sp, lineHeight = 10.sp)
    public val metric: TextStyle = TextStyle(fontSize = 17.sp, lineHeight = 21.sp)
    public val icon: TextStyle = TextStyle(fontSize = 20.sp, lineHeight = 20.sp)
}

/** Theme-owned choices for the persisted application accent preference. */
public object ViewerAccentPalette {
    public val bananaRed: Color = Color(0xFFD4042D)
    public val warmSunOrange: Color = Color(0xFFDB7A0E)
    public val cornflowerBlue: Color = Color(0xFF5A92E5)
    public val jadeGreen: Color = Color(0xFF5E8034)
    public val merlotPink: Color = Color(0xFFEB6D98)
    public val azure: Color = Color(0xFF41B5C2)
    public val lemonYellow: Color = Color(0xFFFACA2E)
    public val royalPurple: Color = Color(0xFF722169)
}

/** Theme-owned CSS tokens for browser fallbacks and exported HTML documents. */
public object ViewerDocumentTheme {
    public const val darkCanvas: String = "#111"
    public const val primaryText: String = "#eee"
    public const val errorText: String = "#fff"
    public const val externalLink: String = "#5af"
    public const val raisedSurface: String = "#222"
    public const val imageSurface: String = "#333"
    public const val systemFontFamily: String = "system-ui"
    public const val errorFontSize: String = "18px"
}

@Immutable
public data class ViewerFlameGraphCategoryColors(
    val selectedFill: Color,
    val unselectedFill: Color,
    val selectedText: Color,
)

@Immutable
public data class ViewerFlameGraphColors(
    val canvasBackground: Color,
    val canvasForeground: Color,
    val viewportBorder: Color,
    val panelSurface: Color,
    val raisedSurface: Color,
    val surfaceBorder: Color,
    val mutedForeground: Color,
    val controlSelectedSurface: Color,
    val focusOutline: Color,
    val selectedLineSurface: Color,
    val system: ViewerFlameGraphCategoryColors,
    val kernel: ViewerFlameGraphCategoryColors,
    val native: ViewerFlameGraphCategoryColors,
    val managed: ViewerFlameGraphCategoryColors,
    val graphics: ViewerFlameGraphCategoryColors,
    val io: ViewerFlameGraphCategoryColors,
    val network: ViewerFlameGraphCategoryColors,
    val other: ViewerFlameGraphCategoryColors,
)

/** Theme-owned palettes for feature visualization surfaces. */
public object ViewerVisualizationColors {
    public val layoutInspectorBorderNormal: Color = Color(0xFF7DD3FC)
    public val layoutInspectorBorderHovered: Color = Color(0xFFF59E0B)
    public val layoutInspectorBorderSelected: Color = Color(0xFFEF4444)
    public val layoutInspectorBorderGreen: Color = Color(0xFF22C55E)
    public val layoutInspectorBorderPurple: Color = Color(0xFFA855F7)
    public val layoutInspectorBorderWhite: Color = Color.White
    public val timeline: Color = Color(0xFF4FC3F7)

    public val flameLight: ViewerFlameGraphColors =
        ViewerFlameGraphColors(
            canvasBackground = Color.White,
            canvasForeground = Color.Black,
            viewportBorder = Color(0xFFD7D7DB),
            panelSurface = Color(0xFFF9F9FA),
            raisedSurface = Color(0xFFF9F9FA),
            surfaceBorder = Color(0xFFCCCCCC),
            mutedForeground = Color(0xFF737373),
            controlSelectedSurface = Color(0xFFEDEDF0),
            focusOutline = Color.Black,
            selectedLineSurface = Color(0xFFEDEDF0),
            system = ViewerFlameGraphCategoryColors(Color(0xFFFFE129), Color(0x70FFE900), Color.Black),
            kernel = ViewerFlameGraphCategoryColors(Color(0xFFFF9400), Color(0x60FF9400), Color.White),
            native = ViewerFlameGraphCategoryColors(Color(0xFFED00B5), Color(0x60ED00B5), Color.White),
            managed = ViewerFlameGraphCategoryColors(Color(0xFF12BC00), Color(0x6012BC00), Color.White),
            graphics = ViewerFlameGraphCategoryColors(Color(0xFF12BC00), Color(0x6012BC00), Color.White),
            io = ViewerFlameGraphCategoryColors(Color(0xFFFFE129), Color(0x70FFE900), Color.Black),
            network = ViewerFlameGraphCategoryColors(Color(0xFF45A1FF), Color(0x6045A1FF), Color.Black),
            other = ViewerFlameGraphCategoryColors(Color(0xFFB1B1B3), Color(0x60B1B1B3), Color.Black),
        )

    public val flameDark: ViewerFlameGraphColors =
        ViewerFlameGraphColors(
            canvasBackground = Color(0xFF18181A),
            canvasForeground = Color(0xFFEDEDF0),
            viewportBorder = Color(0xFF38383D),
            panelSurface = Color(0xFF232327),
            raisedSurface = Color(0xFF232327),
            surfaceBorder = Color(0xFF4A4A4F),
            mutedForeground = Color(0xFFB1B1B3),
            controlSelectedSurface = Color(0xFF2A2A2E),
            focusOutline = Color.White,
            selectedLineSurface = Color(0xFF38383D),
            system = ViewerFlameGraphCategoryColors(Color(0xFFBE9B00), Color(0x85BE9B00), Color(0xFFEDEDF0)),
            kernel = ViewerFlameGraphCategoryColors(Color(0xFFD76E00), Color(0x60D76E00), Color.White),
            native = ViewerFlameGraphCategoryColors(Color(0xFFB5007F), Color(0x60B5007F), Color.White),
            managed = ViewerFlameGraphCategoryColors(Color(0xFF058B00), Color(0x60058B00), Color.White),
            graphics = ViewerFlameGraphCategoryColors(Color(0xFF058B00), Color(0x60058B00), Color.White),
            io = ViewerFlameGraphCategoryColors(Color(0xFFBE9B00), Color(0x85BE9B00), Color(0xFFEDEDF0)),
            network = ViewerFlameGraphCategoryColors(Color(0xFF45A1FF), Color(0x6045A1FF), Color(0xFFEDEDF0)),
            other = ViewerFlameGraphCategoryColors(Color(0xFF737373), Color(0x60737373), Color(0xFFEDEDF0)),
        )
}

private val ViewerMaterialTypography =
    Typography(
        headlineLarge = ViewerTypography.homeHero,
        headlineMedium = ViewerTypography.pageTitle,
        headlineSmall = ViewerTypography.sectionTitle,
        titleLarge = ViewerTypography.cardTitle,
        titleMedium = ViewerTypography.subsectionTitle,
        titleSmall = ViewerTypography.bodyCompact,
        bodyLarge = ViewerTypography.body,
        bodyMedium = ViewerTypography.bodyMedium,
        bodySmall = ViewerTypography.secondary,
        labelLarge = ViewerTypography.bodyCompact,
        labelMedium = ViewerTypography.secondary,
        labelSmall = ViewerTypography.label,
    )

private val ViewerShapes =
    Shapes(
        extraSmall = RoundedCornerShape(3.dp),
        small = RoundedCornerShape(4.dp),
        medium = RoundedCornerShape(6.dp),
        large = RoundedCornerShape(8.dp),
        extraLarge = RoundedCornerShape(8.dp),
    )

internal object ViewerPalettes {
    private val light =
        ViewerColors(
            isDark = false,
            panel = Color.White,
            canvasBackground = Color(0xFFECECEC),
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
            flameTooltipMeter = Color(0xFF45A1FF),
            flameTooltipTrack = Color.Black.copy(alpha = 0.10f),
            firefoxActivity = Color(0xFF5B8DB8),
            firefoxLocalTrack = Color(0xFFF0F0F4),
        )

    private val dark =
        ViewerColors(
            isDark = true,
            panel = Color(0xFF2C2C2E),
            canvasBackground = Color(0xFF1E1E1E),
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
            flameTooltipMeter = Color(0xFF0A84FF),
            flameTooltipTrack = Color.White.copy(alpha = 0.10f),
            firefoxActivity = Color(0xFF75A7D4),
            firefoxLocalTrack = Color(0xFF202124),
        )

    fun forDark(darkTheme: Boolean, accentColor: Color): ViewerColors =
        (if (darkTheme) dark else light).withAccent(accentColor)
}

private fun ViewerColors.withAccent(accentColor: Color): ViewerColors =
    copy(
        accent = accentColor,
        accentText = accentColor.contentColor(),
        visibleViewBounds = accentColor,
        info = accentColor,
        selectedRow = accentColor.copy(alpha = if (isDark) 0.32f else 0.20f),
        searchMatchRow = accentColor.copy(alpha = 0.20f),
        searchCurrentMatchRow = accentColor.copy(alpha = 0.40f),
        searchHighlightText = accentColor,
        flameTooltipMeter = accentColor,
        firefoxActivity = accentColor,
    )

private fun Color.contentColor(): Color =
    if (red * 0.299f + green * 0.587f + blue * 0.114f > 0.55f) {
        Color(0xFF1D1D1F)
    } else {
        Color.White
    }

private val DefaultViewerAccent = Color(0xFF0A84FF)

val LocalViewerColors = staticCompositionLocalOf {
    viewerColors(darkTheme = true)
}

@Composable
public fun ViewerTheme(
    darkTheme: Boolean,
    displayScale: Float = 1f,
    accentColor: Color = LocalViewerColors.current.accent,
    typography: Typography = ViewerMaterialTypography,
    shapes: Shapes = ViewerShapes,
    content: @Composable () -> Unit,
) {
    val colors = viewerColors(darkTheme, accentColor)
    val colorScheme = viewerMaterialColorScheme(darkTheme, accentColor)
    val density = LocalDensity.current
    val scaledDensity = remember(density, displayScale) { scaledViewerDensity(density, displayScale) }
    CompositionLocalProvider(LocalViewerColors provides colors, LocalDensity provides scaledDensity) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = shapes,
            content = content,
        )
    }
}

@Immutable
public data class ViewerThemeContext(
    val colors: ViewerColors,
    val colorScheme: ColorScheme,
    val density: Density,
    val typography: Typography,
    val shapes: Shapes,
)

@Composable
public fun rememberViewerThemeContext(): ViewerThemeContext {
    val colors = LocalViewerColors.current
    val colorScheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    return remember(colors, colorScheme, density, typography, shapes) {
        ViewerThemeContext(
            colors = colors,
            colorScheme = colorScheme,
            density = density,
            typography = typography,
            shapes = shapes,
        )
    }
}

@Composable
public fun ProvideViewerThemeContext(
    viewerThemeContext: ViewerThemeContext,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalViewerColors provides viewerThemeContext.colors,
        LocalDensity provides viewerThemeContext.density,
    ) {
        MaterialTheme(
            colorScheme = viewerThemeContext.colorScheme,
            typography = viewerThemeContext.typography,
            shapes = viewerThemeContext.shapes,
            content = content,
        )
    }
}

@Composable
public fun viewerOutlinedTextFieldColors(): TextFieldColors {
    val accent = LocalViewerColors.current.accent
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = accent,
        unfocusedBorderColor = accent,
        disabledBorderColor = accent.copy(alpha = 0.45f),
    )
}

public fun scaledViewerDensity(density: Density, displayScale: Float): Density =
    Density(density = density.density * displayScale, fontScale = density.fontScale)

public fun viewerColors(
    darkTheme: Boolean,
    accentColor: Color = DefaultViewerAccent,
): ViewerColors = ViewerPalettes.forDark(darkTheme, accentColor)

public fun viewerMaterialColorScheme(
    darkTheme: Boolean,
    accentColor: Color = DefaultViewerAccent,
): ColorScheme {
    val colors = viewerColors(darkTheme, accentColor)
    return if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.accentText,
            background = colors.canvasBackground,
            surface = colors.panel,
            surfaceVariant = colors.detailRowDeep,
            primaryContainer = colors.selectedRow,
            onPrimaryContainer = colors.primaryText,
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
            onPrimary = colors.accentText,
            background = colors.canvasBackground,
            surface = colors.panel,
            surfaceVariant = colors.detailRowDeep,
            primaryContainer = colors.selectedRow,
            onPrimaryContainer = colors.primaryText,
            secondaryContainer = colors.sectionBackground,
            outline = colors.border,
            onBackground = colors.primaryText,
            onSurface = colors.primaryText,
            onSurfaceVariant = colors.secondaryText,
            error = colors.error,
        )
    }
}
