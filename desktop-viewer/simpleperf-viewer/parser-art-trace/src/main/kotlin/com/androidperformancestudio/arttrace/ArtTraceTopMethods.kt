package com.androidperformancestudio.arttrace

import com.androidperformancestudio.profileanalysis.CallStackTable

/**
 * One aggregated method row for the top-methods table: exclusive (self) and inclusive (total)
 * time in microseconds, call count, and the number of threads it ran on.
 */
data class MethodTopMethod(
    val symbolName: String,
    val resource: String,
    val selfMicros: Long,
    val totalMicros: Long,
    val callCount: Long,
    val threadCount: Int,
)

enum class MethodTopMethodSort { SYMBOL, SELF_MICROS, TOTAL_MICROS, CALL_COUNT }

/**
 * Aggregates a [CallStackTable] (as produced by [ArtTraceCallStackProjector]) into per-method
 * self/inclusive durations. Mirrors the flame graph's semantics: every interval weight is added to
 * each method on the stack path (inclusive) and to the leaf method (self). Weights are in
 * nanoseconds; the table displays microseconds. Call counts are the true method-invocation counts
 * (enter events), taken from the [analysis] rather than the interval samples.
 */
object MethodTopMethodsReducer {
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    fun topMethods(
        table: CallStackTable,
        analysis: ArtTraceAnalysis,
        search: String = "",
        limit: Int = 200,
        sort: MethodTopMethodSort = MethodTopMethodSort.SELF_MICROS,
        descending: Boolean = true,
    ): List<MethodTopMethod> {
        val selfMicros = HashMap<Long, Long>()
        val totalMicros = HashMap<Long, Long>()
        val callCounts = HashMap<Long, Long>()
        val threads = HashMap<Long, HashSet<String>>()
        analysis.events.forEach { event ->
            if (event.action == ArtTraceAction.ENTER) {
                callCounts[event.methodId] = (callCounts[event.methodId] ?: 0L) + 1
            }
        }

        table.stacks.forEach { stack ->
            val path = stack.frameIdsRootToLeaf
            if (path.isEmpty()) return@forEach
            path.forEach { frameId ->
                val frame = table.framesById[frameId] ?: return@forEach
                totalMicros[frame.functionId.value] =
                    (totalMicros[frame.functionId.value] ?: 0L) + stack.weight
                threads.getOrPut(frame.functionId.value) { HashSet() }.add(stack.threadKey)
            }
            val leaf = table.framesById[path.last()] ?: return@forEach
            selfMicros[leaf.functionId.value] =
                (selfMicros[leaf.functionId.value] ?: 0L) + stack.weight
        }

        val query = search.lowercase()
        return totalMicros.keys
            .asSequence()
            .filter { functionId ->
                query.isEmpty() || symbolOf(table, functionId).lowercase().contains(query)
            }.map { functionId ->
                MethodTopMethod(
                    symbolName = symbolOf(table, functionId),
                    resource = resourceOf(table, functionId),
                    selfMicros = (selfMicros[functionId] ?: 0L) / NANOS_PER_MICRO,
                    totalMicros = (totalMicros[functionId] ?: 0L) / NANOS_PER_MICRO,
                    callCount = callCounts[functionId] ?: 0L,
                    threadCount = threads[functionId]?.size ?: 0,
                )
            }.sortedWith(topMethodComparator(sort, descending))
            .take(limit)
            .toList()
    }

    private fun symbolOf(
        table: CallStackTable,
        functionId: Long,
    ): String {
        val frame =
            table.framesById.values.firstOrNull { it.functionId.value == functionId }
                ?: return "0x${functionId.toString(HEX_RADIX)}"
        return frame.symbolName
    }

    private fun resourceOf(
        table: CallStackTable,
        functionId: Long,
    ): String {
        val frame = table.framesById.values.firstOrNull { it.functionId.value == functionId } ?: return ""
        return frame.resource
    }

    private fun topMethodComparator(
        sort: MethodTopMethodSort,
        descending: Boolean,
    ): Comparator<MethodTopMethod> {
        val base =
            when (sort) {
                MethodTopMethodSort.SYMBOL -> compareBy(MethodTopMethod::symbolName)
                MethodTopMethodSort.SELF_MICROS -> compareBy(MethodTopMethod::selfMicros)
                MethodTopMethodSort.TOTAL_MICROS -> compareBy(MethodTopMethod::totalMicros)
                MethodTopMethodSort.CALL_COUNT -> compareBy(MethodTopMethod::callCount)
            }
        val ordered = base.thenBy(MethodTopMethod::symbolName)
        return if (descending) ordered.reversed() else ordered
    }

    private const val NANOS_PER_MICRO = 1000L
    private const val HEX_RADIX = 16
}
