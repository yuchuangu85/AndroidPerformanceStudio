package com.androidperformancestudio.presentation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.StackChartBlock
import com.androidperformancestudio.profileanalysis.StackChartBlockId
import com.androidperformancestudio.profileanalysis.StackChartSnapshot
import java.util.PriorityQueue
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

internal data class StackChartViewport(
    val startNanos: Long,
    val endNanosExclusive: Long,
) {
    val durationNanos: Long
        get() = (endNanosExclusive - startNanos).coerceAtLeast(1L)
}

internal fun StackChartSnapshot.selectedBlock(
    blockId: StackChartBlockId?,
    nodeId: FlameCallNodeId?,
    viewport: StackChartViewport? = null,
): StackChartBlock? =
    blocksById[blockId]?.takeIf { it.callNodeId == null || it.callNodeId == nodeId }
        ?: blocksByCallNodeId[nodeId]?.let { blocks ->
            blocks.firstOrNull {
                viewport == null ||
                    (it.endNanosExclusive > viewport.startNanos && it.startNanos < viewport.endNanosExclusive)
            } ?: blocks.firstOrNull()
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

internal data class VisibleStackChartBlock(
    val block: StackChartBlock,
    val rect: Rect,
)

internal fun firefoxStackPaintRect(
    rect: Rect,
    rowHeight: Float,
    density: Float,
): Rect {
    val left = floor(rect.left)
    val top = floor(rect.top + STACK_ROUND_HALF)
    val width = ceil((rect.width - 1f).coerceAtLeast(1f)) + STACK_BORDER_OPACITY
    val height = floor(rowHeight - density + STACK_ROUND_HALF).coerceAtLeast(0f)
    return Rect(left, top, left + width, top + height)
}

private const val STACK_BORDER_OPACITY = 0.4f
private const val STACK_ROUND_HALF = 0.5f

/** Immutable row/time index; selection and pointer movement never scan the complete snapshot. */
internal class StackChartIndex(
    blocks: List<StackChartBlock>,
    private val parentCallNodeIds: Map<FlameCallNodeId, FlameCallNodeId?> = emptyMap(),
) {
    private val rows = blocks.withIndex().groupBy { it.value.depth }.mapValues { (_, values) -> StackChartRow(values) }

    fun hitTest(
        timestampNanos: Long,
        depth: Int,
    ): StackChartBlock? = rows[depth]?.hitTest(timestampNanos)

    fun hitTest(
        point: Offset,
        viewport: StackChartViewport,
        width: Float,
        rowHeight: Float,
        scrollDepth: Int,
    ): StackChartBlock? {
        if (rowHeight <= 0 || point.y < 0) return null
        return if (point.x < 0 || point.x >= width) {
            null
        } else {
            val time = StackChartPresenter.timeAtX(point.x, viewport, width)
            hitTest(time, (point.y / rowHeight).toInt() + scrollDepth)
        }
    }

    fun parent(block: StackChartBlock): StackChartBlock? =
        rows[block.depth - 1]?.overlapping(block.startNanos, block.endNanosExclusive)?.lastOrNull {
            it.threadKey == block.threadKey &&
                it.startNanos <= block.startNanos &&
                it.endNanosExclusive >= block.endNanosExclusive &&
                sameParent(block, it)
        }

    fun child(block: StackChartBlock): StackChartBlock? =
        rows[block.depth + 1]?.overlapping(block.startNanos, block.endNanosExclusive)?.firstOrNull {
            it.threadKey == block.threadKey &&
                it.startNanos >= block.startNanos &&
                it.endNanosExclusive <= block.endNanosExclusive &&
                sameParent(it, block)
        }

    private fun sameParent(
        child: StackChartBlock,
        parent: StackChartBlock,
    ): Boolean =
        !parentCallNodeIds.containsKey(child.callNodeId) ||
            parentCallNodeIds[child.callNodeId] == parent.callNodeId

    fun sibling(
        block: StackChartBlock,
        direction: Int,
    ): StackChartBlock? = rows[block.depth]?.sibling(block, direction)

    @Suppress("LongParameterList")
    fun layout(
        viewport: StackChartViewport,
        width: Float,
        height: Float,
        rowHeight: Float,
        scrollDepth: Int,
        sameWidths: StackChartSameWidths? = null,
    ): List<VisibleStackChartBlock> {
        if (width <= 0 || height <= 0 || rowHeight <= 0) return emptyList()
        val endDepth = scrollDepth + ceil(height / rowHeight).toInt()
        return buildList {
            for (depth in scrollDepth until endDepth) {
                val row = rows[depth] ?: continue
                if (row.hasOverlaps) {
                    row.pixelSegments(width, { x ->
                        sameWidths?.timeAtX(x, viewport, width)
                            ?: StackChartPresenter.timeAtX(x, viewport, width)
                    }) { block, left, right ->
                        val top = (depth - scrollDepth) * rowHeight
                        add(VisibleStackChartBlock(block, Rect(left, top, right, minOf(top + rowHeight, height))))
                    }
                } else {
                    var lastDrawnPixel = 0f
                    row.forEachOverlapping(viewport.startNanos, viewport.endNanosExclusive) { block ->
                        val raw =
                            if (sameWidths == null) {
                                StackChartPresenter.blockRect(block, viewport, width, rowHeight)
                            } else {
                                Rect(
                                    sameWidths.xAtTime(block.startNanos, viewport, width),
                                    depth * rowHeight,
                                    sameWidths.xAtTime(block.endNanosExclusive, viewport, width),
                                    (depth + 1) * rowHeight,
                                )
                            }
                        val left = raw.left.coerceAtLeast(lastDrawnPixel)
                        if (raw.width > 0 && (raw.left >= lastDrawnPixel || raw.right - 1f > lastDrawnPixel)) {
                            val top = (depth - scrollDepth) * rowHeight
                            val right = raw.right.coerceAtLeast(left + 1f).coerceAtMost(width)
                            val rect = Rect(left, top, right, (top + rowHeight).coerceAtMost(height))
                            add(VisibleStackChartBlock(block, rect))
                            lastDrawnPixel = floor(left) + ceil((right - left - 1f).coerceAtLeast(1f)) + 1f
                        }
                    }
                }
            }
        }
    }
}

/** Only display coordinates change: ticks are stack changes, never event weights or elapsed time. */
internal class StackChartSameWidths(
    blocks: List<StackChartBlock>,
) {
    private val ticks =
        blocks
            .asSequence()
            .flatMap {
                sequenceOf(it.startNanos, it.endNanosExclusive)
            }.distinct()
            .sorted()
            .toList()
            .toLongArray()

    fun xAtTime(
        time: Long,
        viewport: StackChartViewport,
        width: Float,
    ): Float {
        val (start, end) = bounds(viewport)
        val tick = tickAtTime(time)
        return ((tick - start) / (end - start) * width).toFloat().coerceIn(0f, width)
    }

    fun timeAtX(
        x: Float,
        viewport: StackChartViewport,
        width: Float,
    ): Long {
        if (ticks.isEmpty() || width <= 0f) return viewport.startNanos
        val (start, end) = bounds(viewport)
        val tick = start + (x / width).coerceIn(0f, 1f) * (end - start)
        val lower = tick.toInt().coerceIn(ticks.indices)
        val upper = (lower + 1).coerceAtMost(ticks.lastIndex)
        val time = ticks[lower] + ((ticks[upper] - ticks[lower]).toDouble() * (tick - lower)).roundToLong()
        return time.coerceIn(viewport.startNanos, viewport.endNanosExclusive)
    }

    private fun bounds(viewport: StackChartViewport): Pair<Double, Double> {
        val start = tickAtTime(viewport.startNanos)
        return start to tickAtTime(viewport.endNanosExclusive).coerceAtLeast(start + MINIMUM_STACK_TICK_SPAN)
    }

    private fun tickAtTime(time: Long): Double {
        if (ticks.isEmpty()) return 0.0
        val found = ticks.binarySearch(time)
        return if (found >= 0) {
            found.toDouble()
        } else {
            val upper = (-found - 1).coerceAtMost(ticks.lastIndex)
            val lower = (upper - 1).coerceAtLeast(0)
            val duration = (ticks[upper] - ticks[lower]).coerceAtLeast(1)
            lower + (time - ticks[lower]).toDouble().coerceIn(0.0, duration.toDouble()) / duration
        }
    }
}

private class StackChartRow(
    blocks: List<IndexedValue<StackChartBlock>>,
) {
    private val ordered = blocks.sortedWith(compareBy({ it.value.startNanos }, { it.index }))
    private val positions = ordered.mapIndexed { index, block -> block.value.id to index }.toMap()
    private val maximumEnds = LongArray(ordered.size)
    val hasOverlaps: Boolean

    init {
        var maximum = Long.MIN_VALUE
        ordered.forEachIndexed { index, block ->
            maximum = maxOf(maximum, block.value.endNanosExclusive)
            maximumEnds[index] = maximum
        }
        hasOverlaps = ordered.indices.any { it > 0 && ordered[it].value.startNanos < maximumEnds[it - 1] }
    }

    fun pixelSegments(
        width: Float,
        timeAtX: (Float) -> Long,
        action: (StackChartBlock, Float, Float) -> Unit,
    ) {
        val active = PriorityQueue(compareByDescending<IndexedValue<StackChartBlock>> { it.index })
        var next = 0
        var previous: StackChartBlock? = null
        var segmentStart = 0f
        for (pixel in 0..ceil(width).toInt()) {
            val time = timeAtX(pixel + STACK_PIXEL_CENTER_OFFSET)
            while (next < ordered.size && ordered[next].value.startNanos <= time) active.add(ordered[next++])
            while (active
                    .peek()
                    ?.value
                    ?.endNanosExclusive
                    ?.let { it <= time } == true
            ) {
                active.remove()
            }
            val winner = active.peek()?.value.takeIf { pixel < width }
            if (winner?.id != previous?.id) {
                previous?.let { action(it, segmentStart, pixel.toFloat().coerceAtMost(width)) }
                previous = winner
                segmentStart = pixel.toFloat()
            }
        }
    }

    fun hitTest(time: Long): StackChartBlock? {
        var result: IndexedValue<StackChartBlock>? = null
        forEachCandidate(time, time) { candidate ->
            if (time < candidate.value.endNanosExclusive && (result?.index ?: -1) < candidate.index) result = candidate
        }
        return result?.value
    }

    fun overlapping(
        start: Long,
        end: Long,
    ): List<StackChartBlock> = buildList { forEachOverlapping(start, end, ::add) }

    fun sibling(
        block: StackChartBlock,
        direction: Int,
    ): StackChartBlock? {
        val position = positions[block.id] ?: return null
        return ordered.getOrNull(position + direction)?.value
    }

    fun forEachOverlapping(
        start: Long,
        end: Long,
        action: (StackChartBlock) -> Unit,
    ) {
        forEachCandidate(start, end) { candidate ->
            if (candidate.value.startNanos < end && candidate.value.endNanosExclusive > start) action(candidate.value)
        }
    }

    private fun forEachCandidate(
        start: Long,
        end: Long,
        action: (IndexedValue<StackChartBlock>) -> Unit,
    ) {
        var low = 0
        var high = maximumEnds.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (maximumEnds[middle] <= start) low = middle + 1 else high = middle
        }
        var index = low
        while (index < ordered.size && ordered[index].value.startNanos <= end) action(ordered[index++])
    }
}

private const val MINIMUM_STACK_TICK_SPAN = 0.000001
private const val STACK_PIXEL_CENTER_OFFSET = 0.5f
