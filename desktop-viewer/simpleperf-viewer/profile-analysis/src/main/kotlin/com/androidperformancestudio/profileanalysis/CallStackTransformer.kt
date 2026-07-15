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
        var currentStacks = table.stacks
        val appliedTransforms = ArrayList<CallStackTransform>(transforms.size)
        val invalidTransforms = ArrayList<CallStackTransform>()

        transforms.forEach { transform ->
            if (!isValidCallNodeTransform(table, currentStacks, transform)) {
                invalidTransforms += transform
            } else {
                currentStacks = applyTransform(table, currentStacks, transform)
                appliedTransforms += transform
            }
        }

        val outputTable =
            if (table.stacks.hasSameInstances(currentStacks)) {
                table
            } else {
                table.copy(stacks = currentStacks)
            }
        return TransformResult(
            table = outputTable,
            appliedTransforms = appliedTransforms,
            invalidTransforms = invalidTransforms,
            inputStackCount = table.stacks.size,
            outputStackCount = currentStacks.size,
        )
    }
}

private fun isValidCallNodeTransform(
    table: CallStackTable,
    stacks: List<WeightedCallStack>,
    transform: CallStackTransform,
): Boolean =
    when (transform) {
        is CallStackTransform.FocusCallNode -> table.containsPath(stacks, transform.path)
        is CallStackTransform.MergeCallNode -> table.containsPath(stacks, transform.path)
        else -> true
    }

private fun applyTransform(
    table: CallStackTable,
    stacks: List<WeightedCallStack>,
    transform: CallStackTransform,
): List<WeightedCallStack> =
    when (transform) {
        is CallStackTransform.FocusCallNode -> focusCallNode(table, stacks, transform.path)
        is CallStackTransform.FocusFunction -> focusFunction(table, stacks, transform.function)
        is CallStackTransform.FocusFunctionSelf -> focusFunctionSelf(table, stacks, transform.function)
        is CallStackTransform.MergeCallNode -> mergeCallNode(table, stacks, transform.path)
        is CallStackTransform.MergeFunction -> mergeFunction(table, stacks, transform.function)
        is CallStackTransform.DropFunction -> dropFunction(table, stacks, transform.function)
        is CallStackTransform.CollapseResource -> collapseResource(table, stacks, transform.resource)
        is CallStackTransform.CollapseRecursion -> collapseRecursion(table, stacks, transform.function)
        is CallStackTransform.CollapseDirectRecursion ->
            collapseDirectRecursion(table, stacks, transform.function)
        is CallStackTransform.CollapseFunctionSubtree ->
            collapseFunctionSubtree(table, stacks, transform.function)
        is CallStackTransform.FocusCategory -> focusCategory(stacks, transform.category)
    }

private fun focusCallNode(
    table: CallStackTable,
    stacks: List<WeightedCallStack>,
    path: CallNodePath,
): List<WeightedCallStack> =
    stacks.mapNotNull { stack ->
        val functions = table.functions(stack)
        if (functions.startsWith(path.functions)) {
            stack.withFramesOrNull(stack.frameIdsRootToLeaf.drop(path.functions.lastIndex))
        } else {
            null
        }
    }

private fun focusFunction(
    table: CallStackTable,
    stacks: List<WeightedCallStack>,
    function: FlameFunctionId,
): List<WeightedCallStack> =
    stacks.mapNotNull { stack ->
        val focusIndex = table.functions(stack).indexOf(function)
        if (focusIndex == -1) null else stack.withFramesOrNull(stack.frameIdsRootToLeaf.drop(focusIndex))
    }

private fun focusFunctionSelf(
    table: CallStackTable,
    stacks: List<WeightedCallStack>,
    function: FlameFunctionId,
): List<WeightedCallStack> =
    stacks.mapNotNull { stack ->
        val leafFrameId = stack.frameIdsRootToLeaf.lastOrNull() ?: return@mapNotNull null
        if (table.frame(leafFrameId).functionId == function) {
            stack.withFramesOrNull(listOf(leafFrameId))
        } else {
            null
        }
    }

private fun mergeCallNode(
    table: CallStackTable,
    stacks: List<WeightedCallStack>,
    path: CallNodePath,
): List<WeightedCallStack> =
    stacks.mapNotNull { stack ->
        val functions = table.functions(stack)
        if (functions.startsWith(path.functions)) {
            stack.withFramesOrNull(stack.frameIdsRootToLeaf.withoutIndex(path.functions.lastIndex))
        } else {
            stack
        }
    }

private fun mergeFunction(
    table: CallStackTable,
    stacks: List<WeightedCallStack>,
    function: FlameFunctionId,
): List<WeightedCallStack> =
    stacks.mapNotNull { stack ->
        stack.withFramesOrNull(
            stack.frameIdsRootToLeaf.filter { frameId -> table.frame(frameId).functionId != function },
        )
    }

private fun dropFunction(
    table: CallStackTable,
    stacks: List<WeightedCallStack>,
    function: FlameFunctionId,
): List<WeightedCallStack> =
    stacks.filterNot { stack ->
        stack.frameIdsRootToLeaf.any { frameId -> table.frame(frameId).functionId == function }
    }

private fun collapseResource(
    table: CallStackTable,
    stacks: List<WeightedCallStack>,
    resource: String,
): List<WeightedCallStack> =
    stacks.mapNotNull { stack ->
        stack.withFramesOrNull(
            stack.frameIdsRootToLeaf.collapseConsecutive { frameId -> table.frame(frameId).resource == resource },
        )
    }

private fun collapseRecursion(
    table: CallStackTable,
    stacks: List<WeightedCallStack>,
    function: FlameFunctionId,
): List<WeightedCallStack> =
    stacks.mapNotNull { stack ->
        val functions = table.functions(stack)
        val firstIndex = functions.indexOf(function)
        val lastIndex = functions.lastIndexOf(function)
        val newFrames =
            if (firstIndex == -1 || firstIndex == lastIndex) {
                stack.frameIdsRootToLeaf
            } else {
                stack.frameIdsRootToLeaf.take(firstIndex) + stack.frameIdsRootToLeaf.drop(lastIndex)
            }
        stack.withFramesOrNull(newFrames)
    }

private fun collapseDirectRecursion(
    table: CallStackTable,
    stacks: List<WeightedCallStack>,
    function: FlameFunctionId,
): List<WeightedCallStack> =
    stacks.mapNotNull { stack ->
        stack.withFramesOrNull(
            stack.frameIdsRootToLeaf.collapseConsecutive { frameId ->
                table.frame(frameId).functionId == function
            },
        )
    }

private fun collapseFunctionSubtree(
    table: CallStackTable,
    stacks: List<WeightedCallStack>,
    function: FlameFunctionId,
): List<WeightedCallStack> =
    stacks.mapNotNull { stack ->
        val functionIndex = table.functions(stack).indexOf(function)
        val newFrames =
            if (functionIndex == -1) stack.frameIdsRootToLeaf else stack.frameIdsRootToLeaf.take(functionIndex + 1)
        stack.withFramesOrNull(newFrames)
    }

private fun focusCategory(
    stacks: List<WeightedCallStack>,
    category: String,
): List<WeightedCallStack> = stacks.filter { stack -> stack.category == category }

private fun CallStackTable.containsPath(
    stacks: List<WeightedCallStack>,
    path: CallNodePath,
): Boolean = path.functions.isNotEmpty() && stacks.any { stack -> functions(stack).startsWith(path.functions) }

private fun CallStackTable.functions(stack: WeightedCallStack): List<FlameFunctionId> =
    stack.frameIdsRootToLeaf.map { frameId -> frame(frameId).functionId }

private fun WeightedCallStack.withFramesOrNull(frameIds: List<Long>): WeightedCallStack? =
    when {
        frameIds.isEmpty() -> null
        frameIds === frameIdsRootToLeaf || frameIds == frameIdsRootToLeaf -> this
        else -> copy(frameIdsRootToLeaf = frameIds)
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
