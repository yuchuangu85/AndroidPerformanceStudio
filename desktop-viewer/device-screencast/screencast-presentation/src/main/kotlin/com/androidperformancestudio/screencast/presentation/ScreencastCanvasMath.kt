package com.androidperformancestudio.screencast.presentation

import kotlin.math.roundToInt

/** contain-fit geometry for a device screen inside a canvas viewport. */
data class ScreenViewport(
    val offsetX: Float,
    val offsetY: Float,
    val width: Float,
    val height: Float,
)

/** Computes the contain-fit viewport (no crop, no stretch) for a device screen. */
@Suppress("ReturnCount")
public fun containViewport(
    screenWidth: Int,
    screenHeight: Int,
    canvasWidth: Float,
    canvasHeight: Float,
): ScreenViewport {
    if (screenWidth <= 0 || screenHeight <= 0) {
        return ScreenViewport(0f, 0f, 0f, 0f)
    }
    if (canvasWidth <= 0f || canvasHeight <= 0f) {
        return ScreenViewport(0f, 0f, 0f, 0f)
    }
    val screenRatio = screenWidth.toFloat() / screenHeight
    val canvasRatio = canvasWidth / canvasHeight
    return if (screenRatio > canvasRatio) {
        val width = canvasWidth
        val height = width / screenRatio
        ScreenViewport(0f, (canvasHeight - height) / 2f, width, height)
    } else {
        val height = canvasHeight
        val width = height * screenRatio
        ScreenViewport((canvasWidth - width) / 2f, 0f, width, height)
    }
}

/**
 * Maps a canvas-space point to device-space coordinates, clamped to the screen
 * bounds. Returns null when the point falls in the letterbox area.
 */
@Suppress("ReturnCount")
public fun mapToScreen(
    canvasX: Float,
    canvasY: Float,
    viewport: ScreenViewport,
    screenWidth: Int,
    screenHeight: Int,
): Pair<Int, Int>? {
    if (viewport.width <= 0f || viewport.height <= 0f) return null
    val relativeX = (canvasX - viewport.offsetX) / viewport.width
    val relativeY = (canvasY - viewport.offsetY) / viewport.height
    if (relativeX < 0f || relativeX > 1f) {
        return null
    }
    if (relativeY < 0f || relativeY > 1f) {
        return null
    }
    return (relativeX * screenWidth).roundToInt() to (relativeY * screenHeight).roundToInt()
}
