package com.androidperformancestudio.frame.presentation

import kotlin.math.floor

internal data class FrameTimelineWindow(
    val startIndex: Int,
    val count: Int,
) {
    val endExclusive: Int get() = startIndex + count

    fun indexAt(
        x: Float,
        width: Float,
    ): Int? {
        if (count == 0 || width <= 0f) return null
        return startIndex + floor(x / width * count).toInt().coerceIn(0, count - 1)
    }
}

internal fun frameTimelineWindow(
    totalFrames: Int,
    requestedStart: Int,
    requestedCount: Int,
): FrameTimelineWindow {
    if (totalFrames <= 0) return FrameTimelineWindow(0, 0)
    val count = requestedCount.coerceIn(1, totalFrames)
    return FrameTimelineWindow(requestedStart.coerceIn(0, totalFrames - count), count)
}

internal fun centeredFrameTimelineWindow(
    totalFrames: Int,
    selectedIndex: Int,
    requestedCount: Int,
): FrameTimelineWindow = frameTimelineWindow(totalFrames, selectedIndex - requestedCount / 2, requestedCount)
