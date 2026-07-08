package dev.agentperf.application

import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.LayoutSnapshot
import dev.agentperf.protocol.UiNode
import dev.agentperf.protocol.effectiveWindows

data class TimelineDiff(
    val previousCapturedAtEpochMillis: Long,
    val currentCapturedAtEpochMillis: Long,
    val addedNodes: Int,
    val removedNodes: Int,
    val boundsChangedNodes: Int,
)

fun diffSnapshots(
    previous: LayoutSnapshot,
    current: LayoutSnapshot,
): TimelineDiff {
    val previousNodes = previous.flattenNodesByKey()
    val currentNodes = current.flattenNodesByKey()
    val previousKeys = previousNodes.keys
    val currentKeys = currentNodes.keys
    val sharedKeys = previousKeys intersect currentKeys
    return TimelineDiff(
        previousCapturedAtEpochMillis = previous.capturedAtEpochMillis,
        currentCapturedAtEpochMillis = current.capturedAtEpochMillis,
        addedNodes = (currentKeys - previousKeys).size,
        removedNodes = (previousKeys - currentKeys).size,
        boundsChangedNodes = sharedKeys.count { key -> previousNodes.getValue(key) != currentNodes.getValue(key) },
    )
}

private fun LayoutSnapshot.flattenNodesByKey(): Map<String, Bounds> = buildMap {
    effectiveWindows.forEach { window ->
        window.root.appendBounds(window.id, this)
    }
}

private fun UiNode.appendBounds(
    windowId: String,
    target: MutableMap<String, Bounds>,
) {
    target["$windowId:$id"] = bounds
    children.forEach { child -> child.appendBounds(windowId, target) }
}
