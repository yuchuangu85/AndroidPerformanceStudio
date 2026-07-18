package com.androidperformancestudio.profileanalysis

object StackChartProjector {
    fun project(
        source: CallStackTable,
        query: CallStackAnalysisQuery,
        viewportEndNanosExclusive: Long,
    ): StackChartSnapshot {
        val filtered = CallStackFilter.apply(source, query)
        val transformed = CallStackTransformer.apply(filtered.table, query.transforms).table
        val orderedStacks =
            transformed.stacks.sortedWith(
                compareBy<WeightedCallStack>(WeightedCallStack::timestampNanos).thenBy(WeightedCallStack::sampleId),
            )
        val blocks = projectBlocks(orderedStacks, query.direction, viewportEndNanosExclusive)
        val emptyReason = emptyReason(source, filtered, blocks)

        return StackChartSnapshot(
            framesById = transformed.framesById,
            blocks = blocks,
            startNanos = blocks.firstOrNull()?.startNanos,
            endNanosExclusive = blocks.firstOrNull()?.let { viewportEndNanosExclusive },
            maxDepth = blocks.maxOfOrNull(StackChartBlock::depth) ?: 0,
            emptyReason = emptyReason,
        )
    }
}

private data class StackChartLane(
    val threadKey: String,
    val depth: Int,
)

private fun projectBlocks(
    stacks: List<WeightedCallStack>,
    direction: CallStackDirection,
    viewportEndNanosExclusive: Long,
): List<StackChartBlock> {
    val blocks = ArrayList<StackChartBlock>()
    val lastBlockIndexByLane = HashMap<StackChartLane, Int>()
    stacks.forEachIndexed { stackIndex, stack ->
        val endNanosExclusive =
            stacks.getOrNull(stackIndex + 1)?.timestampNanos ?: viewportEndNanosExclusive
        stack.visibleFrameIds(direction).forEachIndexed { depth, frameId ->
            val next = stack.toBlock(frameId, depth, endNanosExclusive)
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

private fun WeightedCallStack.visibleFrameIds(direction: CallStackDirection): List<Long> =
    when (direction) {
        CallStackDirection.FORWARD -> frameIdsRootToLeaf
        CallStackDirection.INVERTED -> frameIdsRootToLeaf.asReversed()
    }

private fun WeightedCallStack.toBlock(
    frameId: Long,
    depth: Int,
    endNanosExclusive: Long,
): StackChartBlock =
    StackChartBlock(
        id = StackChartBlockId("$threadKey:$depth:$frameId:$timestampNanos"),
        sampleId = sampleId,
        startNanos = timestampNanos,
        endNanosExclusive = endNanosExclusive,
        depth = depth,
        frameId = frameId,
        threadKey = threadKey,
        weight = weight,
    )

private fun StackChartBlock.canCoalesce(next: StackChartBlock): Boolean =
    frameId == next.frameId &&
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
