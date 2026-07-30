package com.androidperformancestudio.desktop

internal enum class TimelineScrollDirection {
    LEFT,
    RIGHT,
}

internal data class TimelineScrollButtons(
    val visible: Boolean,
    val leftEnabled: Boolean,
    val rightEnabled: Boolean,
)

internal object TimelineScrollNavigation {
    private const val PAGE_FRACTION = 0.8f

    fun buttons(
        canScrollBackward: Boolean,
        canScrollForward: Boolean,
    ): TimelineScrollButtons =
        TimelineScrollButtons(
            visible = canScrollBackward || canScrollForward,
            leftEnabled = canScrollBackward,
            rightEnabled = canScrollForward,
        )

    fun scrollDistance(
        direction: TimelineScrollDirection,
        viewportWidthPx: Int,
    ): Float {
        val distance = viewportWidthPx.coerceAtLeast(0) * PAGE_FRACTION
        return if (direction == TimelineScrollDirection.LEFT) -distance else distance
    }
}
