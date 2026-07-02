package dev.agentperf.desktop

import dev.agentperf.protocol.Bounds
import kotlin.math.min

data class FloatRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

object CanvasGeometry {
    fun contain(
        sourceWidth: Int,
        sourceHeight: Int,
        canvasWidth: Float,
        canvasHeight: Float,
    ): FloatRect {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        val scale = min(canvasWidth / sourceWidth, canvasHeight / sourceHeight)
        val width = sourceWidth * scale
        val height = sourceHeight * scale
        return FloatRect(
            left = (canvasWidth - width) / 2f,
            top = (canvasHeight - height) / 2f,
            width = width,
            height = height,
        )
    }

    fun mapBounds(
        bounds: Bounds,
        sourceWidth: Int,
        sourceHeight: Int,
        destination: FloatRect,
    ): FloatRect {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        val scaleX = destination.width / sourceWidth
        val scaleY = destination.height / sourceHeight
        return FloatRect(
            left = destination.left + bounds.left * scaleX,
            top = destination.top + bounds.top * scaleY,
            width = bounds.width * scaleX,
            height = bounds.height * scaleY,
        )
    }
}
