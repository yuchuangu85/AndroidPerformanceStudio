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
)

enum class StackChartEmptyReason {
    NO_SAMPLES,
    RANGE_EMPTY,
    FILTERED_ALL,
}

@ConsistentCopyVisibility
data class StackChartSnapshot private constructor(
    val framesById: Map<Long, CallStackFrame>,
    val blocks: List<StackChartBlock>,
    val startNanos: Long?,
    val endNanosExclusive: Long?,
    val maxDepth: Int,
    val emptyReason: StackChartEmptyReason?,
    private val immutableSnapshot: Boolean,
) {
    constructor(
        framesById: Map<Long, CallStackFrame>,
        blocks: List<StackChartBlock>,
        startNanos: Long?,
        endNanosExclusive: Long?,
        maxDepth: Int,
        emptyReason: StackChartEmptyReason?,
    ) : this(
        immutableStackChartMap(framesById),
        immutableStackChartList(blocks),
        startNanos,
        endNanosExclusive,
        maxDepth,
        emptyReason,
        true,
    )

    @Suppress("LongParameterList")
    fun copy(
        framesById: Map<Long, CallStackFrame> = this.framesById,
        blocks: List<StackChartBlock> = this.blocks,
        startNanos: Long? = this.startNanos,
        endNanosExclusive: Long? = this.endNanosExclusive,
        maxDepth: Int = this.maxDepth,
        emptyReason: StackChartEmptyReason? = this.emptyReason,
    ): StackChartSnapshot = StackChartSnapshot(framesById, blocks, startNanos, endNanosExclusive, maxDepth, emptyReason)

    override fun toString(): String =
        "StackChartSnapshot(framesById=$framesById, blocks=$blocks, startNanos=$startNanos, " +
            "endNanosExclusive=$endNanosExclusive, maxDepth=$maxDepth, emptyReason=$emptyReason)"
}

@Suppress("MaxLineLength")
private fun <T> immutableStackChartList(source: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(source))

@Suppress("MaxLineLength")
private fun <K, V> immutableStackChartMap(source: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(source))
