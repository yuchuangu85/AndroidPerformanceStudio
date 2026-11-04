@file:Suppress("TooManyFunctions")

package com.androidperformancestudio.visualization

import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import java.util.Collections
import kotlin.math.ceil
import kotlin.math.floor

data class FlameViewport(
    val widthPx: Int,
    val heightPx: Int,
    val scrollRow: Int,
    val rowHeightPx: Float = DEFAULT_FLAME_ROW_HEIGHT_PX,
    val overscanRows: Int = DEFAULT_FLAME_OVERSCAN_ROWS,
    /** Absolute physical-pixel offset; null preserves the integer scrollRow contract. */
    val scrollOffsetPx: Float? = null,
) {
    init {
        require(rowHeightPx.isFinite() && rowHeightPx > 0f) { "rowHeightPx must be finite and positive" }
        require(overscanRows >= 0) { "overscanRows must not be negative" }
        require(scrollOffsetPx == null || scrollOffsetPx.isFinite()) { "scrollOffsetPx must be finite" }
    }
}

data class VisibleFlameNode(
    val nodeIndex: Int,
    val nodeId: FlameCallNodeId,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

class VisibleFlameLayout(
    nodes: List<VisibleFlameNode>,
    val materializedRowRange: IntRange,
) {
    val nodes: List<VisibleFlameNode> = Collections.unmodifiableList(ArrayList(nodes))

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is VisibleFlameLayout &&
            nodes == other.nodes &&
            materializedRowRange == other.materializedRowRange

    override fun hashCode(): Int = 31 * nodes.hashCode() + materializedRowRange.hashCode()

    override fun toString(): String = "VisibleFlameLayout(nodes=$nodes, materializedRowRange=$materializedRowRange)"
}

object FlameGraphLayout {
    fun layout(
        snapshot: FlameGraphSnapshot,
        viewport: FlameViewport,
    ): VisibleFlameLayout = layout(SnapshotFlameGraphLayoutSource(snapshot), viewport)

    internal fun layout(
        source: FlameGraphLayoutSource,
        viewport: FlameViewport,
    ): VisibleFlameLayout {
        val materializedRows = materializedRows(source.rowCount, viewport)
        val canvasWidth = viewport.widthPx.coerceAtLeast(0)
        val nodes =
            if (materializedRows.isEmpty() || canvasWidth == 0) {
                emptyList()
            } else {
                projectVisibleNodes(source, materializedRows, viewport)
            }
        return VisibleFlameLayout(nodes, materializedRows)
    }

    fun hitTest(
        layout: VisibleFlameLayout,
        x: Float,
        y: Float,
    ): VisibleFlameNode? {
        if (!x.isFinite() || !y.isFinite()) return null
        return layout.nodes.lastOrNull { node ->
            x >= node.x && x < node.x + node.width && y >= node.y && y < node.y + node.height
        }
    }

    fun scrollRowToReveal(
        snapshot: FlameGraphSnapshot,
        selectedNodeId: FlameCallNodeId,
        viewport: FlameViewport,
    ): Int {
        val rowCount = snapshot.rows.rowCount
        val visibleCount = visibleRowCount(rowCount, viewport.heightPx, viewport.rowHeightPx)
        return if (visibleCount == 0) {
            0
        } else {
            val maximumScrollRow = (rowCount - visibleCount).coerceAtLeast(0)
            val currentScrollRow = viewport.scrollRow.coerceIn(0, maximumScrollRow)
            val selectedRow =
                snapshot.callNodes.indexOf(selectedNodeId)?.let(snapshot.callNodes::depthAt)
            when {
                selectedRow == null -> currentScrollRow
                selectedRow < currentScrollRow -> selectedRow.coerceIn(0, maximumScrollRow)
                selectedRow >= currentScrollRow + visibleCount ->
                    (selectedRow - visibleCount + 1).coerceIn(0, maximumScrollRow)
                else -> currentScrollRow
            }
        }
    }

    fun clampScrollRow(
        snapshot: FlameGraphSnapshot,
        viewport: FlameViewport,
    ): Int {
        val maximumScrollRow = maximumScrollRow(snapshot.rows.rowCount, viewport)
        return viewport.scrollRow.coerceIn(0, maximumScrollRow)
    }

    fun clampScrollOffset(
        snapshot: FlameGraphSnapshot,
        viewport: FlameViewport,
    ): Float = scrollOffset(snapshot.rows.rowCount, viewport).toFloat()

    fun scrollOffsetToReveal(
        snapshot: FlameGraphSnapshot,
        selectedNodeId: FlameCallNodeId,
        viewport: FlameViewport,
    ): Float {
        val offset = scrollOffset(snapshot.rows.rowCount, viewport)
        val depth =
            snapshot.callNodes.indexOf(selectedNodeId)?.let(snapshot.callNodes::depthAt) ?: return offset.toFloat()
        val top = depth * viewport.rowHeightPx.toDouble()
        val bottom = top + viewport.rowHeightPx
        val next =
            when {
                top < offset -> top
                bottom > offset + viewport.heightPx -> bottom - viewport.heightPx
                else -> offset
            }
        return next.coerceIn(0.0, maximumScrollOffset(snapshot.rows.rowCount, viewport)).toFloat()
    }
}

private fun maximumScrollOffset(
    rowCount: Int,
    viewport: FlameViewport,
): Double = (rowCount * viewport.rowHeightPx.toDouble() - viewport.heightPx.coerceAtLeast(0)).coerceAtLeast(0.0)

private fun scrollOffset(
    rowCount: Int,
    viewport: FlameViewport,
): Double =
    viewport.scrollOffsetPx?.toDouble()?.coerceIn(0.0, maximumScrollOffset(rowCount, viewport))
        ?: (viewport.scrollRow.coerceIn(0, maximumScrollRow(rowCount, viewport)) * viewport.rowHeightPx.toDouble())

private fun maximumScrollRow(
    rowCount: Int,
    viewport: FlameViewport,
): Int = (rowCount - visibleRowCount(rowCount, viewport.heightPx, viewport.rowHeightPx)).coerceAtLeast(0)

private fun materializedRows(
    rowCount: Int,
    viewport: FlameViewport,
): IntRange {
    val visibleCount = visibleRowCount(rowCount, viewport.heightPx, viewport.rowHeightPx)
    if (visibleCount == 0) return IntRange.EMPTY
    val offset = scrollOffset(rowCount, viewport)
    val firstVisible =
        if (viewport.scrollOffsetPx == null) {
            viewport.scrollRow.coerceIn(0, maximumScrollRow(rowCount, viewport))
        } else {
            floor(offset / viewport.rowHeightPx).toInt()
        }
    val lastVisible =
        if (viewport.scrollOffsetPx == null) {
            firstVisible + visibleCount - 1
        } else {
            (ceil((offset + viewport.heightPx) / viewport.rowHeightPx) - 1.0)
                .coerceAtMost(rowCount.toDouble() - 1.0)
                .toInt()
        }
    val firstMaterialized = (firstVisible.toLong() - viewport.overscanRows).coerceAtLeast(0).toInt()
    val lastMaterialized =
        (lastVisible.toLong() + viewport.overscanRows)
            .coerceAtMost(rowCount.toLong() - 1)
            .toInt()
    return firstMaterialized..lastMaterialized
}

private fun projectVisibleNodes(
    source: FlameGraphLayoutSource,
    materializedRows: IntRange,
    viewport: FlameViewport,
): List<VisibleFlameNode> {
    val canvasHeight = viewport.heightPx.coerceAtLeast(0)
    val offset = scrollOffset(source.rowCount, viewport)
    val nodes = ArrayList<VisibleFlameNode>()
    materializedRows.forEach { rowIndex ->
        val top = rowTop(rowIndex, offset, canvasHeight, viewport.rowHeightPx, source.startsAtBottom)
        val y = snapFirefoxDevicePixel(top).toFloat()
        val bottom = snapFirefoxDevicePixel(top + viewport.rowHeightPx).toFloat()
        val height = (bottom - y).coerceAtLeast(0f)
        source.nodeIndexesAt(rowIndex)?.forEach { nodeIndex ->
            projectNode(source, nodeIndex, y, height, viewport)?.let(nodes::add)
        }
    }
    return nodes
}

private fun visibleRowCount(
    rowCount: Int,
    heightPx: Int,
    rowHeightPx: Float,
): Int {
    if (rowCount == 0 || heightPx <= 0) return 0
    return ceil(heightPx.toDouble() / rowHeightPx.toDouble())
        .coerceAtMost(rowCount.toDouble())
        .toInt()
}

private fun rowTop(
    rowIndex: Int,
    scrollOffsetPx: Double,
    canvasHeight: Int,
    rowHeightPx: Float,
    startsAtBottom: Boolean,
): Double {
    val rowOffsetPx = rowIndex * rowHeightPx.toDouble() - scrollOffsetPx
    return if (startsAtBottom) {
        canvasHeight.toDouble() - rowOffsetPx - rowHeightPx
    } else {
        rowOffsetPx
    }
}

internal interface FlameGraphLayoutSource {
    val rowCount: Int
    val startsAtBottom: Boolean

    fun nodeIndexesAt(rowIndex: Int): IntArray?

    fun normalizedStartAt(nodeIndex: Int): Double?

    fun normalizedEndAt(nodeIndex: Int): Double?

    fun nodeIdAt(nodeIndex: Int): FlameCallNodeId?
}

private class SnapshotFlameGraphLayoutSource(
    private val snapshot: FlameGraphSnapshot,
) : FlameGraphLayoutSource {
    override val rowCount: Int get() = snapshot.rows.rowCount
    override val startsAtBottom: Boolean get() = snapshot.rows.startsAtBottom

    override fun nodeIndexesAt(rowIndex: Int): IntArray? = snapshot.rows.nodeIndexesAt(rowIndex)

    override fun normalizedStartAt(nodeIndex: Int): Double? = snapshot.rows.startAt(nodeIndex)

    override fun normalizedEndAt(nodeIndex: Int): Double? = snapshot.rows.endAt(nodeIndex)

    override fun nodeIdAt(nodeIndex: Int): FlameCallNodeId? = snapshot.callNodes.nodeIdAt(nodeIndex)
}

private fun projectNode(
    source: FlameGraphLayoutSource,
    nodeIndex: Int,
    y: Float,
    height: Float,
    viewport: FlameViewport,
): VisibleFlameNode? {
    val node = source.compactNodeAt(nodeIndex)
    return if (node == null || !node.normalizedStart.isFinite() || !node.normalizedEnd.isFinite()) {
        null
    } else {
        projectFiniteNode(nodeIndex, node, y, height, viewport)
    }
}

private data class CompactFlameNode(
    val nodeId: FlameCallNodeId,
    val normalizedStart: Double,
    val normalizedEnd: Double,
)

private fun FlameGraphLayoutSource.compactNodeAt(nodeIndex: Int): CompactFlameNode? {
    val normalizedStart = normalizedStartAt(nodeIndex)
    val normalizedEnd = normalizedEndAt(nodeIndex)
    val nodeId = nodeIdAt(nodeIndex)
    return if (normalizedStart == null || normalizedEnd == null || nodeId == null) {
        null
    } else {
        CompactFlameNode(nodeId, normalizedStart, normalizedEnd)
    }
}

private fun projectFiniteNode(
    nodeIndex: Int,
    node: CompactFlameNode,
    y: Float,
    height: Float,
    viewport: FlameViewport,
): VisibleFlameNode? {
    val start = node.normalizedStart.coerceIn(0.0, 1.0)
    val end = node.normalizedEnd.coerceIn(0.0, 1.0)
    val canvasWidth = viewport.widthPx.coerceAtLeast(0)
    val geometry = firefoxHorizontalGeometry(start, end, canvasWidth)
    return if (geometry == null) {
        null
    } else {
        val visibleLeft = geometry.leftPx.coerceAtLeast(0f)
        val visibleRight = geometry.rightPx.coerceAtMost(canvasWidth.toFloat())
        if (visibleRight <= visibleLeft) return null
        VisibleFlameNode(
            nodeIndex = nodeIndex,
            nodeId = node.nodeId,
            x = visibleLeft,
            y = y,
            width = visibleRight - visibleLeft,
            height = height,
        )
    }
}

internal data class FirefoxHorizontalGeometry(
    val leftPx: Float,
    val rightPx: Float,
) {
    val widthPx: Float
        get() = rightPx - leftPx
}

internal fun firefoxHorizontalGeometry(
    normalizedStart: Double,
    normalizedEnd: Double,
    canvasWidthPx: Int,
): FirefoxHorizontalGeometry? {
    if (!normalizedStart.isFinite() || !normalizedEnd.isFinite() || canvasWidthPx <= 0) return null
    val start = normalizedStart.coerceIn(0.0, 1.0)
    val end = normalizedEnd.coerceIn(0.0, 1.0)
    return if (end <= start) {
        null
    } else {
        val left = snapToDevicePixelMultiple(start * canvasWidthPx, FIREFOX_EDGE_SNAP_MULTIPLE_PX)
        val right =
            snapToDevicePixelMultiple(end * canvasWidthPx, FIREFOX_EDGE_SNAP_MULTIPLE_PX) -
                FIREFOX_TRANSLUCENT_GAP_PX
        FirefoxHorizontalGeometry(left.toFloat(), right.toFloat()).takeIf { it.widthPx > 0f }
    }
}

internal fun snapFirefoxDevicePixel(value: Double): Int = floor(value + SNAP_HALF).toInt()

private fun snapToDevicePixelMultiple(
    value: Double,
    multiple: Int,
): Double = snapFirefoxDevicePixel(value / multiple) * multiple.toDouble()

private const val DEFAULT_FLAME_ROW_HEIGHT_PX = 16f
private const val DEFAULT_FLAME_OVERSCAN_ROWS = 1
private const val FIREFOX_EDGE_SNAP_MULTIPLE_PX = 2
private const val FIREFOX_TRANSLUCENT_GAP_PX = 0.8
private const val SNAP_HALF = 0.5
