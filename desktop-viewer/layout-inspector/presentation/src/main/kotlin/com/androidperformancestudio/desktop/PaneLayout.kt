package com.androidperformancestudio.desktop

internal data class PaneWidths(
    val hierarchy: Float = 320f,
    val properties: Float = 300f,
)

internal object PaneLayout {
    const val HIERARCHY_MIN_WIDTH_DP = 280f
    const val HIERARCHY_MAX_WIDTH_DP = 360f
    private const val COMPACT_HIERARCHY_MIN_WIDTH_DP = 180f
    private const val COMPACT_PROPERTIES_MIN_WIDTH_DP = 200f
    private const val COMPACT_CANVAS_MIN_WIDTH_DP = 280f
    const val PROPERTIES_MIN_WIDTH_DP = 240f
    const val CANVAS_MIN_WIDTH_DP = 320f
    const val SPLITTER_WIDTH_DP = 7f
    private const val SPLITTER_COUNT = 2

    fun fit(
        widths: PaneWidths,
        availableWidthDp: Float,
    ): PaneWidths {
        val compact = availableWidthDp < regularMinimumWidth()
        val hierarchyMinimum = if (compact) COMPACT_HIERARCHY_MIN_WIDTH_DP else HIERARCHY_MIN_WIDTH_DP
        val propertiesMinimum = if (compact) COMPACT_PROPERTIES_MIN_WIDTH_DP else PROPERTIES_MIN_WIDTH_DP
        val canvasMinimum = if (compact) COMPACT_CANVAS_MIN_WIDTH_DP else CANVAS_MIN_WIDTH_DP
        val sidePaneBudget =
            availableWidthDp -
                canvasMinimum -
                SPLITTER_WIDTH_DP * SPLITTER_COUNT
        val hierarchyMaximum = (sidePaneBudget - propertiesMinimum)
            .coerceAtLeast(hierarchyMinimum)
            .coerceAtMost(HIERARCHY_MAX_WIDTH_DP)
        val hierarchy = widths.hierarchy.coerceIn(hierarchyMinimum, hierarchyMaximum)
        val propertiesMaximum = maxOf(propertiesMinimum, sidePaneBudget - hierarchy)
        val properties = widths.properties.coerceIn(propertiesMinimum, propertiesMaximum)
        return PaneWidths(hierarchy = hierarchy, properties = properties)
    }

    fun dragHierarchy(
        widths: PaneWidths,
        deltaDp: Float,
        availableWidthDp: Float,
    ): PaneWidths {
        val fitted = fit(widths, availableWidthDp)
        val hierarchyMinimum = if (availableWidthDp < regularMinimumWidth()) COMPACT_HIERARCHY_MIN_WIDTH_DP else HIERARCHY_MIN_WIDTH_DP
        val canvasMinimum = if (availableWidthDp < regularMinimumWidth()) COMPACT_CANVAS_MIN_WIDTH_DP else CANVAS_MIN_WIDTH_DP
        val maximumWidth =
            (availableWidthDp - fitted.properties - canvasMinimum - SPLITTER_WIDTH_DP * SPLITTER_COUNT)
                .coerceAtLeast(hierarchyMinimum)
                .coerceAtMost(HIERARCHY_MAX_WIDTH_DP)
        return fitted.copy(
            hierarchy = (fitted.hierarchy + deltaDp).coerceIn(hierarchyMinimum, maximumWidth),
        )
    }

    fun dragProperties(
        widths: PaneWidths,
        deltaDp: Float,
        availableWidthDp: Float,
    ): PaneWidths {
        val fitted = fit(widths, availableWidthDp)
        val propertiesMinimum = if (availableWidthDp < regularMinimumWidth()) COMPACT_PROPERTIES_MIN_WIDTH_DP else PROPERTIES_MIN_WIDTH_DP
        val canvasMinimum = if (availableWidthDp < regularMinimumWidth()) COMPACT_CANVAS_MIN_WIDTH_DP else CANVAS_MIN_WIDTH_DP
        val maximumWidth =
            maxOf(
                propertiesMinimum,
                availableWidthDp -
                    fitted.hierarchy -
                    canvasMinimum -
                    SPLITTER_WIDTH_DP * SPLITTER_COUNT,
            )
        return fitted.copy(
            properties = (fitted.properties - deltaDp).coerceIn(propertiesMinimum, maximumWidth),
        )
    }

    private fun regularMinimumWidth(): Float =
        HIERARCHY_MIN_WIDTH_DP + PROPERTIES_MIN_WIDTH_DP + CANVAS_MIN_WIDTH_DP + SPLITTER_WIDTH_DP * SPLITTER_COUNT
}
