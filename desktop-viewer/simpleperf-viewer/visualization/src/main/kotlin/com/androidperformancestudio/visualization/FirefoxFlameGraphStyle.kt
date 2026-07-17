@file:Suppress("LongParameterList", "MagicNumber")

package com.androidperformancestudio.visualization

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
            return when (theme) {
                FlameTheme.LIGHT -> light(devicePixelRatio)
                FlameTheme.DARK -> dark(devicePixelRatio)
            }
        }
    }
}

data class FirefoxFlameCategoryStyle(
    val selectedFill: FlameGraphColor,
    val unselectedFill: FlameGraphColor,
    val selectedText: FlameGraphColor,
)

private fun light(devicePixelRatio: Float) =
    FirefoxFlameGraphStyle(
        theme = FlameTheme.LIGHT,
        canvasBackground = argb(0xFFFFFFFF),
        canvasForeground = argb(0xFF000000),
        viewportBorder = argb(0xFFD7D7DB),
        panelSurface = argb(0xFFF9F9FA),
        raisedSurface = argb(0xFFF9F9FA),
        surfaceBorder = argb(0xFFCCCCCC),
        mutedForeground = argb(0xFF737373),
        controlSelectedSurface = argb(0xFFEDEDF0),
        focusOutline = argb(0xFF000000),
        selectedLineSurface = argb(0xFFEDEDF0),
        rowHeightPx = cssPixels(16f, devicePixelRatio),
        labelFontSizePx = cssPixels(10f, devicePixelRatio),
        labelStartOffsetPx = cssPixels(3f, devicePixelRatio),
        labelBaselineOffsetPx = cssPixels(11f, devicePixelRatio),
        categoryStyles =
            categoryStyles(
                system = style(0xFFFFE129, 0x70FFE900, 0xFF000000),
                kernel = style(0xFFFF9400, 0x60FF9400, 0xFFFFFFFF),
                native = style(0xFFED00B5, 0x60ED00B5, 0xFFFFFFFF),
                managed = style(0xFF12BC00, 0x6012BC00, 0xFFFFFFFF),
                graphics = style(0xFF12BC00, 0x6012BC00, 0xFFFFFFFF),
                io = style(0xFFFFE129, 0x70FFE900, 0xFF000000),
                network = style(0xFF45A1FF, 0x6045A1FF, 0xFF000000),
                other = style(0xFFB1B1B3, 0x60B1B1B3, 0xFF000000),
            ),
    )

private fun dark(devicePixelRatio: Float) =
    FirefoxFlameGraphStyle(
        theme = FlameTheme.DARK,
        canvasBackground = argb(0xFF18181A),
        canvasForeground = argb(0xFFEDEDF0),
        viewportBorder = argb(0xFF38383D),
        panelSurface = argb(0xFF232327),
        raisedSurface = argb(0xFF232327),
        surfaceBorder = argb(0xFF4A4A4F),
        mutedForeground = argb(0xFFB1B1B3),
        controlSelectedSurface = argb(0xFF2A2A2E),
        focusOutline = argb(0xFFFFFFFF),
        selectedLineSurface = argb(0xFF38383D),
        rowHeightPx = cssPixels(16f, devicePixelRatio),
        labelFontSizePx = cssPixels(10f, devicePixelRatio),
        labelStartOffsetPx = cssPixels(3f, devicePixelRatio),
        labelBaselineOffsetPx = cssPixels(11f, devicePixelRatio),
        categoryStyles =
            categoryStyles(
                system = style(0xFFBE9B00, 0x85BE9B00, 0xFFEDEDF0),
                kernel = style(0xFFD76E00, 0x60D76E00, 0xFFFFFFFF),
                native = style(0xFFB5007F, 0x60B5007F, 0xFFFFFFFF),
                managed = style(0xFF058B00, 0x60058B00, 0xFFFFFFFF),
                graphics = style(0xFF058B00, 0x60058B00, 0xFFFFFFFF),
                io = style(0xFFBE9B00, 0x85BE9B00, 0xFFEDEDF0),
                network = style(0xFF45A1FF, 0x6045A1FF, 0xFFEDEDF0),
                other = style(0xFF737373, 0x60737373, 0xFFEDEDF0),
            ),
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

private fun style(
    selectedFill: Long,
    unselectedFill: Long,
    selectedText: Long,
) = FirefoxFlameCategoryStyle(argb(selectedFill), argb(unselectedFill), argb(selectedText))

private fun argb(value: Long) = FlameGraphColor(value.toInt())

private fun cssPixels(
    value: Float,
    devicePixelRatio: Float,
): Float = value * devicePixelRatio
