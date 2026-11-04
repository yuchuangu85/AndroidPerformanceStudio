package com.androidperformancestudio.profileanalysis

enum class FlameGraphNavigationCommand {
    PARENT,
    WIDEST_CHILD,
    PREVIOUS_SIBLING,
    NEXT_SIBLING,
}

object FlameGraphNavigator {
    fun target(
        snapshot: FlameGraphSnapshot,
        nodeId: FlameCallNodeId,
        command: FlameGraphNavigationCommand,
    ): FlameCallNodeId? =
        when (command) {
            FlameGraphNavigationCommand.PARENT -> parent(snapshot, nodeId)
            FlameGraphNavigationCommand.WIDEST_CHILD -> widestChild(snapshot, nodeId)
            FlameGraphNavigationCommand.PREVIOUS_SIBLING -> previousSibling(snapshot, nodeId)
            FlameGraphNavigationCommand.NEXT_SIBLING -> nextSibling(snapshot, nodeId)
        }

    fun parent(
        snapshot: FlameGraphSnapshot,
        nodeId: FlameCallNodeId,
    ): FlameCallNodeId? {
        val parentIndex = snapshot.callNodes.indexOf(nodeId)?.let(snapshot.callNodes::parentIndexAt)
        return parentIndex?.let(snapshot.callNodes::nodeIdAt)
    }

    fun widestChild(
        snapshot: FlameGraphSnapshot,
        nodeId: FlameCallNodeId,
        minimumNormalizedWidth: Double = MINIMUM_SELECTABLE_NORMALIZED_WIDTH,
    ): FlameCallNodeId? {
        validateThreshold(minimumNormalizedWidth)
        val nodeIndex = snapshot.callNodes.indexOf(nodeId) ?: return null
        var widestIndex: Int? = null
        var widestWidth = Double.NEGATIVE_INFINITY
        var candidateIndex = snapshot.callNodes.firstChildIndexAt(nodeIndex)
        while (candidateIndex != null) {
            val candidateWidth = snapshot.eligibleWidth(candidateIndex, minimumNormalizedWidth)
            if (candidateWidth != null && candidateWidth > widestWidth) {
                widestIndex = candidateIndex
                widestWidth = candidateWidth
            }
            candidateIndex = snapshot.callNodes.nextSiblingIndexAt(candidateIndex)
        }
        return widestIndex?.let(snapshot.callNodes::nodeIdAt)
    }

    fun previousSibling(
        snapshot: FlameGraphSnapshot,
        nodeId: FlameCallNodeId,
        minimumNormalizedWidth: Double = MINIMUM_SELECTABLE_NORMALIZED_WIDTH,
    ): FlameCallNodeId? = adjacentInRow(snapshot, nodeId, minimumNormalizedWidth, searchForward = false)

    fun nextSibling(
        snapshot: FlameGraphSnapshot,
        nodeId: FlameCallNodeId,
        minimumNormalizedWidth: Double = MINIMUM_SELECTABLE_NORMALIZED_WIDTH,
    ): FlameCallNodeId? = adjacentInRow(snapshot, nodeId, minimumNormalizedWidth, searchForward = true)
}

@Suppress("ReturnCount")
private fun adjacentInRow(
    snapshot: FlameGraphSnapshot,
    nodeId: FlameCallNodeId,
    minimumNormalizedWidth: Double,
    searchForward: Boolean,
): FlameCallNodeId? {
    validateThreshold(minimumNormalizedWidth)
    val nodeIndex = snapshot.callNodes.indexOf(nodeId) ?: return null
    val depth = snapshot.callNodes.depthAt(nodeIndex) ?: return null
    val row = snapshot.rows.nodeIndexesAt(depth) ?: return null
    val columnIndex = row.indexOf(nodeIndex).takeIf { it >= 0 } ?: return null
    val step = if (searchForward) 1 else -1
    var candidateColumn = columnIndex + step
    while (candidateColumn in row.indices) {
        snapshot.eligibleNodeId(row[candidateColumn], minimumNormalizedWidth)?.let { return it }
        candidateColumn += step
    }
    return null
}

private fun FlameGraphSnapshot.eligibleNodeId(
    nodeIndex: Int,
    minimumNormalizedWidth: Double,
): FlameCallNodeId? =
    if (eligibleWidth(nodeIndex, minimumNormalizedWidth) == null) {
        null
    } else {
        callNodes.nodeIdAt(nodeIndex)
    }

private fun FlameGraphSnapshot.eligibleWidth(
    nodeIndex: Int,
    minimumNormalizedWidth: Double,
): Double? = rows.normalizedWidthAt(nodeIndex)?.takeIf { width -> width > minimumNormalizedWidth }

private fun validateThreshold(minimumNormalizedWidth: Double) {
    require(minimumNormalizedWidth.isFinite() && minimumNormalizedWidth >= 0.0) {
        "minimumNormalizedWidth must be finite and non-negative"
    }
}

const val MINIMUM_SELECTABLE_NORMALIZED_WIDTH: Double = 0.001
