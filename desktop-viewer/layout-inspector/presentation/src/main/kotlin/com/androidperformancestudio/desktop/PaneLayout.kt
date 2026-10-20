package com.androidperformancestudio.desktop

internal data class PaneWidths(
    val hierarchy: Float = 320f,
    val properties: Float = 300f,
)

internal object PaneLayout {
    const val HIERARCHY_MIN_WIDTH_DP = 280f
    // Deep view trees need room for indentation; the window budget still keeps the canvas minimum.
    const val HIERARCHY_MAX_WIDTH_DP = 640f
    private const val COMPACT_HIERARCHY_MIN_WIDTH_DP = 180f
    private const val COMPACT_PROPERTIES_MIN_WIDTH_DP = 200f
    private const val COMPACT_CANVAS_MIN_WIDTH_DP = 280f
    const val PROPERTIES_MIN_WIDTH_DP = 240f
    const val CANVAS_MIN_WIDTH_DP = 320f
    const val SPLITTER_WIDTH_DP = 7f

    fun fit(
        widths: PaneWidths,
        availableWidthDp: Float,
        showHierarchy: Boolean = true,
        showDetails: Boolean = true,
    ): PaneWidths {
        if (!showHierarchy && !showDetails) return widths

        val compact = availableWidthDp < regularMinimumWidth(showHierarchy, showDetails)
        val hierarchyMinimum = if (compact) COMPACT_HIERARCHY_MIN_WIDTH_DP else HIERARCHY_MIN_WIDTH_DP
        val propertiesMinimum = if (compact) COMPACT_PROPERTIES_MIN_WIDTH_DP else PROPERTIES_MIN_WIDTH_DP
        val canvasMinimum = if (compact) COMPACT_CANVAS_MIN_WIDTH_DP else CANVAS_MIN_WIDTH_DP
        val sidePaneBudget = availableWidthDp - canvasMinimum - splitterWidth(showHierarchy, showDetails)
        val reservedPropertiesMinimum = if (showDetails) propertiesMinimum else 0f
        val hierarchyMaximum =
            (sidePaneBudget - reservedPropertiesMinimum)
                .coerceAtLeast(hierarchyMinimum)
                .coerceAtMost(HIERARCHY_MAX_WIDTH_DP)
        val hierarchy = if (showHierarchy) widths.hierarchy.coerceIn(hierarchyMinimum, hierarchyMaximum) else widths.hierarchy
        val reservedHierarchyWidth = if (showHierarchy) hierarchy else 0f
        val propertiesMaximum = maxOf(propertiesMinimum, sidePaneBudget - reservedHierarchyWidth)
        val properties = if (showDetails) widths.properties.coerceIn(propertiesMinimum, propertiesMaximum) else widths.properties
        return PaneWidths(hierarchy = hierarchy, properties = properties)
    }

    fun dragHierarchy(
        widths: PaneWidths,
        deltaDp: Float,
        availableWidthDp: Float,
        showHierarchy: Boolean = true,
        showDetails: Boolean = true,
    ): PaneWidths {
        if (!showHierarchy) return widths

        val fitted = fit(widths, availableWidthDp, showHierarchy, showDetails)
        val compact = availableWidthDp < regularMinimumWidth(showHierarchy, showDetails)
        val hierarchyMinimum = if (compact) COMPACT_HIERARCHY_MIN_WIDTH_DP else HIERARCHY_MIN_WIDTH_DP
        val canvasMinimum = if (compact) COMPACT_CANVAS_MIN_WIDTH_DP else CANVAS_MIN_WIDTH_DP
        val reservedPropertiesWidth = if (showDetails) fitted.properties else 0f
        val maximumWidth =
            (availableWidthDp - reservedPropertiesWidth - canvasMinimum - splitterWidth(showHierarchy, showDetails))
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
        showHierarchy: Boolean = true,
        showDetails: Boolean = true,
    ): PaneWidths {
        if (!showDetails) return widths

        val fitted = fit(widths, availableWidthDp, showHierarchy, showDetails)
        val compact = availableWidthDp < regularMinimumWidth(showHierarchy, showDetails)
        val propertiesMinimum = if (compact) COMPACT_PROPERTIES_MIN_WIDTH_DP else PROPERTIES_MIN_WIDTH_DP
        val canvasMinimum = if (compact) COMPACT_CANVAS_MIN_WIDTH_DP else CANVAS_MIN_WIDTH_DP
        val reservedHierarchyWidth = if (showHierarchy) fitted.hierarchy else 0f
        val maximumWidth =
            maxOf(
                propertiesMinimum,
                availableWidthDp -
                    reservedHierarchyWidth -
                    canvasMinimum -
                    splitterWidth(showHierarchy, showDetails),
            )
        return fitted.copy(
            properties = (fitted.properties - deltaDp).coerceIn(propertiesMinimum, maximumWidth),
        )
    }

    private fun regularMinimumWidth(
        showHierarchy: Boolean,
        showDetails: Boolean,
    ): Float =
        (if (showHierarchy) HIERARCHY_MIN_WIDTH_DP else 0f) +
            (if (showDetails) PROPERTIES_MIN_WIDTH_DP else 0f) +
            CANVAS_MIN_WIDTH_DP + splitterWidth(showHierarchy, showDetails)

    private fun splitterWidth(
        showHierarchy: Boolean,
        showDetails: Boolean,
    ): Float = (if (showHierarchy) SPLITTER_WIDTH_DP else 0f) + (if (showDetails) SPLITTER_WIDTH_DP else 0f)
}
