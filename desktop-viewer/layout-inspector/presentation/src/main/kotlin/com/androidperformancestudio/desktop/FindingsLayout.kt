package com.androidperformancestudio.desktop

internal object FindingsLayoutTokens {
    const val VERTICAL_PADDING_DP = 2f
    const val SEVERITY_COLUMN_WIDTH_DP = 100f
    const val NODE_COLUMN_WIDTH_DP = 96f
    const val TABLE_HEADER_HEIGHT_DP = 28f
    const val MIN_VISIBLE_ROW_HEIGHT_DP = 26f
}

internal object FindingsLayout {
    const val DEFAULT_HEIGHT_DP = 240f
    const val MIN_HEIGHT_DP = 56f
    const val SPLITTER_HEIGHT_DP = 7f
    private const val MAX_HEIGHT_RATIO = 0.5f

    fun fit(
        heightDp: Float,
        availableHeightDp: Float,
    ): Float {
        val maximumHeight = maxOf(MIN_HEIGHT_DP, availableHeightDp * MAX_HEIGHT_RATIO)
        return heightDp.coerceIn(MIN_HEIGHT_DP, maximumHeight)
    }

    fun drag(
        heightDp: Float,
        deltaDp: Float,
        availableHeightDp: Float,
    ): Float = fit(
        heightDp = heightDp - deltaDp,
        availableHeightDp = availableHeightDp,
    )

    fun showTableHeader(heightDp: Float, hasTimeline: Boolean): Boolean {
        val timelineHeight = if (hasTimeline) PanelHeaderLayout.HEIGHT_DP else 0f
        val dividerHeight = if (hasTimeline) 3f else 2f
        return heightDp >=
            PanelHeaderLayout.HEIGHT_DP + timelineHeight +
                FindingsLayoutTokens.TABLE_HEADER_HEIGHT_DP +
                FindingsLayoutTokens.MIN_VISIBLE_ROW_HEIGHT_DP + dividerHeight
    }

    fun showTimeline(heightDp: Float, hasTimeline: Boolean): Boolean =
        hasTimeline &&
            heightDp >= PanelHeaderLayout.HEIGHT_DP * 2 +
                FindingsLayoutTokens.MIN_VISIBLE_ROW_HEIGHT_DP + 2f
}
