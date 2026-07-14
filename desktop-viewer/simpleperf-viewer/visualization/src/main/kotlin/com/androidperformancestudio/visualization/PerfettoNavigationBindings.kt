package com.androidperformancestudio.visualization

enum class NavigationAction {
    ZOOM_IN,
    ZOOM_OUT,
    PAN_LEFT,
    PAN_RIGHT,
}

object PerfettoNavigationBindings {
    fun actionForKey(key: Char): NavigationAction? =
        when (key.uppercaseChar()) {
            'W' -> NavigationAction.ZOOM_IN
            'S' -> NavigationAction.ZOOM_OUT
            'A' -> NavigationAction.PAN_LEFT
            'D' -> NavigationAction.PAN_RIGHT
            else -> null
        }

    fun actionForScroll(
        verticalDelta: Float,
        controlPressed: Boolean,
    ): NavigationAction? {
        if (!controlPressed || verticalDelta == 0f) return null

        return if (verticalDelta < 0f) NavigationAction.ZOOM_IN else NavigationAction.ZOOM_OUT
    }
}
