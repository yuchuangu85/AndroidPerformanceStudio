package com.androidperformancestudio.profileanalysis

@JvmInline
value class FlameFunctionId(
    val value: Long,
)

@JvmInline
value class FlameCallNodeId(
    val value: Long,
)

data class CallNodePath(
    val functions: List<FlameFunctionId>,
)

data class AnalysisTimeRange(
    val startNanosInclusive: Long,
    val endNanosExclusive: Long,
)

enum class CallStackDirection {
    FORWARD,
    INVERTED,
}

enum class FrameImplementation {
    NATIVE,
    MANAGED,
    KERNEL,
    UNKNOWN,
}

enum class ImplementationFilter {
    ALL,
    NATIVE,
    MANAGED,
    KERNEL,
    UNKNOWN,
}

data class CallStackFrame(
    val frameId: Long,
    val functionId: FlameFunctionId,
    val symbolName: String,
    val resource: String,
    val virtualAddress: Long,
    val implementation: FrameImplementation,
)

data class WeightedCallStack(
    val sampleId: Long,
    val timestampNanos: Long,
    val weight: Long,
    val threadKey: String,
    val category: String?,
    val subcategory: String?,
    val frameIdsRootToLeaf: List<Long>,
)

data class CallStackTable(
    val framesById: Map<Long, CallStackFrame>,
    val stacks: List<WeightedCallStack>,
) {
    fun frame(frameId: Long): CallStackFrame = checkNotNull(framesById[frameId])
}

sealed interface CallStackTransform {
    data class FocusCallNode(
        val path: CallNodePath,
    ) : CallStackTransform

    data class FocusFunction(
        val function: FlameFunctionId,
    ) : CallStackTransform

    data class FocusFunctionSelf(
        val function: FlameFunctionId,
    ) : CallStackTransform

    data class MergeCallNode(
        val path: CallNodePath,
    ) : CallStackTransform

    data class MergeFunction(
        val function: FlameFunctionId,
    ) : CallStackTransform

    data class DropFunction(
        val function: FlameFunctionId,
    ) : CallStackTransform

    data class CollapseResource(
        val resource: String,
    ) : CallStackTransform

    data class CollapseRecursion(
        val function: FlameFunctionId,
    ) : CallStackTransform

    data class CollapseDirectRecursion(
        val function: FlameFunctionId,
    ) : CallStackTransform

    data class CollapseFunctionSubtree(
        val function: FlameFunctionId,
    ) : CallStackTransform

    data class FocusCategory(
        val category: String,
    ) : CallStackTransform
}

data class CallStackAnalysisQuery(
    val previewRange: AnalysisTimeRange? = null,
    val searchText: String = "",
    val implementation: ImplementationFilter = ImplementationFilter.ALL,
    val direction: CallStackDirection = CallStackDirection.FORWARD,
    val transforms: List<CallStackTransform> = emptyList(),
)

@Suppress("LongParameterList")
class CallNodeTable(
    val ids: LongArray,
    val parentIndexes: IntArray,
    val frameIds: LongArray,
    val depths: IntArray,
    val inclusiveWeights: LongArray,
    val selfWeights: LongArray,
    val sampleCounts: LongArray,
    val threadCounts: IntArray,
    val categories: List<String?>,
    val framesById: Map<Long, CallStackFrame>,
    private val idsByPath: Map<CallNodePath, FlameCallNodeId> = emptyMap(),
) {
    val size: Int get() = ids.size

    fun findByPath(path: CallNodePath): FlameCallNodeId? = idsByPath[path]
}

class FlameGraphRows(
    val nodeIndexesByRow: List<IntArray>,
    val starts: DoubleArray,
    val ends: DoubleArray,
    val startsAtBottom: Boolean,
)

enum class FlameGraphEmptyReason {
    THREAD_HAS_NO_SAMPLES,
    COMMITTED_RANGE_EMPTY,
    PREVIEW_RANGE_EMPTY,
    SEARCH_FILTERED_ALL,
    IMPLEMENTATION_FILTERED_ALL,
    TRANSFORMS_FILTERED_ALL,
    PROFILE_INCOMPLETE,
    PROJECTION_FAILED,
}

data class FlameGraphSnapshot(
    val query: CallStackAnalysisQuery,
    val callNodes: CallNodeTable,
    val rows: FlameGraphRows,
    val totalWeight: Long,
    val emptyReason: FlameGraphEmptyReason?,
    val invalidTransforms: List<CallStackTransform>,
)

fun parseFlameSearchTerms(searchText: String): List<String> =
    searchText
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
