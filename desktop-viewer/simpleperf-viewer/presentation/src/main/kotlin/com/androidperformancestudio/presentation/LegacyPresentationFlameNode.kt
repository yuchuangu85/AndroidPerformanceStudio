package com.androidperformancestudio.presentation

import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import java.util.Collections

/**
 * Compile bridge for the pre-Task-9 canvas. Delete this adapter when the native Firefox-style panel
 * consumes [FlameGraphSnapshot] rows directly in Task 9.
 */
internal data class LegacyPresentationFlameNode(
    val id: Long,
    val symbolName: String,
    val filePath: String,
    val path: List<String>,
    val inclusiveWeight: Long,
    val exclusiveWeight: Long,
)

internal fun FlameGraphSnapshot.resolveLegacyNode(nodeId: FlameCallNodeId): LegacyPresentationFlameNode? {
    val nodeIndex = callNodes.indexOf(nodeId)
    return if (nodeIndex == null) null else resolveLegacyNodeAt(nodeId, nodeIndex)
}

private fun FlameGraphSnapshot.resolveLegacyNodeAt(
    nodeId: FlameCallNodeId,
    nodeIndex: Int,
): LegacyPresentationFlameNode? {
    val frame = callNodes.frameAt(nodeIndex)
    val path = nodePath(nodeIndex)
    val inclusiveWeight = callNodes.inclusiveWeightAt(nodeIndex)
    val exclusiveWeight = callNodes.selfWeightAt(nodeIndex)
    return if (frame == null || path == null) {
        null
    } else if (inclusiveWeight == null || exclusiveWeight == null) {
        null
    } else {
        LegacyPresentationFlameNode(
            id = nodeId.value,
            symbolName = frame.symbolName,
            filePath = frame.resource,
            path = path,
            inclusiveWeight = inclusiveWeight,
            exclusiveWeight = exclusiveWeight,
        )
    }
}

private fun FlameGraphSnapshot.nodePath(nodeIndex: Int): List<String>? {
    val reversed = ArrayList<String>()
    var current = nodeIndex
    var traversed = 0
    var valid = true
    while (current >= 0 && traversed++ < callNodes.size) {
        val frame = callNodes.frameAt(current)
        val parent = callNodes.parentIndexAt(current)
        if (frame == null || parent == null) {
            valid = false
            break
        }
        reversed += frame.symbolName
        current = parent
    }
    reversed.reverse()
    return if (valid && current < 0) Collections.unmodifiableList(reversed) else null
}
