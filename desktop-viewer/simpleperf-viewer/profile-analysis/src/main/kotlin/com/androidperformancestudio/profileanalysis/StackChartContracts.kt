package com.androidperformancestudio.profileanalysis

import java.util.Collections

@JvmInline
value class StackChartBlockId(
    val value: String,
)

@Suppress("LongParameterList")
data class StackChartBlock(
    val id: StackChartBlockId,
    val sampleId: Long,
    val startNanos: Long,
    val endNanosExclusive: Long,
    val depth: Int,
    val frameId: Long,
    val threadKey: String,
    val weight: Long,
    val callNodeId: FlameCallNodeId? = null,
    val category: String? = null,
)

enum class StackChartEmptyReason {
    NO_SAMPLES,
    RANGE_EMPTY,
    FILTERED_ALL,
}

@ConsistentCopyVisibility
@Suppress("LongParameterList")
data class StackChartSnapshot private constructor(
    val framesById: Map<Long, CallStackFrame>,
    val blocks: List<StackChartBlock>,
    val startNanos: Long?,
    val endNanosExclusive: Long?,
    val maxDepth: Int,
    val emptyReason: StackChartEmptyReason?,
    /** Shared parent links for projected call nodes; roots map to null. */
    val parentCallNodeIds: Map<FlameCallNodeId, FlameCallNodeId?>,
    val committedAnalysis: FlameGraphSnapshot?,
    private val immutableSnapshot: Boolean,
) {
    val blocksById: Map<StackChartBlockId, StackChartBlock> =
        immutableStackChartMap(blocks.associateBy(StackChartBlock::id))
    val blocksByCallNodeId: Map<FlameCallNodeId, List<StackChartBlock>> = indexBlocksByCallNode(blocks)

    @Suppress("LongParameterList")
    constructor(
        framesById: Map<Long, CallStackFrame>,
        blocks: List<StackChartBlock>,
        startNanos: Long?,
        endNanosExclusive: Long?,
        maxDepth: Int,
        emptyReason: StackChartEmptyReason?,
        parentCallNodeIds: Map<FlameCallNodeId, FlameCallNodeId?> = emptyMap(),
        committedAnalysis: FlameGraphSnapshot? = null,
    ) : this(
        immutableStackChartMap(framesById),
        immutableStackChartList(blocks),
        startNanos,
        endNanosExclusive,
        maxDepth,
        emptyReason,
        immutableStackChartMap(parentCallNodeIds),
        committedAnalysis,
        true,
    )

    fun blockById(id: StackChartBlockId): StackChartBlock? = blocksById[id]

    @Suppress("LongParameterList")
    fun copy(
        framesById: Map<Long, CallStackFrame> = this.framesById,
        blocks: List<StackChartBlock> = this.blocks,
        startNanos: Long? = this.startNanos,
        endNanosExclusive: Long? = this.endNanosExclusive,
        maxDepth: Int = this.maxDepth,
        emptyReason: StackChartEmptyReason? = this.emptyReason,
        parentCallNodeIds: Map<FlameCallNodeId, FlameCallNodeId?> = this.parentCallNodeIds,
        committedAnalysis: FlameGraphSnapshot? = this.committedAnalysis,
    ): StackChartSnapshot =
        StackChartSnapshot(
            framesById,
            blocks,
            startNanos,
            endNanosExclusive,
            maxDepth,
            emptyReason,
            parentCallNodeIds,
            committedAnalysis,
        )

    override fun toString(): String =
        "StackChartSnapshot(framesById=$framesById, blocks=$blocks, startNanos=$startNanos, " +
            "endNanosExclusive=$endNanosExclusive, maxDepth=$maxDepth, emptyReason=$emptyReason, " +
            "parentCallNodeIds=$parentCallNodeIds, committedAnalysis=$committedAnalysis)"
}

private fun indexBlocksByCallNode(blocks: List<StackChartBlock>): Map<FlameCallNodeId, List<StackChartBlock>> {
    val grouped = LinkedHashMap<FlameCallNodeId, MutableList<StackChartBlock>>()
    blocks.forEach { block ->
        block.callNodeId?.let { nodeId -> grouped.getOrPut(nodeId) { ArrayList() }.add(block) }
    }
    return immutableStackChartMap(grouped.mapValues { (_, matches) -> immutableStackChartList(matches) })
}

@Suppress("MaxLineLength")
private fun <T> immutableStackChartList(source: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(source))

@Suppress("MaxLineLength")
private fun <K, V> immutableStackChartMap(source: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(source))
