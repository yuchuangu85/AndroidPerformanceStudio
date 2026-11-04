package com.androidperformancestudio.profileanalysis

object StackChartProjector {
    fun project(
        source: CallStackTable,
        query: CallStackAnalysisQuery,
        viewportEndNanosExclusive: Long,
    ): StackChartSnapshot {
        val preview = CallStackFilter.apply(source, CallStackAnalysisQuery(previewRange = query.previewRange))
        val transformed = CallStackTransformer.apply(preview.table, query.transforms)
        val filtered = CallStackFilter.apply(transformed.table, query.copy(previewRange = null))
        val visible = filtered.table
        val nodes = CallTreeProjector.project(visible, query.direction)
        val callNodes = StackChartCallNodes(nodes)
        val committedQuery = query.copy(previewRange = null, direction = CallStackDirection.FORWARD)
        val committedTransformed =
            if (query.previewRange == null) transformed else CallStackTransformer.apply(source, query.transforms)
        val committedFiltered =
            if (query.previewRange == null) {
                filtered
            } else {
                CallStackFilter.apply(committedTransformed.table, committedQuery)
            }
        val committedNodes =
            if (query == committedQuery) {
                nodes
            } else {
                CallTreeProjector.project(committedFiltered.table, CallStackDirection.FORWARD)
            }
        val orderedSource = source.stacks.sortedWith(stackChartSampleOrder)
        val orderedStacks =
            if (visible === source) orderedSource else visible.stacks.sortedWith(stackChartSampleOrder)
        val viewportEnd =
            minOf(viewportEndNanosExclusive, query.previewRange?.endNanosExclusive ?: viewportEndNanosExclusive)
        val blocks =
            projectBlocks(orderedStacks, orderedSource, visible.framesById, query.direction, viewportEnd, callNodes)
        val emptyReason = emptyReason(source, preview, blocks)

        return StackChartSnapshot(
            framesById = visible.framesById,
            blocks = blocks,
            startNanos = blocks.firstOrNull()?.startNanos,
            endNanosExclusive = blocks.firstOrNull()?.let { viewportEnd },
            maxDepth = blocks.maxOfOrNull(StackChartBlock::depth) ?: 0,
            emptyReason = emptyReason,
            parentCallNodeIds = callNodes.parents,
            committedAnalysis =
                committedAnalysis(source, committedQuery, committedTransformed, committedFiltered, committedNodes),
        )
    }
}

private fun committedAnalysis(
    source: CallStackTable,
    query: CallStackAnalysisQuery,
    transformed: TransformResult,
    filtered: FilteredCallStacks,
    nodes: CallNodeTable,
): FlameGraphSnapshot {
    var totalWeight = 0L
    repeat(nodes.size) { index ->
        if (nodes.parentIndexAt(index) == -1) {
            totalWeight =
                StableCallNodeId.saturatingNonNegativeAdd(totalWeight, checkNotNull(nodes.inclusiveWeightAt(index)))
        }
    }
    val stages =
        FlameGraphStageCounts(
            sourceStackCount = source.stacks.size,
            selectedThreadHasNoSamples = source.stacks.isEmpty(),
            committedRangeExcludedSamples = false,
            afterPreviewCount = source.stacks.size,
            afterSearchCount = filtered.afterSearchCount,
            afterImplementationCount = filtered.afterImplementationCount,
            afterTransformCount = transformed.outputStackCount,
            incompleteStackCount = filtered.table.stacks.count { it.frameIdsRootToLeaf.isEmpty() },
            projectedNodeCount = nodes.size,
            projectionFailure = null,
        )
    return FlameGraphSnapshot(
        query = query,
        callNodes = nodes,
        rows = FlameGraphRowProjector.project(nodes, CallStackDirection.FORWARD),
        totalWeight = totalWeight,
        emptyReason = stages.emptyReason(),
        invalidTransforms = transformed.invalidTransforms,
        weightType = source.weightType,
    )
}

private val stackChartSampleOrder =
    compareBy<WeightedCallStack>(WeightedCallStack::timestampNanos).thenBy(WeightedCallStack::sampleId)

private data class StackChartCallNodeKey(
    val parentId: FlameCallNodeId?,
    val functionId: FlameFunctionId,
)

private class StackChartCallNodes(
    private val nodes: CallNodeTable,
) {
    val parents = LinkedHashMap<FlameCallNodeId, FlameCallNodeId?>()
    private val idsByParentAndFunction = HashMap<StackChartCallNodeKey, FlameCallNodeId>()

    init {
        repeat(nodes.size) { index ->
            val nodeId = checkNotNull(nodes.nodeIdAt(index))
            val parentId = nodes.parentIndexAt(index)?.let(nodes::nodeIdAt)
            val functionId = checkNotNull(nodes.frameAt(index)).functionId
            parents[nodeId] = parentId
            idsByParentAndFunction[StackChartCallNodeKey(parentId, functionId)] = nodeId
        }
    }

    fun childId(
        parentId: FlameCallNodeId?,
        functionId: FlameFunctionId,
    ): FlameCallNodeId = idsByParentAndFunction.getValue(StackChartCallNodeKey(parentId, functionId))

    fun categoryFor(nodeId: FlameCallNodeId): String? = nodes.indexOf(nodeId)?.let(nodes::categoryAt)
}

private data class StackChartLane(
    val threadKey: String,
    val depth: Int,
)

@Suppress("LongParameterList")
private fun projectBlocks(
    stacks: List<WeightedCallStack>,
    orderedSource: List<WeightedCallStack>,
    framesById: Map<Long, CallStackFrame>,
    direction: CallStackDirection,
    viewportEndNanosExclusive: Long,
    callNodes: StackChartCallNodes,
): List<StackChartBlock> {
    val blocks = ArrayList<StackChartBlock>()
    val lastBlockIndexByLane = HashMap<StackChartLane, Int>()
    var sourceIndex = 0
    for (stack in stacks) {
        if (stack.timestampNanos >= viewportEndNanosExclusive) break
        // Read timing from the unfiltered sequence so excluded samples remain gaps.
        while (sourceIndex < orderedSource.size && !orderedSource[sourceIndex].sameSample(stack)) sourceIndex += 1
        check(sourceIndex < orderedSource.size) { "Transformed stack has no source sample: ${stack.sampleId}" }
        val endNanosExclusive =
            minOf(
                orderedSource.getOrNull(sourceIndex + 1)?.timestampNanos ?: viewportEndNanosExclusive,
                viewportEndNanosExclusive,
            )
        sourceIndex += 1
        var parentId: FlameCallNodeId? = null
        stack.visibleFrameIds(direction).forEachIndexed { depth, frameId ->
            val nodeId = callNodes.childId(parentId, framesById.getValue(frameId).functionId)
            parentId = nodeId
            val next = stack.toBlock(frameId, depth, endNanosExclusive, nodeId, callNodes.categoryFor(nodeId))
            val lane = StackChartLane(stack.threadKey, depth)
            val previousIndex = lastBlockIndexByLane[lane]
            val previous = previousIndex?.let(blocks::get)
            if (previous != null && previous.canCoalesce(next)) {
                blocks[previousIndex] =
                    previous.copy(
                        endNanosExclusive = next.endNanosExclusive,
                        weight = StableCallNodeId.saturatingNonNegativeAdd(previous.weight, next.weight),
                    )
            } else {
                lastBlockIndexByLane[lane] = blocks.size
                blocks += next
            }
        }
    }
    return blocks
}

private fun WeightedCallStack.sameSample(other: WeightedCallStack): Boolean =
    sampleId == other.sampleId && timestampNanos == other.timestampNanos && threadKey == other.threadKey

private fun WeightedCallStack.visibleFrameIds(direction: CallStackDirection): List<Long> =
    when (direction) {
        CallStackDirection.FORWARD -> frameIdsRootToLeaf
        CallStackDirection.INVERTED -> frameIdsRootToLeaf.asReversed()
    }

private fun WeightedCallStack.toBlock(
    frameId: Long,
    depth: Int,
    endNanosExclusive: Long,
    callNodeId: FlameCallNodeId,
    category: String?,
): StackChartBlock =
    StackChartBlock(
        id = StackChartBlockId("$threadKey:$depth:$frameId:$timestampNanos:$sampleId"),
        sampleId = sampleId,
        startNanos = timestampNanos,
        endNanosExclusive = endNanosExclusive,
        depth = depth,
        frameId = frameId,
        threadKey = threadKey,
        weight = weight,
        callNodeId = callNodeId,
        category = category,
    )

private fun StackChartBlock.canCoalesce(next: StackChartBlock): Boolean =
    callNodeId != null &&
        callNodeId == next.callNodeId &&
        threadKey == next.threadKey &&
        depth == next.depth &&
        endNanosExclusive == next.startNanos

private fun emptyReason(
    source: CallStackTable,
    filtered: FilteredCallStacks,
    blocks: List<StackChartBlock>,
): StackChartEmptyReason? =
    when {
        source.stacks.isEmpty() -> StackChartEmptyReason.NO_SAMPLES
        filtered.afterPreviewCount == 0 -> StackChartEmptyReason.RANGE_EMPTY
        blocks.isEmpty() -> StackChartEmptyReason.FILTERED_ALL
        else -> null
    }
