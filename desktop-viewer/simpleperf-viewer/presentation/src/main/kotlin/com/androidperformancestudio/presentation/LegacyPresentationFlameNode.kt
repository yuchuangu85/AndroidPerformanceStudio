package com.androidperformancestudio.presentation

import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot

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

internal fun FlameGraphSnapshot.toLegacyNodes(): List<LegacyPresentationFlameNode> {
    if (callNodes.size == 0) return emptyList()
    val ids = callNodes.ids
    val parents = callNodes.parentIndexes
    val frameIds = callNodes.frameIds
    val inclusive = callNodes.inclusiveWeights
    val exclusive = callNodes.selfWeights
    val frames = callNodes.framesById
    val names = frameIds.map { frameId -> frames.getValue(frameId).symbolName }

    return ids.indices.map { index ->
        val frame = frames.getValue(frameIds[index])
        val path = nodePath(index, parents, names)
        LegacyPresentationFlameNode(
            id = ids[index],
            symbolName = frame.symbolName,
            filePath = frame.resource,
            path = path,
            inclusiveWeight = inclusive[index],
            exclusiveWeight = exclusive[index],
        )
    }
}

private fun nodePath(
    nodeIndex: Int,
    parentIndexes: IntArray,
    names: List<String>,
): List<String> {
    val reversed = ArrayList<String>()
    var current = nodeIndex
    while (current >= 0) {
        reversed += names[current]
        current = parentIndexes[current]
    }
    reversed.reverse()
    return reversed
}
