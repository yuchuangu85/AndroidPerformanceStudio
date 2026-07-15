package com.androidperformancestudio.profileanalysis

object FlameGraphNavigator {
    fun parent(
        snapshot: FlameGraphSnapshot,
        nodeId: FlameCallNodeId,
        minimumNormalizedWidth: Double = MINIMUM_SELECTABLE_NORMALIZED_WIDTH,
    ): FlameCallNodeId? {
        validateThreshold(minimumNormalizedWidth)
        val parentIndex = snapshot.callNodes.indexOf(nodeId)?.let(snapshot.callNodes::parentIndexAt)
        return parentIndex?.let { index -> snapshot.eligibleNodeId(index, minimumNormalizedWidth) }
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
    ): FlameCallNodeId? = sibling(snapshot, nodeId, minimumNormalizedWidth, searchForward = false)

    fun nextSibling(
        snapshot: FlameGraphSnapshot,
        nodeId: FlameCallNodeId,
        minimumNormalizedWidth: Double = MINIMUM_SELECTABLE_NORMALIZED_WIDTH,
    ): FlameCallNodeId? = sibling(snapshot, nodeId, minimumNormalizedWidth, searchForward = true)
}

private fun sibling(
    snapshot: FlameGraphSnapshot,
    nodeId: FlameCallNodeId,
    minimumNormalizedWidth: Double,
    searchForward: Boolean,
): FlameCallNodeId? {
    validateThreshold(minimumNormalizedWidth)
    val nodeIndex = snapshot.callNodes.indexOf(nodeId)
    val parentIndex = nodeIndex?.let(snapshot.callNodes::parentIndexAt)
    return if (nodeIndex == null || parentIndex == null) {
        null
    } else {
        var candidateIndex = snapshot.callNodes.siblingIndexAt(nodeIndex, searchForward)
        var eligibleNodeId: FlameCallNodeId? = null
        while (candidateIndex != null && eligibleNodeId == null) {
            eligibleNodeId = snapshot.eligibleNodeId(candidateIndex, minimumNormalizedWidth)
            candidateIndex = snapshot.callNodes.siblingIndexAt(candidateIndex, searchForward)
        }
        eligibleNodeId
    }
}

private fun CallNodeTable.siblingIndexAt(
    nodeIndex: Int,
    searchForward: Boolean,
): Int? = if (searchForward) nextSiblingIndexAt(nodeIndex) else previousSiblingIndexAt(nodeIndex)

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
): Double? = rows.normalizedWidthAt(nodeIndex)?.takeIf { width -> width >= minimumNormalizedWidth }

private fun validateThreshold(minimumNormalizedWidth: Double) {
    require(minimumNormalizedWidth.isFinite() && minimumNormalizedWidth >= 0.0) {
        "minimumNormalizedWidth must be finite and non-negative"
    }
}

const val MINIMUM_SELECTABLE_NORMALIZED_WIDTH: Double = 0.001
