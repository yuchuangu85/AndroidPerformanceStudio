package com.androidperformancestudio.profileanalysis

import java.util.Collections

@JvmInline
value class FlameFunctionId(
    val value: Long,
) {
    companion object {
        fun fromSymbol(
            symbolName: String,
            resource: String,
        ): FlameFunctionId {
            var primaryHash = SYMBOL_ID_HASH_OFFSET
            var secondaryHash: Long? = null
            // Length-prefix both fields so field boundaries cannot alias.
            for (component in listOf(symbolName, resource)) {
                val length = FlameFunctionId(component.length.toLong())
                primaryHash = StableCallNodeId.primaryHashStep(primaryHash, length)
                secondaryHash = StableCallNodeId.secondaryHashStep(secondaryHash, length)
                component.forEach { character ->
                    val code = FlameFunctionId(character.code.toLong())
                    primaryHash = StableCallNodeId.primaryHashStep(primaryHash, code)
                    secondaryHash = StableCallNodeId.secondaryHashStep(secondaryHash, code)
                }
            }
            return FlameFunctionId(StableCallNodeId.derive(primaryHash, checkNotNull(secondaryHash)))
        }
    }
}

private const val SYMBOL_ID_HASH_OFFSET = 0x510e527fade682d1L

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

enum class CallStackWeightType {
    NANOSECONDS,
    SAMPLES,
    EVENTS,
}

enum class FrameImplementation {
    NATIVE,
    MANAGED,
    KERNEL,
    UNKNOWN,
}

enum class ImplementationFilter {
    ALL,
    SCRIPT,
    NATIVE,
}

@Suppress("LongParameterList")
data class CallStackFrame(
    val frameId: Long,
    val functionId: FlameFunctionId,
    val symbolName: String,
    val resource: String,
    val virtualAddress: Long,
    val implementation: FrameImplementation,
    val collapsedResource: String? = null,
    val sourceFile: String? = null,
    val originalSourceFile: String? = null,
)

fun CallStackFrame.firefoxCategoryName(): String = firefoxCategoryName(symbolName, resource)

fun firefoxCategoryName(
    symbolName: String,
    resource: String,
): String =
    when {
        resource.contains("kallsyms") || resource.endsWith(".ko") ->
            if (symbolName == "__schedule" || symbolName.startsWith("__schedule ")) "Off-CPU" else "Kernel"
        resource.endsWith(".so") -> "Native"
        resource.endsWith(".vdex") -> "DEX"
        resource.endsWith(".oat") -> "OAT"
        resource == "[JIT app cache]" -> "JIT"
        else -> "User"
    }

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
    val tracedDurationNanos: Long?,
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
        tracedDurationNanos: Long? = null,
    ) : this(
        sampleId,
        timestampNanos,
        weight,
        threadKey,
        category,
        subcategory,
        immutableList(frameIdsRootToLeaf),
        immutableCategories(frameIdsRootToLeaf, categoriesRootToLeaf, category),
        tracedDurationNanos,
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
        tracedDurationNanos: Long? = this.tracedDurationNanos,
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
            tracedDurationNanos,
        )

    override fun toString(): String =
        "WeightedCallStack(sampleId=$sampleId, timestampNanos=$timestampNanos, weight=$weight, " +
            "threadKey=$threadKey, category=$category, subcategory=$subcategory, " +
            "frameIdsRootToLeaf=$frameIdsRootToLeaf, categoriesRootToLeaf=$categoriesRootToLeaf, " +
            "tracedDurationNanos=$tracedDurationNanos)"
}

@ConsistentCopyVisibility
data class CallStackTable private constructor(
    val framesById: Map<Long, CallStackFrame>,
    val stacks: List<WeightedCallStack>,
    val weightType: CallStackWeightType,
    private val immutableSnapshot: Boolean,
) {
    constructor(
        framesById: Map<Long, CallStackFrame>,
        stacks: List<WeightedCallStack>,
        weightType: CallStackWeightType = CallStackWeightType.NANOSECONDS,
    ) : this(immutableMap(framesById), immutableList(stacks), weightType, true)

    fun frame(frameId: Long): CallStackFrame = checkNotNull(framesById[frameId])

    fun copy(
        framesById: Map<Long, CallStackFrame> = this.framesById,
        stacks: List<WeightedCallStack> = this.stacks,
        weightType: CallStackWeightType = this.weightType,
    ): CallStackTable = CallStackTable(framesById, stacks, weightType)

    override fun toString(): String = "CallStackTable(framesById=$framesById, stacks=$stacks, weightType=$weightType)"
}

sealed interface CallStackTransform {
    data class FocusCallNode(
        val path: CallNodePath,
        val implementation: ImplementationFilter = ImplementationFilter.ALL,
        val direction: CallStackDirection = CallStackDirection.FORWARD,
    ) : CallStackTransform

    data class FocusFunction(
        val function: FlameFunctionId,
    ) : CallStackTransform

    data class FocusFunctionSelf(
        val function: FlameFunctionId,
    ) : CallStackTransform

    data class MergeCallNode(
        val path: CallNodePath,
        val implementation: ImplementationFilter = ImplementationFilter.ALL,
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
    val weightType: CallStackWeightType,
    private val immutableSnapshot: Boolean,
) {
    constructor(
        previewRange: AnalysisTimeRange? = null,
        searchText: String = "",
        implementation: ImplementationFilter = ImplementationFilter.ALL,
        direction: CallStackDirection = CallStackDirection.FORWARD,
        transforms: List<CallStackTransform> = emptyList(),
        weightType: CallStackWeightType = CallStackWeightType.NANOSECONDS,
    ) : this(previewRange, searchText, implementation, direction, immutableList(transforms), weightType, true)

    @Suppress("LongParameterList")
    fun copy(
        previewRange: AnalysisTimeRange? = this.previewRange,
        searchText: String = this.searchText,
        implementation: ImplementationFilter = this.implementation,
        direction: CallStackDirection = this.direction,
        transforms: List<CallStackTransform> = this.transforms,
        weightType: CallStackWeightType = this.weightType,
    ): CallStackAnalysisQuery =
        CallStackAnalysisQuery(
            previewRange = previewRange,
            searchText = searchText,
            implementation = implementation,
            direction = direction,
            transforms = transforms,
            weightType = weightType,
        )

    override fun toString(): String =
        "CallStackAnalysisQuery(previewRange=$previewRange, searchText=$searchText, " +
            "implementation=$implementation, direction=$direction, transforms=$transforms, weightType=$weightType)"
}

data class CallNodeCategoryMetrics(
    val category: String?,
    val subcategory: String?,
    val sampleCount: Long,
    val selfSampleCount: Long,
    val inclusiveWeight: Long,
    val selfWeight: Long,
)

@Suppress("LongParameterList", "TooManyFunctions")
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
    selfSampleCounts: LongArray? = null,
    categoryMetrics: List<List<CallNodeCategoryMetrics>>? = null,
    tracedInclusiveNanos: LongArray? = null,
    tracedSelfNanos: LongArray? = null,
) {
    private val idsSnapshot = ids.copyOf()
    private val parentIndexesSnapshot = parentIndexes.copyOf()
    private val frameIdsSnapshot = frameIds.copyOf()
    private val depthsSnapshot = depths.copyOf()
    private val inclusiveWeightsSnapshot = inclusiveWeights.copyOf()
    private val selfWeightsSnapshot = selfWeights.copyOf()
    private val sampleCountsSnapshot = sampleCounts.copyOf()
    private val selfSampleCountsSnapshot =
        selfSampleCounts?.copyOf() ?: deriveSelfSampleCounts(parentIndexesSnapshot, sampleCountsSnapshot)
    private val categoryMetricsSnapshot = categoryMetrics?.let { immutableList(it.map(::immutableList)) }
    private val tracedInclusiveNanosSnapshot = tracedInclusiveNanos?.copyOf()
    private val tracedSelfNanosSnapshot = tracedSelfNanos?.copyOf()
    private val threadCountsSnapshot = threadCounts.copyOf()
    private val categoriesSnapshot = immutableList(categories)
    private val framesSnapshot = immutableMap(framesById)
    private val idsByPathSnapshot = immutableMap(idsByPath)
    private val indexByIdSnapshot = idsSnapshot.withIndex().associate { indexed -> indexed.value to indexed.index }
    private val adjacencySnapshot = CallNodeAdjacency(parentIndexesSnapshot, idsSnapshot.size)
    private val pathIndexSnapshot =
        CompactCallNodePathIndex(idsSnapshot, parentIndexesSnapshot, frameIdsSnapshot, framesSnapshot)

    init {
        require(selfSampleCountsSnapshot.size == idsSnapshot.size) { "Self sample counts must align with call nodes" }
        require(categoryMetricsSnapshot == null || categoryMetricsSnapshot.size == idsSnapshot.size) {
            "Category metrics must align with call nodes"
        }
        require(tracedInclusiveNanosSnapshot == null || tracedInclusiveNanosSnapshot.size == idsSnapshot.size) {
            "Traced inclusive times must align with call nodes"
        }
        require(tracedSelfNanosSnapshot == null || tracedSelfNanosSnapshot.size == idsSnapshot.size) {
            "Traced self times must align with call nodes"
        }
    }

    val ids: LongArray get() = idsSnapshot.copyOf()
    val parentIndexes: IntArray get() = parentIndexesSnapshot.copyOf()
    val frameIds: LongArray get() = frameIdsSnapshot.copyOf()
    val depths: IntArray get() = depthsSnapshot.copyOf()
    val inclusiveWeights: LongArray get() = inclusiveWeightsSnapshot.copyOf()
    val selfWeights: LongArray get() = selfWeightsSnapshot.copyOf()
    val sampleCounts: LongArray get() = sampleCountsSnapshot.copyOf()
    val selfSampleCounts: LongArray get() = selfSampleCountsSnapshot.copyOf()
    val categoryMetrics: List<List<CallNodeCategoryMetrics>>? get() = categoryMetricsSnapshot
    val tracedInclusiveNanos: LongArray? get() = tracedInclusiveNanosSnapshot?.copyOf()
    val tracedSelfNanos: LongArray? get() = tracedSelfNanosSnapshot?.copyOf()
    val threadCounts: IntArray get() = threadCountsSnapshot.copyOf()
    val categories: List<String?> get() = categoriesSnapshot
    val framesById: Map<Long, CallStackFrame> get() = framesSnapshot

    val size: Int get() = idsSnapshot.size

    fun findByPath(path: CallNodePath): FlameCallNodeId? = idsByPathSnapshot[path] ?: pathIndexSnapshot.find(path)

    fun nodeIdAt(nodeIndex: Int): FlameCallNodeId? = idsSnapshot.getOrNull(nodeIndex)?.let(::FlameCallNodeId)

    fun parentIndexAt(nodeIndex: Int): Int? = parentIndexesSnapshot.getOrNull(nodeIndex)

    fun firstChildIndexAt(nodeIndex: Int): Int? = adjacencySnapshot.firstChildIndexAt(nodeIndex)

    fun nextSiblingIndexAt(nodeIndex: Int): Int? = adjacencySnapshot.nextSiblingIndexAt(nodeIndex)

    fun previousSiblingIndexAt(nodeIndex: Int): Int? = adjacencySnapshot.previousSiblingIndexAt(nodeIndex)

    fun frameAt(nodeIndex: Int): CallStackFrame? = frameIdsSnapshot.getOrNull(nodeIndex)?.let(framesSnapshot::get)

    fun depthAt(nodeIndex: Int): Int? = depthsSnapshot.getOrNull(nodeIndex)

    fun inclusiveWeightAt(nodeIndex: Int): Long? = inclusiveWeightsSnapshot.getOrNull(nodeIndex)

    fun selfWeightAt(nodeIndex: Int): Long? = selfWeightsSnapshot.getOrNull(nodeIndex)

    fun sampleCountAt(nodeIndex: Int): Long? = sampleCountsSnapshot.getOrNull(nodeIndex)

    fun selfSampleCountAt(nodeIndex: Int): Long = selfSampleCountsSnapshot.getOrNull(nodeIndex) ?: 0L

    fun categoryMetricsAt(nodeIndex: Int): List<CallNodeCategoryMetrics>? = categoryMetrics?.getOrNull(nodeIndex)

    fun tracedInclusiveNanosAt(nodeIndex: Int): Long? = tracedInclusiveNanosSnapshot?.getOrNull(nodeIndex)

    fun tracedSelfNanosAt(nodeIndex: Int): Long? = tracedSelfNanosSnapshot?.getOrNull(nodeIndex)

    fun threadCountAt(nodeIndex: Int): Int? = threadCountsSnapshot.getOrNull(nodeIndex)

    fun categoryAt(nodeIndex: Int): String? = categoriesSnapshot.getOrNull(nodeIndex)

    fun indexOf(nodeId: FlameCallNodeId): Int? = indexByIdSnapshot[nodeId.value]

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is CallNodeTable &&
            idsSnapshot.contentEquals(other.idsSnapshot) &&
            parentIndexesSnapshot.contentEquals(other.parentIndexesSnapshot) &&
            frameIdsSnapshot.contentEquals(other.frameIdsSnapshot) &&
            depthsSnapshot.contentEquals(other.depthsSnapshot) &&
            equalWeightColumns(other) &&
            categoryMetricsSnapshot == other.categoryMetricsSnapshot &&
            threadCountsSnapshot.contentEquals(other.threadCountsSnapshot) &&
            categoriesSnapshot == other.categoriesSnapshot &&
            framesSnapshot == other.framesSnapshot &&
            idsByPathSnapshot == other.idsByPathSnapshot

    private fun equalWeightColumns(other: CallNodeTable): Boolean =
        inclusiveWeightsSnapshot.contentEquals(other.inclusiveWeightsSnapshot) &&
            selfWeightsSnapshot.contentEquals(other.selfWeightsSnapshot) &&
            sampleCountsSnapshot.contentEquals(other.sampleCountsSnapshot) &&
            selfSampleCountsSnapshot.contentEquals(other.selfSampleCountsSnapshot) &&
            tracedInclusiveNanosSnapshot.contentEquals(other.tracedInclusiveNanosSnapshot) &&
            tracedSelfNanosSnapshot.contentEquals(other.tracedSelfNanosSnapshot)

    override fun hashCode(): Int {
        var result = idsSnapshot.contentHashCode()
        result = 31 * result + parentIndexesSnapshot.contentHashCode()
        result = 31 * result + frameIdsSnapshot.contentHashCode()
        result = 31 * result + depthsSnapshot.contentHashCode()
        result = 31 * result + inclusiveWeightsSnapshot.contentHashCode()
        result = 31 * result + selfWeightsSnapshot.contentHashCode()
        result = 31 * result + sampleCountsSnapshot.contentHashCode()
        result = 31 * result + selfSampleCountsSnapshot.contentHashCode()
        result = 31 * result + categoryMetricsSnapshot.hashCode()
        result = 31 * result + tracedInclusiveNanosSnapshot.contentHashCode()
        result = 31 * result + tracedSelfNanosSnapshot.contentHashCode()
        result = 31 * result + threadCountsSnapshot.contentHashCode()
        result = 31 * result + categoriesSnapshot.hashCode()
        result = 31 * result + framesSnapshot.hashCode()
        result = 31 * result + idsByPathSnapshot.hashCode()
        return result
    }
}

private fun deriveSelfSampleCounts(
    parents: IntArray,
    samples: LongArray,
): LongArray {
    val self = LongArray(samples.size) { samples[it].coerceAtLeast(0) }
    parents.forEachIndexed { index, parent ->
        if (parent in self.indices && parent != index) {
            self[parent] = (self[parent] - (samples.getOrNull(index) ?: 0L).coerceAtLeast(0)).coerceAtLeast(0)
        }
    }
    return self
}

private class CallNodeAdjacency(
    parentIndexes: IntArray,
    size: Int,
) {
    private val firstChildIndexes = IntArray(size) { NO_NODE_INDEX }
    private val nextSiblingIndexes = IntArray(size) { NO_NODE_INDEX }
    private val previousSiblingIndexes = IntArray(size) { NO_NODE_INDEX }

    init {
        val lastChildIndexes = IntArray(size) { NO_NODE_INDEX }
        var lastRootIndex = NO_NODE_INDEX
        repeat(size) { nodeIndex ->
            val parentIndex = parentIndexes.getOrNull(nodeIndex) ?: NO_NODE_INDEX
            if (parentIndex == NO_NODE_INDEX) {
                if (lastRootIndex != NO_NODE_INDEX) {
                    nextSiblingIndexes[lastRootIndex] = nodeIndex
                    previousSiblingIndexes[nodeIndex] = lastRootIndex
                }
                lastRootIndex = nodeIndex
            } else if (parentIndex in 0 until size && parentIndex != nodeIndex) {
                val previousSiblingIndex = lastChildIndexes[parentIndex]
                if (previousSiblingIndex == NO_NODE_INDEX) {
                    firstChildIndexes[parentIndex] = nodeIndex
                } else {
                    nextSiblingIndexes[previousSiblingIndex] = nodeIndex
                    previousSiblingIndexes[nodeIndex] = previousSiblingIndex
                }
                lastChildIndexes[parentIndex] = nodeIndex
            }
        }
    }

    fun firstChildIndexAt(nodeIndex: Int): Int? = firstChildIndexes.nodeIndexAt(nodeIndex)

    fun nextSiblingIndexAt(nodeIndex: Int): Int? = nextSiblingIndexes.nodeIndexAt(nodeIndex)

    fun previousSiblingIndexAt(nodeIndex: Int): Int? = previousSiblingIndexes.nodeIndexAt(nodeIndex)
}

private fun IntArray.nodeIndexAt(index: Int): Int? = getOrNull(index)?.takeUnless { it == NO_NODE_INDEX }

private const val NO_NODE_INDEX = -1

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

@Suppress("LongParameterList")
data class FlameGraphStageCounts(
    val sourceStackCount: Int,
    val selectedThreadHasNoSamples: Boolean,
    val committedRangeExcludedSamples: Boolean,
    val afterPreviewCount: Int,
    val afterSearchCount: Int,
    val afterImplementationCount: Int,
    val afterTransformCount: Int,
    val incompleteStackCount: Int,
    val projectedNodeCount: Int,
    val projectionFailure: String?,
) {
    fun emptyReason(): FlameGraphEmptyReason? =
        when {
            selectedThreadHasNoSamples -> FlameGraphEmptyReason.THREAD_HAS_NO_SAMPLES
            sourceStackCount == 0 && committedRangeExcludedSamples -> FlameGraphEmptyReason.COMMITTED_RANGE_EMPTY
            sourceStackCount == 0 -> FlameGraphEmptyReason.THREAD_HAS_NO_SAMPLES
            afterPreviewCount == 0 -> FlameGraphEmptyReason.PREVIEW_RANGE_EMPTY
            afterTransformCount == 0 -> FlameGraphEmptyReason.TRANSFORMS_FILTERED_ALL
            afterImplementationCount == 0 -> FlameGraphEmptyReason.IMPLEMENTATION_FILTERED_ALL
            afterSearchCount == 0 -> FlameGraphEmptyReason.SEARCH_FILTERED_ALL
            incompleteStackCount > 0 && projectedNodeCount == 0 -> FlameGraphEmptyReason.PROFILE_INCOMPLETE
            projectionFailure != null -> FlameGraphEmptyReason.PROJECTION_FAILED
            else -> null
        }
}

@ConsistentCopyVisibility
data class FlameGraphSnapshot private constructor(
    val query: CallStackAnalysisQuery,
    val callNodes: CallNodeTable,
    val rows: FlameGraphRows,
    val totalWeight: Long,
    val emptyReason: FlameGraphEmptyReason?,
    val invalidTransforms: List<CallStackTransform>,
    val diagnosticDetails: String?,
    val weightType: CallStackWeightType,
    private val immutableSnapshot: Boolean,
) {
    constructor(
        query: CallStackAnalysisQuery,
        callNodes: CallNodeTable,
        rows: FlameGraphRows,
        totalWeight: Long,
        emptyReason: FlameGraphEmptyReason?,
        invalidTransforms: List<CallStackTransform>,
        diagnosticDetails: String? = null,
        weightType: CallStackWeightType = CallStackWeightType.NANOSECONDS,
    ) : this(
        query,
        callNodes,
        rows,
        totalWeight,
        emptyReason,
        immutableList(invalidTransforms),
        diagnosticDetails,
        weightType,
        true,
    )

    @Suppress("LongParameterList")
    fun copy(
        query: CallStackAnalysisQuery = this.query,
        callNodes: CallNodeTable = this.callNodes,
        rows: FlameGraphRows = this.rows,
        totalWeight: Long = this.totalWeight,
        emptyReason: FlameGraphEmptyReason? = this.emptyReason,
        invalidTransforms: List<CallStackTransform> = this.invalidTransforms,
        diagnosticDetails: String? = this.diagnosticDetails,
        weightType: CallStackWeightType = this.weightType,
    ): FlameGraphSnapshot =
        FlameGraphSnapshot(
            query,
            callNodes,
            rows,
            totalWeight,
            emptyReason,
            invalidTransforms,
            diagnosticDetails,
            weightType,
        )

    override fun toString(): String =
        "FlameGraphSnapshot(query=$query, callNodes=$callNodes, rows=$rows, totalWeight=$totalWeight, " +
            "emptyReason=$emptyReason, invalidTransforms=$invalidTransforms, " +
            "diagnosticDetails=$diagnosticDetails, weightType=$weightType)"
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
