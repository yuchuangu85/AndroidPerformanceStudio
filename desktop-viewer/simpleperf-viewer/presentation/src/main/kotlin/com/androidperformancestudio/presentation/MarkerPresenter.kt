package com.androidperformancestudio.presentation

import com.androidperformancestudio.storage.MarkerProjectionRow

internal sealed interface MarkerGlyph {
    val centerX: Float

    data class Point(
        override val centerX: Float,
    ) : MarkerGlyph

    data class Interval(
        val left: Float,
        val right: Float,
    ) : MarkerGlyph {
        override val centerX: Float = (left + right) / 2f
    }
}

internal object MarkerPresenter {
    fun glyph(
        marker: MarkerProjectionRow,
        viewport: StackChartViewport,
        width: Float,
        minimumIntervalWidth: Float = 1f,
    ): MarkerGlyph {
        val left = x(marker.startNanos, viewport, width)
        if (!marker.interval) return MarkerGlyph.Point(left)
        val right = x(marker.endNanosExclusive, viewport, width)
        return MarkerGlyph.Interval(left, (right.coerceAtLeast(left + minimumIntervalWidth)).coerceAtMost(width))
    }

    fun visible(
        marker: MarkerProjectionRow,
        viewport: StackChartViewport,
    ): Boolean = marker.endNanosExclusive > viewport.startNanos && marker.startNanos < viewport.endNanosExclusive

    private fun x(
        timestamp: Long,
        viewport: StackChartViewport,
        width: Float,
    ): Float {
        val fraction = (timestamp - viewport.startNanos).toDouble() / viewport.durationNanos.toDouble()
        return (fraction * width).toFloat().coerceIn(0f, width.coerceAtLeast(0f))
    }
}
