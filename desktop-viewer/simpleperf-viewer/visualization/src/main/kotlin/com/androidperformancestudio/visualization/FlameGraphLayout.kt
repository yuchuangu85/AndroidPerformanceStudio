package com.androidperformancestudio.visualization

import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import java.util.Collections
import kotlin.math.ceil
import kotlin.math.roundToInt

data class FlameViewport(
    val widthPx: Int,
    val heightPx: Int,
    val scrollRow: Int,
    val rowHeightPx: Float = DEFAULT_FLAME_ROW_HEIGHT_PX,
    val overscanRows: Int = DEFAULT_FLAME_OVERSCAN_ROWS,
) {
    init {
        require(rowHeightPx.isFinite() && rowHeightPx > 0f) { "rowHeightPx must be finite and positive" }
        require(overscanRows >= 0) { "overscanRows must not be negative" }
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
}

private fun materializedRows(
    rowCount: Int,
    viewport: FlameViewport,
): IntRange {
    val visibleCount = visibleRowCount(rowCount, viewport.heightPx, viewport.rowHeightPx)
    if (visibleCount == 0) return IntRange.EMPTY
    val firstVisible = firstVisibleRow(rowCount, visibleCount, viewport.scrollRow)
    val lastVisible = firstVisible + visibleCount - 1
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
    val visibleRowCount = visibleRowCount(source.rowCount, canvasHeight, viewport.rowHeightPx)
    val firstVisibleRow = firstVisibleRow(source.rowCount, visibleRowCount, viewport.scrollRow)
    val nodes = ArrayList<VisibleFlameNode>()
    materializedRows.forEach { rowIndex ->
        val y = rowY(rowIndex, firstVisibleRow, canvasHeight, viewport.rowHeightPx, source.startsAtBottom)
        source.nodeIndexesAt(rowIndex)?.forEach { nodeIndex ->
            projectNode(source, nodeIndex, y, viewport)?.let(nodes::add)
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

private fun firstVisibleRow(
    rowCount: Int,
    visibleRowCount: Int,
    requestedScrollRow: Int,
): Int = requestedScrollRow.coerceIn(0, (rowCount - visibleRowCount).coerceAtLeast(0))

private fun rowY(
    rowIndex: Int,
    firstVisibleRow: Int,
    canvasHeight: Int,
    rowHeightPx: Float,
    startsAtBottom: Boolean,
): Float {
    val rowOffset = rowIndex.toDouble() - firstVisibleRow.toDouble()
    val y =
        if (startsAtBottom) {
            canvasHeight.toDouble() - (rowOffset + 1.0) * rowHeightPx
        } else {
            rowOffset * rowHeightPx
        }
    return if (y.isFinite()) {
        y.coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat()
    } else {
        if (y < 0.0) -Float.MAX_VALUE else Float.MAX_VALUE
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
    viewport: FlameViewport,
): VisibleFlameNode? {
    val node = source.compactNodeAt(nodeIndex)
    return if (node == null || !node.normalizedStart.isFinite() || !node.normalizedEnd.isFinite()) {
        null
    } else {
        projectFiniteNode(nodeIndex, node, y, viewport)
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
    viewport: FlameViewport,
): VisibleFlameNode? {
    val start = node.normalizedStart.coerceIn(0.0, 1.0)
    val end = node.normalizedEnd.coerceIn(0.0, 1.0)
    val canvasWidth = viewport.widthPx.coerceAtLeast(0)
    val rawWidth = (end - start) * canvasWidth
    val snappedStart = (start * canvasWidth).roundToInt()
    val snappedEnd = (end * canvasWidth).roundToInt()
    return if (rawWidth < MINIMUM_DRAWABLE_WIDTH_PX || snappedEnd <= snappedStart) {
        null
    } else {
        VisibleFlameNode(
            nodeIndex = nodeIndex,
            nodeId = node.nodeId,
            x = snappedStart.toFloat(),
            y = y,
            width = (snappedEnd - snappedStart).toFloat(),
            height = viewport.rowHeightPx,
        )
    }
}

private const val DEFAULT_FLAME_ROW_HEIGHT_PX = 16f
private const val DEFAULT_FLAME_OVERSCAN_ROWS = 1
private const val MINIMUM_DRAWABLE_WIDTH_PX = 1.0
