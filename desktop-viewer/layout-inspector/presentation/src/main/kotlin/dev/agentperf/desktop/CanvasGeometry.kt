package dev.agentperf.desktop

import androidx.compose.ui.geometry.Offset
import dev.agentperf.protocol.Bounds
import kotlin.math.max
import kotlin.math.min

data class FloatRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

data class FloatSize(
    val width: Float,
    val height: Float,
)

data class CropRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

object CanvasGeometry {
    fun unmapPoint(
        point: Offset,
        source: CropRect,
        destination: FloatRect,
    ): Offset? {
        if (
            point.x !in destination.left..(destination.left + destination.width) ||
            point.y !in destination.top..(destination.top + destination.height)
        ) return null
        return Offset(
            x = source.left + (point.x - destination.left) * source.width / destination.width,
            y = source.top + (point.y - destination.top) * source.height / destination.height,
        )
    }

    fun previewSize(
        source: CropRect,
        maxWidth: Float,
        maxHeight: Float,
        portraitMaxWidth: Float,
    ): FloatSize {
        require(source.width > 0 && source.height > 0) { "Source dimensions must be positive" }
        require(maxWidth > 0f && maxHeight > 0f && portraitMaxWidth > 0f) {
            "Preview constraints must be positive"
        }
        val availableWidth = if (source.width <= source.height) {
            min(maxWidth, portraitMaxWidth)
        } else {
            maxWidth
        }
        val scale = min(availableWidth / source.width, maxHeight / source.height)
        return FloatSize(
            width = source.width * scale,
            height = source.height * scale,
        )
    }

    fun sourceRect(
        appBounds: Bounds?,
        displayWidth: Int,
        displayHeight: Int,
        appOnly: Boolean,
    ): CropRect {
        require(displayWidth > 0 && displayHeight > 0) {
            "Display dimensions must be positive"
        }
        val fullDisplay = CropRect(0, 0, displayWidth, displayHeight)
        if (!appOnly || appBounds == null) return fullDisplay

        val left = appBounds.left.coerceIn(0, displayWidth)
        val top = appBounds.top.coerceIn(0, displayHeight)
        val right = appBounds.right.coerceIn(0, displayWidth)
        val bottom = appBounds.bottom.coerceIn(0, displayHeight)
        if (right <= left || bottom <= top) return fullDisplay
        return CropRect(left, top, right - left, bottom - top)
    }

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

    fun mapBounds(
        bounds: Bounds,
        source: CropRect,
        destination: FloatRect,
    ): FloatRect? {
        require(source.width > 0 && source.height > 0) { "Source dimensions must be positive" }
        val left = max(bounds.left, source.left)
        val top = max(bounds.top, source.top)
        val right = min(bounds.right, source.right)
        val bottom = min(bounds.bottom, source.bottom)
        if (right <= left || bottom <= top) return null

        val scaleX = destination.width / source.width
        val scaleY = destination.height / source.height
        return FloatRect(
            left = destination.left + (left - source.left) * scaleX,
            top = destination.top + (top - source.top) * scaleY,
            width = (right - left) * scaleX,
            height = (bottom - top) * scaleY,
        )
    }
}
