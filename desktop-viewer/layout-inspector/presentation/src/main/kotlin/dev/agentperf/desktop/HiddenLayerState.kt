package dev.agentperf.desktop

internal data class HiddenLayerState(
    val hiddenNodeIds: Set<String> = emptySet(),
) {
    val count: Int get() = hiddenNodeIds.size

    fun isHidden(nodeId: String?): Boolean = nodeId != null && nodeId in hiddenNodeIds

    fun toggle(nodeId: String): HiddenLayerState =
        if (nodeId in hiddenNodeIds) {
            copy(hiddenNodeIds = hiddenNodeIds - nodeId)
        } else {
            copy(hiddenNodeIds = hiddenNodeIds + nodeId)
        }

    fun clear(): HiddenLayerState = HiddenLayerState()

    fun sanitize(rows: List<TreeRowModel>): HiddenLayerState {
        val rowIds = rows.mapTo(mutableSetOf(), TreeRowModel::id)
        return copy(hiddenNodeIds = hiddenNodeIds intersect rowIds)
    }
}
