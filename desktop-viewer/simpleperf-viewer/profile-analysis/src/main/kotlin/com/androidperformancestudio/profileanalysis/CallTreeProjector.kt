package com.androidperformancestudio.profileanalysis

import java.util.Locale

object CallTreeProjector {
    fun project(
        table: CallStackTable,
        direction: CallStackDirection = CallStackDirection.FORWARD,
    ): CallNodeTable = project(table, direction, ::stablePathHash)

    internal fun project(
        table: CallStackTable,
        direction: CallStackDirection,
        pathHash: (CallNodePath) -> Long,
    ): CallNodeTable {
        val canonicalFrames = canonicalFramesByFunction(table.framesById.values)
        val roots = LinkedHashMap<FlameFunctionId, MutableCallNode>()
        table.stacks.forEach { stack ->
            addStack(table, stack, direction, canonicalFrames, roots)
        }
        return flatten(roots.values, canonicalFrames, table.framesById, pathHash)
    }
}

private class MutableCallNode(
    val functionId: FlameFunctionId,
    val path: CallNodePath,
) {
    val children = LinkedHashMap<FlameFunctionId, MutableCallNode>()
    val threads = HashSet<String>()
    val categoryWeights = HashMap<String?, Long>()
    var inclusiveWeight: Long = 0
    var selfWeight: Long = 0
    var sampleCount: Long = 0

    fun record(
        weight: Long,
        threadKey: String,
        category: String?,
    ) {
        inclusiveWeight = saturatingAdd(inclusiveWeight, weight)
        sampleCount = saturatingAdd(sampleCount, 1)
        threads += threadKey
        categoryWeights[category] = saturatingAdd(categoryWeights[category] ?: 0, weight)
    }
}

private data class PendingNode(
    val node: MutableCallNode,
    val parentIndex: Int,
    val depth: Int,
)

private fun addStack(
    table: CallStackTable,
    stack: WeightedCallStack,
    direction: CallStackDirection,
    canonicalFrames: Map<FlameFunctionId, CallStackFrame>,
    roots: MutableMap<FlameFunctionId, MutableCallNode>,
) {
    if (stack.frameIdsRootToLeaf.isEmpty()) return
    val indexes =
        when (direction) {
            CallStackDirection.FORWARD -> stack.frameIdsRootToLeaf.indices
            CallStackDirection.INVERTED -> stack.frameIdsRootToLeaf.indices.reversed()
        }
    val effectiveWeight = stack.weight.coerceAtLeast(0)
    var siblings = roots
    val functions = ArrayList<FlameFunctionId>(stack.frameIdsRootToLeaf.size)
    var terminal: MutableCallNode? = null
    indexes.forEach { sourceIndex ->
        val frame = table.frame(stack.frameIdsRootToLeaf[sourceIndex])
        check(canonicalFrames.containsKey(frame.functionId))
        functions += frame.functionId
        val path = CallNodePath(functions)
        val node = siblings.getOrPut(frame.functionId) { MutableCallNode(frame.functionId, path) }
        node.record(effectiveWeight, stack.threadKey, stack.categoriesRootToLeaf[sourceIndex])
        terminal = node
        siblings = node.children
    }
    terminal?.let { node -> node.selfWeight = saturatingAdd(node.selfWeight, effectiveWeight) }
}

@Suppress("LongMethod")
private fun flatten(
    roots: Collection<MutableCallNode>,
    canonicalFrames: Map<FlameFunctionId, CallStackFrame>,
    framesById: Map<Long, CallStackFrame>,
    pathHash: (CallNodePath) -> Long,
): CallNodeTable {
    val nodeComparator = callNodeComparator(canonicalFrames)
    val orderedRoots = roots.sortedWith(nodeComparator)
    val nodeCount = countNodes(orderedRoots)
    val stableIds = assignStableIds(orderedRoots, pathHash)
    val ids = LongArray(nodeCount)
    val parentIndexes = IntArray(nodeCount)
    val frameIds = LongArray(nodeCount)
    val depths = IntArray(nodeCount)
    val inclusiveWeights = LongArray(nodeCount)
    val selfWeights = LongArray(nodeCount)
    val sampleCounts = LongArray(nodeCount)
    val threadCounts = IntArray(nodeCount)
    val categories = ArrayList<String?>(nodeCount)
    val idsByPath = LinkedHashMap<CallNodePath, FlameCallNodeId>(nodeCount)
    val pending = ArrayDeque<PendingNode>()
    orderedRoots.asReversed().forEach { root -> pending.addLast(PendingNode(root, -1, 0)) }

    var index = 0
    while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        val node = current.node
        val stableId = stableIds.getValue(node.path)
        ids[index] = stableId.value
        parentIndexes[index] = current.parentIndex
        frameIds[index] = canonicalFrames.getValue(node.functionId).frameId
        depths[index] = current.depth
        inclusiveWeights[index] = node.inclusiveWeight
        selfWeights[index] = node.selfWeight
        sampleCounts[index] = node.sampleCount
        threadCounts[index] = node.threads.size
        categories += dominantCategory(node.categoryWeights)
        idsByPath[node.path] = stableId

        val parentIndex = index
        index += 1
        node.children.values.sortedWith(nodeComparator).asReversed().forEach { child ->
            pending.addLast(PendingNode(child, parentIndex, current.depth + 1))
        }
    }

    return CallNodeTable(
        ids = ids,
        parentIndexes = parentIndexes,
        frameIds = frameIds,
        depths = depths,
        inclusiveWeights = inclusiveWeights,
        selfWeights = selfWeights,
        sampleCounts = sampleCounts,
        threadCounts = threadCounts,
        categories = categories,
        framesById = framesById,
        idsByPath = idsByPath,
    )
}

private fun countNodes(roots: Collection<MutableCallNode>): Int {
    var count = 0
    val pending = ArrayDeque<MutableCallNode>()
    roots.forEach(pending::addLast)
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        count = Math.addExact(count, 1)
        node.children.values.forEach(pending::addLast)
    }
    return count
}

private fun assignStableIds(
    roots: Collection<MutableCallNode>,
    pathHash: (CallNodePath) -> Long,
): Map<CallNodePath, FlameCallNodeId> {
    val allPaths = ArrayList<CallNodePath>()
    val pending = ArrayDeque<MutableCallNode>()
    roots.forEach(pending::addLast)
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        allPaths += node.path
        node.children.values.forEach(pending::addLast)
    }
    allPaths.sortWith(callNodePathComparator)

    val occupied = HashMap<Long, CallNodePath>(allPaths.size)
    val ids = LinkedHashMap<CallNodePath, FlameCallNodeId>(allPaths.size)
    allPaths.forEach { path ->
        var candidate = pathHash(path)
        while (occupied.containsKey(candidate)) {
            candidate = if (candidate == Long.MAX_VALUE) Long.MIN_VALUE else candidate + 1
        }
        occupied[candidate] = path
        ids[path] = FlameCallNodeId(candidate)
    }
    return ids
}

private val callNodePathComparator =
    Comparator<CallNodePath> { left, right ->
        val commonSize = minOf(left.functions.size, right.functions.size)
        for (index in 0 until commonSize) {
            val comparison = left.functions[index].value.compareTo(right.functions[index].value)
            if (comparison != 0) return@Comparator comparison
        }
        left.functions.size.compareTo(right.functions.size)
    }

private fun canonicalFramesByFunction(frames: Collection<CallStackFrame>): Map<FlameFunctionId, CallStackFrame> =
    frames
        .groupBy(CallStackFrame::functionId)
        .mapValues { (_, candidates) -> candidates.minWith(frameComparator) }

private fun callNodeComparator(frames: Map<FlameFunctionId, CallStackFrame>): Comparator<MutableCallNode> =
    Comparator { left, right ->
        compareValuesBy(
            left,
            right,
            { frames.getValue(it.functionId).symbolName.lowercase(Locale.ROOT) },
            { it.functionId.value },
            { frames.getValue(it.functionId).symbolName },
            { frames.getValue(it.functionId).resource },
            { frames.getValue(it.functionId).virtualAddress },
            { frames.getValue(it.functionId).implementation.ordinal },
            { frames.getValue(it.functionId).frameId },
        )
    }

private val frameComparator =
    compareBy<CallStackFrame>(
        { it.symbolName.lowercase(Locale.ROOT) },
        CallStackFrame::symbolName,
        CallStackFrame::resource,
        { it.implementation.ordinal },
        CallStackFrame::virtualAddress,
        CallStackFrame::frameId,
    )

private fun dominantCategory(weights: Map<String?, Long>): String? =
    weights.entries
        .sortedWith(
            compareByDescending<Map.Entry<String?, Long>>(Map.Entry<String?, Long>::value)
                .thenBy { entry -> entry.key ?: "" },
        ).firstOrNull()
        ?.key

private fun stablePathHash(path: CallNodePath): Long {
    var hash = FNV_64_OFFSET_BASIS
    path.functions.forEach { function ->
        var value = function.value
        repeat(Long.SIZE_BYTES) {
            hash = hash xor (value and BYTE_MASK)
            hash *= FNV_64_PRIME
            value = value ushr Byte.SIZE_BITS
        }
    }
    return hash
}

private fun saturatingAdd(
    left: Long,
    right: Long,
): Long =
    try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        if (right >= 0) Long.MAX_VALUE else Long.MIN_VALUE
    }

private const val FNV_64_OFFSET_BASIS = -3750763034362895579L
private const val FNV_64_PRIME = 1099511628211L
private const val BYTE_MASK = 0xffL
