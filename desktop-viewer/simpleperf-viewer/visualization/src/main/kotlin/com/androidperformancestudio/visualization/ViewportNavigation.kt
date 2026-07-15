package com.androidperformancestudio.visualization

import kotlin.math.roundToLong

fun TimeViewport.navigate(
    action: NavigationAction,
    bounds: TimeViewport,
    anchorFraction: Double = DEFAULT_ANCHOR_FRACTION,
): TimeViewport {
    require(anchorFraction in 0.0..1.0) { "anchorFraction must be between zero and one" }
    val range = LongRangeWindow(startNanos, endNanosExclusive)
    val limit = LongRangeWindow(bounds.startNanos, bounds.endNanosExclusive)
    val navigated = range.navigate(action, limit, anchorFraction)
    return TimeViewport(navigated.start, navigated.endExclusive)
}

fun TimeViewport.selection(
    startPixel: Float,
    endPixel: Float,
    widthPixels: Float,
): TimeViewport {
    require(widthPixels > 0f) { "widthPixels must be positive" }
    val first = minOf(startPixel, endPixel).coerceIn(0f, widthPixels) / widthPixels
    val last = maxOf(startPixel, endPixel).coerceIn(0f, widthPixels) / widthPixels
    val duration = endNanosExclusive - startNanos
    val selectionStart = startNanos + (duration * first).roundToLong()
    val selectionEnd = startNanos + (duration * last).roundToLong()
    return TimeViewport(selectionStart, selectionEnd.coerceAtLeast(selectionStart + 1))
}

private data class LongRangeWindow(
    val start: Long,
    val endExclusive: Long,
) {
    val duration: Long = endExclusive - start

    fun navigate(
        action: NavigationAction,
        bounds: LongRangeWindow,
        anchorFraction: Double,
    ): LongRangeWindow =
        when (action) {
            NavigationAction.ZOOM_IN -> zoom(ZOOM_IN_FACTOR, bounds, anchorFraction)
            NavigationAction.ZOOM_OUT -> zoom(ZOOM_OUT_FACTOR, bounds, anchorFraction)
            NavigationAction.PAN_LEFT -> pan(-PAN_FRACTION, bounds)
            NavigationAction.PAN_RIGHT -> pan(PAN_FRACTION, bounds)
        }

    private fun zoom(
        factor: Double,
        bounds: LongRangeWindow,
        anchorFraction: Double,
    ): LongRangeWindow {
        val newDuration = (duration * factor).roundToLong().coerceIn(1, bounds.duration)
        val anchor = start + (duration * anchorFraction).roundToLong()
        val newStart = anchor - (newDuration * anchorFraction).roundToLong()
        return LongRangeWindow(newStart, newStart + newDuration).clamp(bounds)
    }

    private fun pan(
        fraction: Double,
        bounds: LongRangeWindow,
    ): LongRangeWindow {
        val distance = (duration * fraction).roundToLong()
        return LongRangeWindow(start + distance, endExclusive + distance).clamp(bounds)
    }

    private fun clamp(bounds: LongRangeWindow): LongRangeWindow {
        if (duration >= bounds.duration) return bounds
        return when {
            start < bounds.start -> LongRangeWindow(bounds.start, bounds.start + duration)
            endExclusive > bounds.endExclusive ->
                LongRangeWindow(bounds.endExclusive - duration, bounds.endExclusive)
            else -> this
        }
    }
}

private const val DEFAULT_ANCHOR_FRACTION = 0.5
private const val ZOOM_IN_FACTOR = 0.5
private const val ZOOM_OUT_FACTOR = 1.5
private const val PAN_FRACTION = 0.25
