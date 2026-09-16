@file:Suppress("LongParameterList", "MagicNumber")

package com.androidperformancestudio.visualization

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.androidperformancestudio.ui.ViewerFlameGraphCategoryColors
import com.androidperformancestudio.ui.ViewerFlameGraphColors
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.ViewerVisualizationColors

data class FirefoxFlameGraphStyle(
    val theme: FlameTheme,
    val canvasBackground: FlameGraphColor,
    val canvasForeground: FlameGraphColor,
    val viewportBorder: FlameGraphColor,
    val panelSurface: FlameGraphColor,
    val raisedSurface: FlameGraphColor,
    val surfaceBorder: FlameGraphColor,
    val mutedForeground: FlameGraphColor,
    val controlSelectedSurface: FlameGraphColor,
    val focusOutline: FlameGraphColor,
    val selectedLineSurface: FlameGraphColor,
    val rowHeightPx: Float,
    val labelFontSizePx: Float,
    val labelStartOffsetPx: Float,
    val labelBaselineOffsetPx: Float,
    val categoryStyles: List<FirefoxFlameCategoryStyle>,
) {
    init {
        require(rowHeightPx.isFinite() && rowHeightPx > 0f)
        require(labelFontSizePx.isFinite() && labelFontSizePx > 0f)
        require(labelStartOffsetPx.isFinite() && labelStartOffsetPx >= 0f)
        require(labelBaselineOffsetPx.isFinite() && labelBaselineOffsetPx >= 0f)
        require(categoryStyles.size == FlameCategoryRole.entries.size)
    }

    fun categoryStyle(role: FlameCategoryRole): FirefoxFlameCategoryStyle = categoryStyles[role.ordinal]

    fun nodeColors(
        category: String?,
        state: FlameNodeVisualState = FlameNodeVisualState(),
    ): FlameNodeColors = nodeColors(FlameGraphPalette.categoryRole(category), state)

    fun nodeColors(
        role: FlameCategoryRole,
        state: FlameNodeVisualState = FlameNodeVisualState(),
    ): FlameNodeColors {
        val categoryStyle = categoryStyle(role)
        val highlighted = state.selected || state.hovered || state.context
        return FlameNodeColors(
            fill = if (highlighted) categoryStyle.selectedFill else categoryStyle.unselectedFill,
            foreground = if (highlighted) categoryStyle.selectedText else canvasForeground,
            outline = focusOutline.takeIf { highlighted },
        )
    }

    companion object {
        fun resolve(
            theme: FlameTheme,
            devicePixelRatio: Float = 1f,
        ): FirefoxFlameGraphStyle {
            require(devicePixelRatio.isFinite() && devicePixelRatio > 0f)
            return flameGraphStyle(
                theme = theme,
                colors =
                    if (theme == FlameTheme.DARK) {
                        ViewerVisualizationColors.flameDark
                    } else {
                        ViewerVisualizationColors.flameLight
                    },
                devicePixelRatio = devicePixelRatio,
            )
        }
    }
}

data class FirefoxFlameCategoryStyle(
    val selectedFill: FlameGraphColor,
    val unselectedFill: FlameGraphColor,
    val selectedText: FlameGraphColor,
)

private fun flameGraphStyle(
    theme: FlameTheme,
    colors: ViewerFlameGraphColors,
    devicePixelRatio: Float,
): FirefoxFlameGraphStyle =
    FirefoxFlameGraphStyle(
        theme = theme,
        canvasBackground = argb(colors.canvasBackground),
        canvasForeground = argb(colors.canvasForeground),
        viewportBorder = argb(colors.viewportBorder),
        panelSurface = argb(colors.panelSurface),
        raisedSurface = argb(colors.raisedSurface),
        surfaceBorder = argb(colors.surfaceBorder),
        mutedForeground = argb(colors.mutedForeground),
        controlSelectedSurface = argb(colors.controlSelectedSurface),
        focusOutline = argb(colors.focusOutline),
        selectedLineSurface = argb(colors.selectedLineSurface),
        rowHeightPx = cssPixels(16f, devicePixelRatio),
        labelFontSizePx = cssPixels(ViewerTypography.flameGraphLabelPixels, devicePixelRatio),
        labelStartOffsetPx = cssPixels(3f, devicePixelRatio),
        labelBaselineOffsetPx = cssPixels(11f, devicePixelRatio),
        categoryStyles =
            categoryStyles(
                system = colors.system.toFirefoxCategoryStyle(),
                kernel = colors.kernel.toFirefoxCategoryStyle(),
                native = colors.native.toFirefoxCategoryStyle(),
                managed = colors.managed.toFirefoxCategoryStyle(),
                graphics = colors.graphics.toFirefoxCategoryStyle(),
                io = colors.io.toFirefoxCategoryStyle(),
                network = colors.network.toFirefoxCategoryStyle(),
                other = colors.other.toFirefoxCategoryStyle(),
            ),
    )

private fun ViewerFlameGraphCategoryColors.toFirefoxCategoryStyle(): FirefoxFlameCategoryStyle =
    FirefoxFlameCategoryStyle(
        selectedFill = argb(selectedFill),
        unselectedFill = argb(unselectedFill),
        selectedText = argb(selectedText),
    )

private fun categoryStyles(
    system: FirefoxFlameCategoryStyle,
    kernel: FirefoxFlameCategoryStyle,
    native: FirefoxFlameCategoryStyle,
    managed: FirefoxFlameCategoryStyle,
    graphics: FirefoxFlameCategoryStyle,
    io: FirefoxFlameCategoryStyle,
    network: FirefoxFlameCategoryStyle,
    other: FirefoxFlameCategoryStyle,
): List<FirefoxFlameCategoryStyle> = listOf(system, kernel, native, managed, graphics, io, network, other)

private fun argb(value: Color): FlameGraphColor = FlameGraphColor(value.toArgb())

private fun cssPixels(
    value: Float,
    devicePixelRatio: Float,
): Float = value * devicePixelRatio
