package com.androidperformancestudio.presentation

import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.profileanalysis.parseFlameSearchTerms
import kotlin.math.roundToLong

/**
 * Compile bridge for the pre-Task-9 canvas. Delete this adapter when the native Firefox-style panel
 * consumes [FlameGraphSnapshot] rows directly in Task 9.
 */
internal data class LegacyPresentationFlameNode(
    val id: Long,
    val parentId: Long?,
    val depth: Int,
    val symbolName: String,
    val filePath: String,
    val path: List<String>,
    val startWeight: Long,
    val endWeightExclusive: Long,
    val inclusiveWeight: Long,
    val exclusiveWeight: Long,
    val highlighted: Boolean,
)

internal fun FlameGraphSnapshot.toLegacyNodes(selectedNodeId: FlameCallNodeId?): List<LegacyPresentationFlameNode> {
    if (totalWeight <= 0L || callNodes.size == 0) return emptyList()
    val ids = callNodes.ids
    val parents = callNodes.parentIndexes
    val frameIds = callNodes.frameIds
    val depths = callNodes.depths
    val inclusive = callNodes.inclusiveWeights
    val exclusive = callNodes.selfWeights
    val starts = rows.starts
    val ends = rows.ends
    val frames = callNodes.framesById
    val searchTerms = parseFlameSearchTerms(query.searchText)
    val names = frameIds.map { frameId -> frames.getValue(frameId).symbolName }

    return ids.indices.mapNotNull { index ->
        val start = (starts[index] * totalWeight).roundToLong().coerceIn(0L, totalWeight)
        val end = (ends[index] * totalWeight).roundToLong().coerceIn(0L, totalWeight)
        if (end <= start) return@mapNotNull null
        val frame = frames.getValue(frameIds[index])
        val parentIndex = parents[index]
        val path = nodePath(index, parents, names)
        LegacyPresentationFlameNode(
            id = ids[index],
            parentId = parentIndex.takeIf { it >= 0 }?.let(ids::get),
            depth = depths[index],
            symbolName = frame.symbolName,
            filePath = frame.resource,
            path = path,
            startWeight = start,
            endWeightExclusive = end,
            inclusiveWeight = inclusive[index],
            exclusiveWeight = exclusive[index],
            highlighted =
                selectedNodeId?.value == ids[index] ||
                    searchTerms.any { term ->
                        frame.symbolName.contains(term, ignoreCase = true) ||
                            frame.resource.contains(term, ignoreCase = true)
                    },
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
