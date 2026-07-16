package com.androidperformancestudio.profileanalysis

import java.util.Locale

data class CallTreeProjectionResult(
    val callNodes: CallNodeTable,
    val inputStackCount: Int,
    val projectedStackCount: Int,
    val incompleteStackCount: Int,
    val failureDetail: String?,
)

object CallTreeProjector {
    fun project(
        table: CallStackTable,
        direction: CallStackDirection = CallStackDirection.FORWARD,
    ): CallNodeTable =
        project(
            table = table,
            direction = direction,
            primaryHashStep = StableCallNodeId::primaryHashStep,
            idDeriver = StableCallNodeId::derive,
        )

    @Suppress("TooGenericExceptionCaught")
    fun projectResult(
        table: CallStackTable,
        direction: CallStackDirection = CallStackDirection.FORWARD,
    ): CallTreeProjectionResult {
        val incompleteStackCount = table.stacks.count { stack -> stack.frameIdsRootToLeaf.isEmpty() }
        return try {
            val callNodes = project(table, direction)
            CallTreeProjectionResult(
                callNodes = callNodes,
                inputStackCount = table.stacks.size,
                projectedStackCount = table.stacks.size - incompleteStackCount,
                incompleteStackCount = incompleteStackCount,
                failureDetail = null,
            )
        } catch (failure: RuntimeException) {
            CallTreeProjectionResult(
                callNodes = EMPTY_CALL_NODES,
                inputStackCount = table.stacks.size,
                projectedStackCount = 0,
                incompleteStackCount = incompleteStackCount,
                failureDetail = "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}",
            )
        }
    }

    internal fun project(
        table: CallStackTable,
        direction: CallStackDirection,
        primaryHashStep: (Long, FlameFunctionId) -> Long,
        idDeriver: (Long, Long) -> Long = StableCallNodeId::derive,
    ): CallNodeTable {
        table.stacks.firstOrNull { stack -> stack.weight < 0 }?.let { invalid ->
            throw IllegalArgumentException(
                "Negative call-stack weight is unsupported: sampleId=${invalid.sampleId}, weight=${invalid.weight}",
            )
        }
        val canonicalFrames = canonicalReferencedFrames(table)
        val roots = LinkedHashMap<FlameFunctionId, MutableCallNode>()
        table.stacks.forEach { stack ->
            addStack(table, stack, direction, canonicalFrames, roots, primaryHashStep, idDeriver)
        }
        return flatten(roots.values, canonicalFrames)
    }
}

private val EMPTY_CALL_NODES =
    CallNodeTable(
        ids = LongArray(0),
        parentIndexes = IntArray(0),
        frameIds = LongArray(0),
        depths = IntArray(0),
        inclusiveWeights = LongArray(0),
        selfWeights = LongArray(0),
        sampleCounts = LongArray(0),
        threadCounts = IntArray(0),
        categories = emptyList(),
        framesById = emptyMap(),
    )

private class MutableCallNode(
    val functionId: FlameFunctionId,
    val parent: MutableCallNode?,
    val primaryHash: Long,
    val secondaryHash: Long,
    val stableId: FlameCallNodeId,
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
        inclusiveWeight = StableCallNodeId.saturatingNonNegativeAdd(inclusiveWeight, weight)
        sampleCount = StableCallNodeId.saturatingNonNegativeAdd(sampleCount, 1)
        threads += threadKey
        categoryWeights[category] = StableCallNodeId.saturatingNonNegativeAdd(categoryWeights[category] ?: 0, weight)
    }
}

private data class PendingNode(
    val node: MutableCallNode,
    val parentIndex: Int,
    val depth: Int,
)

private fun canonicalReferencedFrames(table: CallStackTable): Map<FlameFunctionId, CallStackFrame> {
    val referencedFrames = LinkedHashMap<Long, CallStackFrame>()
    table.stacks.forEach { stack ->
        stack.frameIdsRootToLeaf.forEach { frameId ->
            referencedFrames.putIfAbsent(frameId, table.frame(frameId))
        }
    }
    return canonicalFramesByFunction(referencedFrames.values)
}

@Suppress("LongParameterList")
private fun addStack(
    table: CallStackTable,
    stack: WeightedCallStack,
    direction: CallStackDirection,
    canonicalFrames: Map<FlameFunctionId, CallStackFrame>,
    roots: MutableMap<FlameFunctionId, MutableCallNode>,
    primaryHashStep: (Long, FlameFunctionId) -> Long,
    idDeriver: (Long, Long) -> Long,
) {
    if (stack.frameIdsRootToLeaf.isEmpty()) return
    val indexes =
        when (direction) {
            CallStackDirection.FORWARD -> stack.frameIdsRootToLeaf.indices
            CallStackDirection.INVERTED -> stack.frameIdsRootToLeaf.indices.reversed()
        }
    var parent: MutableCallNode? = null
    var siblings = roots
    var terminal: MutableCallNode? = null
    indexes.forEach { sourceIndex ->
        val frame = table.frame(stack.frameIdsRootToLeaf[sourceIndex])
        check(canonicalFrames.containsKey(frame.functionId))
        val node =
            siblings.getOrPut(frame.functionId) {
                createNode(frame.functionId, parent, primaryHashStep, idDeriver)
            }
        node.record(stack.weight, stack.threadKey, stack.categoriesRootToLeaf[sourceIndex])
        terminal = node
        parent = node
        siblings = node.children
    }
    terminal?.let { node ->
        node.selfWeight = StableCallNodeId.saturatingNonNegativeAdd(node.selfWeight, stack.weight)
    }
}

private fun createNode(
    functionId: FlameFunctionId,
    parent: MutableCallNode?,
    primaryHashStep: (Long, FlameFunctionId) -> Long,
    idDeriver: (Long, Long) -> Long,
): MutableCallNode {
    val primaryHash = primaryHashStep(parent?.primaryHash ?: PRIMARY_HASH_OFFSET, functionId)
    val secondaryHash = StableCallNodeId.secondaryHashStep(parent?.secondaryHash, functionId)
    return MutableCallNode(
        functionId = functionId,
        parent = parent,
        primaryHash = primaryHash,
        secondaryHash = secondaryHash,
        stableId = FlameCallNodeId(idDeriver(primaryHash, secondaryHash)),
    )
}

@Suppress("LongMethod")
private fun flatten(
    roots: Collection<MutableCallNode>,
    canonicalFrames: Map<FlameFunctionId, CallStackFrame>,
): CallNodeTable {
    val nodeComparator = callNodeComparator(canonicalFrames)
    val orderedRoots = roots.sortedWith(nodeComparator)
    val nodeCount = countNodes(orderedRoots)
    val ids = LongArray(nodeCount)
    val parentIndexes = IntArray(nodeCount)
    val frameIds = LongArray(nodeCount)
    val depths = IntArray(nodeCount)
    val inclusiveWeights = LongArray(nodeCount)
    val selfWeights = LongArray(nodeCount)
    val sampleCounts = LongArray(nodeCount)
    val threadCounts = IntArray(nodeCount)
    val categories = ArrayList<String?>(nodeCount)
    val projectedFrames = LinkedHashMap<Long, CallStackFrame>()
    val nodesByStableId = HashMap<Long, MutableCallNode>(nodeCount)
    val pending = ArrayDeque<PendingNode>()
    orderedRoots.asReversed().forEach { root -> pending.addLast(PendingNode(root, -1, 0)) }

    var index = 0
    while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        val node = current.node
        checkStableIdCollision(nodesByStableId, node)
        val canonicalFrame = canonicalFrames.getValue(node.functionId)
        ids[index] = node.stableId.value
        parentIndexes[index] = current.parentIndex
        frameIds[index] = canonicalFrame.frameId
        depths[index] = current.depth
        inclusiveWeights[index] = node.inclusiveWeight
        selfWeights[index] = node.selfWeight
        sampleCounts[index] = node.sampleCount
        threadCounts[index] = node.threads.size
        categories += dominantCategory(node.categoryWeights)
        projectedFrames.putIfAbsent(canonicalFrame.frameId, canonicalFrame)

        val parentIndex = index
        index += 1
        node.orderedChildren(nodeComparator).asReversed().forEach { child ->
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
        framesById = projectedFrames,
    )
}

private fun checkStableIdCollision(
    nodesByStableId: MutableMap<Long, MutableCallNode>,
    node: MutableCallNode,
) {
    val previous = nodesByStableId.putIfAbsent(node.stableId.value, node)
    check(previous == null || previous === node) {
        "Stable call-node ID collision for distinct ordered function paths: id=${node.stableId.value}"
    }
}

private fun MutableCallNode.orderedChildren(comparator: Comparator<MutableCallNode>): List<MutableCallNode> =
    if (children.size <= 1) children.values.toList() else children.values.sortedWith(comparator)

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
                .thenBy { entry -> if (entry.key == null) 0 else 1 }
                .thenBy { entry -> entry.key?.lowercase(Locale.ROOT).orEmpty() }
                .thenBy { entry -> entry.key.orEmpty() },
        ).firstOrNull()
        ?.key

private const val PRIMARY_HASH_OFFSET = -3750763034362895579L
