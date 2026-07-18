package com.androidperformancestudio.presentation

import androidx.compose.ui.geometry.Rect
import com.androidperformancestudio.profileanalysis.StackChartBlock
import com.androidperformancestudio.profileanalysis.StackChartBlockId

internal data class StackChartViewport(
    val startNanos: Long,
    val endNanosExclusive: Long,
) {
    val durationNanos: Long
        get() = (endNanosExclusive - startNanos).coerceAtLeast(1L)
}

internal object StackChartPresenter {
    fun blockRect(
        block: StackChartBlock,
        viewport: StackChartViewport,
        width: Float,
        rowHeight: Float = STACK_CHART_ROW_HEIGHT_PX,
    ): Rect {
        val left = timeX(block.startNanos, viewport, width)
        val right = timeX(block.endNanosExclusive, viewport, width)
        val top = block.depth * rowHeight
        return Rect(left, top, right, top + rowHeight)
    }

    fun hitTest(
        blocks: List<StackChartBlock>,
        timestampNanos: Long,
        depth: Int,
    ): StackChartBlockId? =
        blocks
            .lastOrNull { block ->
                block.depth == depth && timestampNanos >= block.startNanos && timestampNanos < block.endNanosExclusive
            }?.id

    fun visibleBlocks(
        blocks: List<StackChartBlock>,
        viewport: StackChartViewport,
    ): List<StackChartBlock> =
        blocks.filter { block ->
            block.endNanosExclusive > viewport.startNanos && block.startNanos < viewport.endNanosExclusive
        }

    fun timeAtX(
        x: Float,
        viewport: StackChartViewport,
        width: Float,
    ): Long {
        if (width <= 0f) return viewport.startNanos
        val fraction = (x / width).coerceIn(0f, 1f)
        return viewport.startNanos + (viewport.durationNanos * fraction).toLong()
    }

    private fun timeX(
        timestampNanos: Long,
        viewport: StackChartViewport,
        width: Float,
    ): Float {
        val fraction = (timestampNanos - viewport.startNanos).toDouble() / viewport.durationNanos.toDouble()
        return (fraction * width).toFloat().coerceIn(0f, width.coerceAtLeast(0f))
    }
}

internal const val STACK_CHART_ROW_HEIGHT_PX = 16f
