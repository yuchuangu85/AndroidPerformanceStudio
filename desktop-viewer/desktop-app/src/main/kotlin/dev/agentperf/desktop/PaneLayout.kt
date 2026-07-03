package dev.agentperf.desktop

internal data class PaneWidths(
    val hierarchy: Float = 300f,
    val properties: Float = 300f,
)

internal object PaneLayout {
    const val HIERARCHY_MIN_WIDTH_DP = 180f
    const val PROPERTIES_MIN_WIDTH_DP = 240f
    const val CANVAS_MIN_WIDTH_DP = 320f
    const val SPLITTER_WIDTH_DP = 7f
    private const val SPLITTER_COUNT = 2

    fun fit(
        widths: PaneWidths,
        availableWidthDp: Float,
    ): PaneWidths {
        val sidePaneBudget =
            availableWidthDp -
                CANVAS_MIN_WIDTH_DP -
                SPLITTER_WIDTH_DP * SPLITTER_COUNT
        val hierarchyMaximum = maxOf(HIERARCHY_MIN_WIDTH_DP, sidePaneBudget - PROPERTIES_MIN_WIDTH_DP)
        val hierarchy = widths.hierarchy.coerceIn(HIERARCHY_MIN_WIDTH_DP, hierarchyMaximum)
        val propertiesMaximum = maxOf(PROPERTIES_MIN_WIDTH_DP, sidePaneBudget - hierarchy)
        val properties = widths.properties.coerceIn(PROPERTIES_MIN_WIDTH_DP, propertiesMaximum)
        return PaneWidths(hierarchy = hierarchy, properties = properties)
    }

    fun dragHierarchy(
        widths: PaneWidths,
        deltaDp: Float,
        availableWidthDp: Float,
    ): PaneWidths {
        val maximumWidth =
            maxOf(
                HIERARCHY_MIN_WIDTH_DP,
                availableWidthDp -
                    widths.properties -
                    CANVAS_MIN_WIDTH_DP -
                    SPLITTER_WIDTH_DP * SPLITTER_COUNT,
            )
        return widths.copy(
            hierarchy = (widths.hierarchy + deltaDp).coerceIn(HIERARCHY_MIN_WIDTH_DP, maximumWidth),
        )
    }

    fun dragProperties(
        widths: PaneWidths,
        deltaDp: Float,
        availableWidthDp: Float,
    ): PaneWidths {
        val maximumWidth =
            maxOf(
                PROPERTIES_MIN_WIDTH_DP,
                availableWidthDp -
                    widths.hierarchy -
                    CANVAS_MIN_WIDTH_DP -
                    SPLITTER_WIDTH_DP * SPLITTER_COUNT,
            )
        return widths.copy(
            properties = (widths.properties - deltaDp).coerceIn(PROPERTIES_MIN_WIDTH_DP, maximumWidth),
        )
    }
}
