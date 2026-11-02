package com.androidperformancestudio.presentation

import kotlin.math.roundToLong

internal fun navigateStackChartViewport(
    viewport: StackChartViewport,
    bounds: StackChartViewport,
    zoomFactor: Double = 1.0,
    panFraction: Double = 0.0,
    anchorFraction: Double = 0.5,
): StackChartViewport {
    val validZoom = zoomFactor.isFinite() && zoomFactor > 0
    if (!validZoom || !panFraction.isFinite() || !anchorFraction.isFinite()) return viewport
    val span = viewport.durationNanos.toDouble()
    val duration = (span * zoomFactor).coerceIn(1.0, bounds.durationNanos.toDouble()).roundToLong()
    val anchor = anchorFraction.coerceIn(0.0, 1.0)
    val start =
        (viewport.startNanos + span * panFraction + (span - duration) * anchor)
            .roundToLong()
            .coerceIn(bounds.startNanos, bounds.endNanosExclusive - duration)
    return StackChartViewport(start, start + duration)
}
