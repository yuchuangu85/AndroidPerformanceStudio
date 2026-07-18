package com.androidperformancestudio.storage

import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.CallStackTable
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import java.util.Locale

internal object CallStackTopFunctions {
    fun project(
        table: CallStackTable,
        options: TopFunctionOptions,
    ): List<TopFunction> {
        require(options.limit > 0) { "limit must be positive" }
        val canonicalFrames = table.framesById.values.canonicalFramesByFunction()
        val aggregates = linkedMapOf<FlameFunctionId, FunctionAggregate>()
        table.stacks.forEach { stack ->
            val visibleFunctions =
                stack.frameIdsRootToLeaf
                    .asSequence()
                    .map(table::frame)
                    .map(CallStackFrame::functionId)
                    .toSet()
            visibleFunctions.forEach { functionId ->
                aggregates
                    .getOrPut(functionId) { FunctionAggregate(canonicalFrames.getValue(functionId)) }
                    .recordInclusive(stack.weight, stack.threadKey)
            }
            stack.frameIdsRootToLeaf.lastOrNull()?.let(table::frame)?.let { leaf ->
                aggregates.getValue(leaf.functionId).recordSelf(stack.weight)
            }
        }

        return aggregates.values
            .asSequence()
            .map(FunctionAggregate::snapshot)
            .filter { function -> function.matches(options.search) }
            .sortedWith(options.comparator())
            .take(options.limit)
            .toList()
    }
}

private class FunctionAggregate(
    private val frame: CallStackFrame,
) {
    private val threads = HashSet<String>()
    private var inclusiveWeight = 0L
    private var exclusiveWeight = 0L
    private var sampleCount = 0L

    fun recordInclusive(
        weight: Long,
        threadKey: String,
    ) {
        inclusiveWeight = Math.addExact(inclusiveWeight, weight)
        sampleCount = Math.addExact(sampleCount, 1)
        threads += threadKey
    }

    fun recordSelf(weight: Long) {
        exclusiveWeight = Math.addExact(exclusiveWeight, weight)
    }

    fun snapshot(): TopFunction =
        TopFunction(
            symbolName = frame.symbolName,
            filePath = frame.resource,
            inclusiveWeight = inclusiveWeight,
            exclusiveWeight = exclusiveWeight,
            sampleCount = sampleCount,
            threadCount = threads.size.toLong(),
        )
}

private fun TopFunction.matches(search: String): Boolean =
    search.isBlank() || symbolName.contains(search, ignoreCase = true) || filePath.contains(search, ignoreCase = true)

private fun Collection<CallStackFrame>.canonicalFramesByFunction(): Map<FlameFunctionId, CallStackFrame> =
    groupBy(CallStackFrame::functionId).mapValues { (_, candidates) -> candidates.minWith(FRAME_ORDER) }

private fun TopFunctionOptions.comparator(): Comparator<TopFunction> {
    val requested =
        when (sort) {
            TopFunctionSort.INCLUSIVE_WEIGHT -> compareBy(TopFunction::inclusiveWeight)
            TopFunctionSort.EXCLUSIVE_WEIGHT -> compareBy(TopFunction::exclusiveWeight)
            TopFunctionSort.SAMPLE_COUNT -> compareBy(TopFunction::sampleCount)
            TopFunctionSort.THREAD_COUNT -> compareBy(TopFunction::threadCount)
            TopFunctionSort.SYMBOL_NAME -> compareBy(TopFunction::symbolName)
            TopFunctionSort.FILE_PATH -> compareBy(TopFunction::filePath)
        }.let { comparator -> if (descending) comparator.reversed() else comparator }
    return requested
        .thenBy(TopFunction::symbolName)
        .thenBy(TopFunction::filePath)
        .thenByDescending(TopFunction::inclusiveWeight)
        .thenByDescending(TopFunction::exclusiveWeight)
        .thenByDescending(TopFunction::sampleCount)
        .thenByDescending(TopFunction::threadCount)
}

private val FRAME_ORDER =
    compareBy<CallStackFrame>(
        { frame -> frame.symbolName.lowercase(Locale.ROOT) },
        CallStackFrame::symbolName,
        CallStackFrame::resource,
        { frame -> frame.implementation.ordinal },
        CallStackFrame::virtualAddress,
        CallStackFrame::frameId,
    )
