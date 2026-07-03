package dev.agentperf.desktop

internal object FindingsLayout {
    const val DEFAULT_HEIGHT_DP = 89f
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
}
