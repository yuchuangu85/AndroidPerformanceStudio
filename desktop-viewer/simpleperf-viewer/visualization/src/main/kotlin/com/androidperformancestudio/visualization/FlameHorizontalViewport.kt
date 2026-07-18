package com.androidperformancestudio.visualization

data class FlameHorizontalViewport(
    val start: Double = 0.0,
    val end: Double = 1.0,
) {
    init {
        require(start.isFinite() && end.isFinite()) { "horizontal viewport bounds must be finite" }
        require(start >= 0.0 && end <= 1.0 && start < end) { "horizontal viewport must stay within 0..1" }
    }

    val span: Double
        get() = end - start

    fun navigate(action: NavigationAction): FlameHorizontalViewport =
        when (action) {
            NavigationAction.ZOOM_IN -> zoom(ZOOM_IN_FACTOR)
            NavigationAction.ZOOM_OUT -> zoom(ZOOM_OUT_FACTOR)
            NavigationAction.PAN_LEFT -> pan(-PAN_FRACTION)
            NavigationAction.PAN_RIGHT -> pan(PAN_FRACTION)
        }

    private fun zoom(factor: Double): FlameHorizontalViewport {
        val nextSpan = (span * factor).coerceIn(MINIMUM_SPAN, 1.0)
        val center = (start + end) / 2.0
        val nextStart = (center - nextSpan / 2.0).coerceIn(0.0, 1.0 - nextSpan)
        return FlameHorizontalViewport(nextStart, nextStart + nextSpan)
    }

    private fun pan(fraction: Double): FlameHorizontalViewport {
        val nextStart = (start + span * fraction).coerceIn(0.0, 1.0 - span)
        return FlameHorizontalViewport(nextStart, nextStart + span)
    }
}

private const val ZOOM_IN_FACTOR = 0.5
private const val ZOOM_OUT_FACTOR = 2.0
private const val PAN_FRACTION = 0.25
private const val MINIMUM_SPAN = 0.001
