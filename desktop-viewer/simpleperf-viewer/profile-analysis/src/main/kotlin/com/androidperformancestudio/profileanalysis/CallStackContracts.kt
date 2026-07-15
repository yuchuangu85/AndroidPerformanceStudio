package com.androidperformancestudio.profileanalysis

import java.util.Collections

@JvmInline
value class FlameFunctionId(
    val value: Long,
)

@JvmInline
value class FlameCallNodeId(
    val value: Long,
)

@ConsistentCopyVisibility
data class CallNodePath private constructor(
    val functions: List<FlameFunctionId>,
    private val immutableSnapshot: Boolean,
) {
    constructor(functions: List<FlameFunctionId>) : this(immutableList(functions), true)

    fun copy(functions: List<FlameFunctionId> = this.functions): CallNodePath = CallNodePath(functions)

    override fun toString(): String = "CallNodePath(functions=$functions)"
}

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
    val collapsedResource: String? = null,
)

@ConsistentCopyVisibility
@Suppress("LongParameterList")
data class WeightedCallStack private constructor(
    val sampleId: Long,
    val timestampNanos: Long,
    val weight: Long,
    val threadKey: String,
    val category: String?,
    val subcategory: String?,
    val frameIdsRootToLeaf: List<Long>,
    val categoriesRootToLeaf: List<String?>,
    private val immutableSnapshot: Boolean,
) {
    constructor(
        sampleId: Long,
        timestampNanos: Long,
        weight: Long,
        threadKey: String,
        category: String?,
        subcategory: String?,
        frameIdsRootToLeaf: List<Long>,
        categoriesRootToLeaf: List<String?>? = null,
    ) : this(
        sampleId,
        timestampNanos,
        weight,
        threadKey,
        category,
        subcategory,
        immutableList(frameIdsRootToLeaf),
        immutableCategories(frameIdsRootToLeaf, categoriesRootToLeaf, category),
        true,
    )

    fun copy(
        sampleId: Long = this.sampleId,
        timestampNanos: Long = this.timestampNanos,
        weight: Long = this.weight,
        threadKey: String = this.threadKey,
        category: String? = this.category,
        subcategory: String? = this.subcategory,
        frameIdsRootToLeaf: List<Long> = this.frameIdsRootToLeaf,
        categoriesRootToLeaf: List<String?>? =
            if (frameIdsRootToLeaf == this.frameIdsRootToLeaf) this.categoriesRootToLeaf else null,
    ): WeightedCallStack =
        WeightedCallStack(
            sampleId,
            timestampNanos,
            weight,
            threadKey,
            category,
            subcategory,
            frameIdsRootToLeaf,
            categoriesRootToLeaf,
        )

    override fun toString(): String =
        "WeightedCallStack(sampleId=$sampleId, timestampNanos=$timestampNanos, weight=$weight, " +
            "threadKey=$threadKey, category=$category, subcategory=$subcategory, " +
            "frameIdsRootToLeaf=$frameIdsRootToLeaf, categoriesRootToLeaf=$categoriesRootToLeaf)"
}

@ConsistentCopyVisibility
data class CallStackTable private constructor(
    val framesById: Map<Long, CallStackFrame>,
    val stacks: List<WeightedCallStack>,
    private val immutableSnapshot: Boolean,
) {
    constructor(
        framesById: Map<Long, CallStackFrame>,
        stacks: List<WeightedCallStack>,
    ) : this(immutableMap(framesById), immutableList(stacks), true)

    fun frame(frameId: Long): CallStackFrame = checkNotNull(framesById[frameId])

    fun copy(
        framesById: Map<Long, CallStackFrame> = this.framesById,
        stacks: List<WeightedCallStack> = this.stacks,
    ): CallStackTable = CallStackTable(framesById, stacks)

    override fun toString(): String = "CallStackTable(framesById=$framesById, stacks=$stacks)"
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

@ConsistentCopyVisibility
data class CallStackAnalysisQuery private constructor(
    val previewRange: AnalysisTimeRange? = null,
    val searchText: String = "",
    val implementation: ImplementationFilter = ImplementationFilter.ALL,
    val direction: CallStackDirection = CallStackDirection.FORWARD,
    val transforms: List<CallStackTransform> = emptyList(),
    private val immutableSnapshot: Boolean,
) {
    constructor(
        previewRange: AnalysisTimeRange? = null,
        searchText: String = "",
        implementation: ImplementationFilter = ImplementationFilter.ALL,
        direction: CallStackDirection = CallStackDirection.FORWARD,
        transforms: List<CallStackTransform> = emptyList(),
    ) : this(previewRange, searchText, implementation, direction, immutableList(transforms), true)

    fun copy(
        previewRange: AnalysisTimeRange? = this.previewRange,
        searchText: String = this.searchText,
        implementation: ImplementationFilter = this.implementation,
        direction: CallStackDirection = this.direction,
        transforms: List<CallStackTransform> = this.transforms,
    ): CallStackAnalysisQuery = CallStackAnalysisQuery(previewRange, searchText, implementation, direction, transforms)

    override fun toString(): String =
        "CallStackAnalysisQuery(previewRange=$previewRange, searchText=$searchText, " +
            "implementation=$implementation, direction=$direction, transforms=$transforms)"
}

@Suppress("LongParameterList")
class CallNodeTable(
    ids: LongArray,
    parentIndexes: IntArray,
    frameIds: LongArray,
    depths: IntArray,
    inclusiveWeights: LongArray,
    selfWeights: LongArray,
    sampleCounts: LongArray,
    threadCounts: IntArray,
    categories: List<String?>,
    framesById: Map<Long, CallStackFrame>,
    idsByPath: Map<CallNodePath, FlameCallNodeId> = emptyMap(),
) {
    private val idsSnapshot = ids.copyOf()
    private val parentIndexesSnapshot = parentIndexes.copyOf()
    private val frameIdsSnapshot = frameIds.copyOf()
    private val depthsSnapshot = depths.copyOf()
    private val inclusiveWeightsSnapshot = inclusiveWeights.copyOf()
    private val selfWeightsSnapshot = selfWeights.copyOf()
    private val sampleCountsSnapshot = sampleCounts.copyOf()
    private val threadCountsSnapshot = threadCounts.copyOf()
    private val categoriesSnapshot = immutableList(categories)
    private val framesSnapshot = immutableMap(framesById)
    private val idsByPathSnapshot = immutableMap(idsByPath)
    private val indexByIdSnapshot = idsSnapshot.withIndex().associate { indexed -> indexed.value to indexed.index }
    private val pathIndexSnapshot =
        CompactCallNodePathIndex(idsSnapshot, parentIndexesSnapshot, frameIdsSnapshot, framesSnapshot)

    val ids: LongArray get() = idsSnapshot.copyOf()
    val parentIndexes: IntArray get() = parentIndexesSnapshot.copyOf()
    val frameIds: LongArray get() = frameIdsSnapshot.copyOf()
    val depths: IntArray get() = depthsSnapshot.copyOf()
    val inclusiveWeights: LongArray get() = inclusiveWeightsSnapshot.copyOf()
    val selfWeights: LongArray get() = selfWeightsSnapshot.copyOf()
    val sampleCounts: LongArray get() = sampleCountsSnapshot.copyOf()
    val threadCounts: IntArray get() = threadCountsSnapshot.copyOf()
    val categories: List<String?> get() = categoriesSnapshot
    val framesById: Map<Long, CallStackFrame> get() = framesSnapshot

    val size: Int get() = idsSnapshot.size

    fun findByPath(path: CallNodePath): FlameCallNodeId? = idsByPathSnapshot[path] ?: pathIndexSnapshot.find(path)

    fun nodeIdAt(nodeIndex: Int): FlameCallNodeId? = idsSnapshot.getOrNull(nodeIndex)?.let(::FlameCallNodeId)

    fun parentIndexAt(nodeIndex: Int): Int? = parentIndexesSnapshot.getOrNull(nodeIndex)

    fun frameAt(nodeIndex: Int): CallStackFrame? = frameIdsSnapshot.getOrNull(nodeIndex)?.let(framesSnapshot::get)

    fun depthAt(nodeIndex: Int): Int? = depthsSnapshot.getOrNull(nodeIndex)

    fun inclusiveWeightAt(nodeIndex: Int): Long? = inclusiveWeightsSnapshot.getOrNull(nodeIndex)

    fun selfWeightAt(nodeIndex: Int): Long? = selfWeightsSnapshot.getOrNull(nodeIndex)

    fun indexOf(nodeId: FlameCallNodeId): Int? = indexByIdSnapshot[nodeId.value]

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is CallNodeTable &&
            idsSnapshot.contentEquals(other.idsSnapshot) &&
            parentIndexesSnapshot.contentEquals(other.parentIndexesSnapshot) &&
            frameIdsSnapshot.contentEquals(other.frameIdsSnapshot) &&
            depthsSnapshot.contentEquals(other.depthsSnapshot) &&
            inclusiveWeightsSnapshot.contentEquals(other.inclusiveWeightsSnapshot) &&
            selfWeightsSnapshot.contentEquals(other.selfWeightsSnapshot) &&
            sampleCountsSnapshot.contentEquals(other.sampleCountsSnapshot) &&
            threadCountsSnapshot.contentEquals(other.threadCountsSnapshot) &&
            categoriesSnapshot == other.categoriesSnapshot &&
            framesSnapshot == other.framesSnapshot &&
            idsByPathSnapshot == other.idsByPathSnapshot

    override fun hashCode(): Int {
        var result = idsSnapshot.contentHashCode()
        result = 31 * result + parentIndexesSnapshot.contentHashCode()
        result = 31 * result + frameIdsSnapshot.contentHashCode()
        result = 31 * result + depthsSnapshot.contentHashCode()
        result = 31 * result + inclusiveWeightsSnapshot.contentHashCode()
        result = 31 * result + selfWeightsSnapshot.contentHashCode()
        result = 31 * result + sampleCountsSnapshot.contentHashCode()
        result = 31 * result + threadCountsSnapshot.contentHashCode()
        result = 31 * result + categoriesSnapshot.hashCode()
        result = 31 * result + framesSnapshot.hashCode()
        result = 31 * result + idsByPathSnapshot.hashCode()
        return result
    }
}

private class CompactCallNodePathIndex(
    ids: LongArray,
    parentIndexes: IntArray,
    frameIds: LongArray,
    framesById: Map<Long, CallStackFrame>,
) {
    private class Node(
        val id: FlameCallNodeId? = null,
    ) {
        val children = HashMap<FlameFunctionId, Node>()
    }

    private val root = Node()

    init {
        val nodesByIndex = arrayOfNulls<Node>(ids.size)
        ids.indices.forEach { index ->
            val parentIndex = parentIndexes.getOrNull(index) ?: return@forEach
            val frameId = frameIds.getOrNull(index) ?: return@forEach
            val functionId = framesById[frameId]?.functionId ?: return@forEach
            val parent = if (parentIndex == -1) root else nodesByIndex.getOrNull(parentIndex) ?: return@forEach
            val node = parent.children.getOrPut(functionId) { Node(FlameCallNodeId(ids[index])) }
            nodesByIndex[index] = node
        }
    }

    fun find(path: CallNodePath): FlameCallNodeId? {
        var current = root
        path.functions.forEach { functionId ->
            current = current.children[functionId] ?: return null
        }
        return current.id
    }
}

class FlameGraphRows(
    nodeIndexesByRow: List<IntArray>,
    starts: DoubleArray,
    ends: DoubleArray,
    val startsAtBottom: Boolean,
) {
    private val nodeIndexesSnapshot = nodeIndexesByRow.map(IntArray::copyOf)
    private val startsSnapshot = starts.copyOf()
    private val endsSnapshot = ends.copyOf()

    val nodeIndexesByRow: List<IntArray> get() = nodeIndexesSnapshot.map(IntArray::copyOf)
    val starts: DoubleArray get() = startsSnapshot.copyOf()
    val ends: DoubleArray get() = endsSnapshot.copyOf()
    val rowCount: Int get() = nodeIndexesSnapshot.size

    fun nodeIndexesAt(rowIndex: Int): IntArray? = nodeIndexesSnapshot.getOrNull(rowIndex)?.copyOf()

    fun startAt(nodeIndex: Int): Double? = startsSnapshot.getOrNull(nodeIndex)

    fun endAt(nodeIndex: Int): Double? = endsSnapshot.getOrNull(nodeIndex)

    fun normalizedWidthAt(nodeIndex: Int): Double? {
        val start = startsSnapshot.getOrNull(nodeIndex)
        val end = endsSnapshot.getOrNull(nodeIndex)
        return if (start == null || end == null) null else (end - start).takeIf(Double::isFinite)
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is FlameGraphRows &&
            startsAtBottom == other.startsAtBottom &&
            nodeIndexesSnapshot.arraysEqual(other.nodeIndexesSnapshot) &&
            startsSnapshot.contentEquals(other.startsSnapshot) &&
            endsSnapshot.contentEquals(other.endsSnapshot)

    override fun hashCode(): Int {
        var result = nodeIndexesSnapshot.fold(1) { hash, row -> 31 * hash + row.contentHashCode() }
        result = 31 * result + startsSnapshot.contentHashCode()
        result = 31 * result + endsSnapshot.contentHashCode()
        result = 31 * result + startsAtBottom.hashCode()
        return result
    }
}

private fun List<IntArray>.arraysEqual(other: List<IntArray>): Boolean =
    size == other.size && indices.all { index -> this[index].contentEquals(other[index]) }

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

@ConsistentCopyVisibility
data class FlameGraphSnapshot private constructor(
    val query: CallStackAnalysisQuery,
    val callNodes: CallNodeTable,
    val rows: FlameGraphRows,
    val totalWeight: Long,
    val emptyReason: FlameGraphEmptyReason?,
    val invalidTransforms: List<CallStackTransform>,
    private val immutableSnapshot: Boolean,
) {
    constructor(
        query: CallStackAnalysisQuery,
        callNodes: CallNodeTable,
        rows: FlameGraphRows,
        totalWeight: Long,
        emptyReason: FlameGraphEmptyReason?,
        invalidTransforms: List<CallStackTransform>,
    ) : this(query, callNodes, rows, totalWeight, emptyReason, immutableList(invalidTransforms), true)

    @Suppress("LongParameterList")
    fun copy(
        query: CallStackAnalysisQuery = this.query,
        callNodes: CallNodeTable = this.callNodes,
        rows: FlameGraphRows = this.rows,
        totalWeight: Long = this.totalWeight,
        emptyReason: FlameGraphEmptyReason? = this.emptyReason,
        invalidTransforms: List<CallStackTransform> = this.invalidTransforms,
    ): FlameGraphSnapshot = FlameGraphSnapshot(query, callNodes, rows, totalWeight, emptyReason, invalidTransforms)

    override fun toString(): String =
        "FlameGraphSnapshot(query=$query, callNodes=$callNodes, rows=$rows, totalWeight=$totalWeight, " +
            "emptyReason=$emptyReason, invalidTransforms=$invalidTransforms)"
}

fun parseFlameSearchTerms(searchText: String): List<String> =
    searchText
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)

private fun <T> immutableList(source: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(source))

private fun immutableCategories(
    frameIdsRootToLeaf: List<Long>,
    categoriesRootToLeaf: List<String?>?,
    fallbackCategory: String?,
): List<String?> {
    require(categoriesRootToLeaf == null || categoriesRootToLeaf.size == frameIdsRootToLeaf.size) {
        "categoriesRootToLeaf must align with frameIdsRootToLeaf"
    }
    return immutableList(categoriesRootToLeaf ?: List(frameIdsRootToLeaf.size) { fallbackCategory })
}

private fun <K, V> immutableMap(source: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(source))
