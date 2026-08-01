package com.androidperformancestudio.desktop

import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot

internal class CanvasPointerSelection(
    private val clickTolerancePx: Float = 4f,
) {
    var hoveredNodeId: String? = null
        private set
    private var lastClickPoint: Offset? = null
    private var lastHitPath: List<String> = emptyList()
    private var clickIndex: Int = 0

    fun move(point: Offset, hitPath: List<String>): String? {
        hoveredNodeId = hitPath.firstOrNull()
        val last = lastClickPoint
        if (last != null && hypot(point.x - last.x, point.y - last.y) > clickTolerancePx) {
            clearCycle()
        }
        return hoveredNodeId
    }

    fun click(
        point: Offset,
        hitPath: List<String>,
        cycleCandidates: Boolean = true,
    ): String? {
        if (hitPath.isEmpty()) {
            clearCycle()
            return null
        }
        if (!cycleCandidates) {
            clearCycle()
            return hitPath.first()
        }
        val samePoint = lastClickPoint?.let {
            hypot(point.x - it.x, point.y - it.y) <= clickTolerancePx
        } == true
        if (!samePoint || hitPath != lastHitPath) clickIndex = 0
        val selected = hitPath[clickIndex.coerceAtMost(hitPath.lastIndex)]
        clickIndex = (clickIndex + 1) % hitPath.size
        lastClickPoint = point
        lastHitPath = hitPath
        return selected
    }

    fun leave() {
        hoveredNodeId = null
    }

    fun reset() {
        hoveredNodeId = null
        clearCycle()
    }

    private fun clearCycle() {
        lastClickPoint = null
        lastHitPath = emptyList()
        clickIndex = 0
    }
}
