@file:Suppress("TooManyFunctions")

package com.androidperformancestudio.profileanalysis

import java.util.Collections

@ConsistentCopyVisibility
data class TransformResult private constructor(
    val table: CallStackTable,
    val appliedTransforms: List<CallStackTransform>,
    val invalidTransforms: List<CallStackTransform>,
    val inputStackCount: Int,
    val outputStackCount: Int,
    private val immutableSnapshot: Boolean,
) {
    constructor(
        table: CallStackTable,
        appliedTransforms: List<CallStackTransform>,
        invalidTransforms: List<CallStackTransform>,
        inputStackCount: Int,
        outputStackCount: Int,
    ) : this(
        table = table,
        appliedTransforms = immutableTransformList(appliedTransforms),
        invalidTransforms = immutableTransformList(invalidTransforms),
        inputStackCount = inputStackCount,
        outputStackCount = outputStackCount,
        immutableSnapshot = true,
    )

    @Suppress("LongParameterList")
    fun copy(
        table: CallStackTable = this.table,
        appliedTransforms: List<CallStackTransform> = this.appliedTransforms,
        invalidTransforms: List<CallStackTransform> = this.invalidTransforms,
        inputStackCount: Int = this.inputStackCount,
        outputStackCount: Int = this.outputStackCount,
    ): TransformResult = TransformResult(table, appliedTransforms, invalidTransforms, inputStackCount, outputStackCount)
}

object CallStackTransformer {
    fun apply(
        table: CallStackTable,
        transforms: List<CallStackTransform>,
    ): TransformResult {
        var currentTable = table
        val appliedTransforms = ArrayList<CallStackTransform>(transforms.size)
        val invalidTransforms = ArrayList<CallStackTransform>()

        transforms.forEach { transform ->
            if (!isValidCallNodeTransform(currentTable, transform)) {
                invalidTransforms += transform
            } else {
                currentTable = applyTransform(currentTable, transform)
                appliedTransforms += transform
            }
        }

        return TransformResult(
            table = currentTable,
            appliedTransforms = appliedTransforms,
            invalidTransforms = invalidTransforms,
            inputStackCount = table.stacks.size,
            outputStackCount = currentTable.stacks.size,
        )
    }
}

private fun isValidCallNodeTransform(
    table: CallStackTable,
    transform: CallStackTransform,
): Boolean =
    when (transform) {
        is CallStackTransform.FocusCallNode -> table.containsPath(transform.path)
        is CallStackTransform.MergeCallNode -> table.containsPath(transform.path)
        else -> true
    }

private fun applyTransform(
    table: CallStackTable,
    transform: CallStackTransform,
): CallStackTable =
    when (transform) {
        is CallStackTransform.FocusCallNode -> table.withStacks(focusCallNode(table, transform.path))
        is CallStackTransform.FocusFunction -> table.withStacks(focusFunction(table, transform.function))
        is CallStackTransform.FocusFunctionSelf -> table.withStacks(focusFunctionSelf(table, transform.function))
        is CallStackTransform.MergeCallNode -> table.withStacks(mergeCallNode(table, transform.path))
        is CallStackTransform.MergeFunction -> table.withStacks(mergeFunction(table, transform.function))
        is CallStackTransform.DropFunction -> table.withStacks(dropFunction(table, transform.function))
        is CallStackTransform.CollapseResource -> collapseResource(table, transform.resource)
        is CallStackTransform.CollapseRecursion -> table.withStacks(collapseRecursion(table, transform.function))
        is CallStackTransform.CollapseDirectRecursion ->
            table.withStacks(collapseDirectRecursion(table, transform.function))
        is CallStackTransform.CollapseFunctionSubtree ->
            table.withStacks(collapseFunctionSubtree(table, transform.function))
        is CallStackTransform.FocusCategory -> table.withStacks(focusCategory(table.stacks, transform.category))
    }

private fun focusCallNode(
    table: CallStackTable,
    path: CallNodePath,
): List<WeightedCallStack> =
    table.stacks.mapNotNull { stack ->
        val functions = table.functions(stack)
        if (functions.startsWith(path.functions)) {
            stack.withNodesOrNull(stack.nodes().drop(path.functions.lastIndex))
        } else {
            null
        }
    }

private fun focusFunction(
    table: CallStackTable,
    function: FlameFunctionId,
): List<WeightedCallStack> =
    table.stacks.mapNotNull { stack ->
        val focusIndex = table.functions(stack).indexOf(function)
        if (focusIndex == -1) null else stack.withNodesOrNull(stack.nodes().drop(focusIndex))
    }

private fun focusFunctionSelf(
    table: CallStackTable,
    function: FlameFunctionId,
): List<WeightedCallStack> =
    table.stacks.mapNotNull { stack ->
        val leafNode = stack.nodes().lastOrNull() ?: return@mapNotNull null
        if (table.frame(leafNode.frameId).functionId == function) {
            stack.withNodesOrNull(listOf(leafNode))
        } else {
            null
        }
    }

private fun mergeCallNode(
    table: CallStackTable,
    path: CallNodePath,
): List<WeightedCallStack> =
    table.stacks.mapNotNull { stack ->
        val functions = table.functions(stack)
        if (functions.startsWith(path.functions)) {
            stack.withNodesOrNull(stack.nodes().withoutIndex(path.functions.lastIndex))
        } else {
            stack
        }
    }

private fun mergeFunction(
    table: CallStackTable,
    function: FlameFunctionId,
): List<WeightedCallStack> =
    table.stacks.mapNotNull { stack ->
        stack.withNodesOrNull(
            stack.nodes().filter { node -> table.frame(node.frameId).functionId != function },
        )
    }

private fun dropFunction(
    table: CallStackTable,
    function: FlameFunctionId,
): List<WeightedCallStack> =
    table.stacks.filterNot { stack ->
        stack.frameIdsRootToLeaf.any { frameId -> table.frame(frameId).functionId == function }
    }

private fun collapseResource(
    table: CallStackTable,
    resource: String,
): CallStackTable {
    val matchingFrames = table.framesById.values.filter { frame -> frame.resource == resource }
    if (matchingFrames.isEmpty()) return table

    val pseudoFunction = collapsedResourceFunctionId(resource, table.framesById.values)
    val remappedFrames =
        table.framesById.mapValues { (_, frame) ->
            if (frame.resource == resource) {
                frame.copy(functionId = pseudoFunction, collapsedResource = resource)
            } else {
                frame
            }
        }
    val remappedTable = table.copy(framesById = remappedFrames)
    val collapsedStacks =
        remappedTable.stacks.mapNotNull { stack ->
            stack.withNodesOrNull(
                stack.nodes().collapseConsecutive { node -> remappedTable.frame(node.frameId).resource == resource },
            )
        }
    return remappedTable.withStacks(collapsedStacks)
}

private fun collapseRecursion(
    table: CallStackTable,
    function: FlameFunctionId,
): List<WeightedCallStack> =
    table.stacks.mapNotNull { stack ->
        val functions = table.functions(stack)
        val firstIndex = functions.indexOf(function)
        val lastIndex = functions.lastIndexOf(function)
        val nodes = stack.nodes()
        val newNodes =
            if (firstIndex == -1 || firstIndex == lastIndex) {
                nodes
            } else {
                nodes.take(firstIndex) + nodes.drop(lastIndex)
            }
        stack.withNodesOrNull(newNodes)
    }

private fun collapseDirectRecursion(
    table: CallStackTable,
    function: FlameFunctionId,
): List<WeightedCallStack> =
    table.stacks.mapNotNull { stack ->
        stack.withNodesOrNull(
            stack.nodes().collapseConsecutive { node ->
                table.frame(node.frameId).functionId == function
            },
        )
    }

private fun collapseFunctionSubtree(
    table: CallStackTable,
    function: FlameFunctionId,
): List<WeightedCallStack> =
    table.stacks.mapNotNull { stack ->
        val functionIndex = table.functions(stack).indexOf(function)
        val nodes = stack.nodes()
        val newNodes = if (functionIndex == -1) nodes else nodes.take(functionIndex + 1)
        stack.withNodesOrNull(newNodes)
    }

private fun focusCategory(
    stacks: List<WeightedCallStack>,
    category: String,
): List<WeightedCallStack> =
    stacks.mapNotNull { stack ->
        stack.withNodesOrNull(stack.nodes().filter { node -> node.category == category })
    }

private fun CallStackTable.containsPath(path: CallNodePath): Boolean =
    path.functions.isNotEmpty() && stacks.any { stack -> functions(stack).startsWith(path.functions) }

private fun CallStackTable.functions(stack: WeightedCallStack): List<FlameFunctionId> =
    stack.frameIdsRootToLeaf.map { frameId -> frame(frameId).functionId }

private fun CallStackTable.withStacks(newStacks: List<WeightedCallStack>): CallStackTable =
    if (stacks.hasSameInstances(newStacks)) this else copy(stacks = newStacks)

private data class StackNode(
    val frameId: Long,
    val category: String?,
)

private fun WeightedCallStack.nodes(): List<StackNode> =
    frameIdsRootToLeaf.indices.map { index ->
        StackNode(frameIdsRootToLeaf[index], categoriesRootToLeaf[index])
    }

private fun WeightedCallStack.withNodesOrNull(nodes: List<StackNode>): WeightedCallStack? {
    if (nodes.isEmpty()) return null
    val frameIds = nodes.map(StackNode::frameId)
    val categories = nodes.map(StackNode::category)
    return if (frameIds == frameIdsRootToLeaf && categories == categoriesRootToLeaf) {
        this
    } else {
        copy(frameIdsRootToLeaf = frameIds, categoriesRootToLeaf = categories)
    }
}

private fun collapsedResourceFunctionId(
    resource: String,
    frames: Collection<CallStackFrame>,
): FlameFunctionId {
    val reusableIds =
        frames
            .asSequence()
            .filter { frame -> frame.collapsedResource == resource }
            .map(CallStackFrame::functionId)
            .distinct()
            .toList()
    reusableIds.singleOrNull()?.let { reusable ->
        if (frames.none { frame -> frame.functionId == reusable && frame.collapsedResource != resource }) {
            return reusable
        }
    }

    val occupied = frames.mapTo(HashSet(), CallStackFrame::functionId)
    var candidate = FlameFunctionId(stableResourceHash(resource))
    while (candidate in occupied) {
        candidate = FlameFunctionId(if (candidate.value == Long.MAX_VALUE) Long.MIN_VALUE else candidate.value + 1)
    }
    return candidate
}

private fun stableResourceHash(resource: String): Long {
    var hash = FNV_64_OFFSET_BASIS
    "collapsed-resource:$resource".encodeToByteArray().forEach { byte ->
        hash = hash xor (byte.toLong() and BYTE_MASK)
        hash *= FNV_64_PRIME
    }
    return hash
}

private fun <T> List<T>.withoutIndex(indexToRemove: Int): List<T> = filterIndexed { index, _ -> index != indexToRemove }

private fun <T> List<T>.collapseConsecutive(matches: (T) -> Boolean): List<T> {
    var previousMatched = false
    return fold(ArrayList<T>(size)) { collapsed, item ->
        val itemMatches = matches(item)
        if (itemMatches && previousMatched) {
            collapsed[collapsed.lastIndex] = item
        } else {
            collapsed += item
        }
        previousMatched = itemMatches
        collapsed
    }
}

private fun <T> List<T>.startsWith(prefix: List<T>): Boolean =
    prefix.size <= size && prefix.indices.all { index -> this[index] == prefix[index] }

private fun List<WeightedCallStack>.hasSameInstances(other: List<WeightedCallStack>): Boolean =
    size == other.size && indices.all { index -> this[index] === other[index] }

private fun immutableTransformList(source: Collection<CallStackTransform>): List<CallStackTransform> =
    Collections.unmodifiableList(ArrayList(source))

private const val FNV_64_OFFSET_BASIS = -3750763034362895579L
private const val FNV_64_PRIME = 1099511628211L
private const val BYTE_MASK = 0xffL
