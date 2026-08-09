package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.contracts.CapabilityId
import com.androidperformancestudio.memory.model.NativeHeapAnalysis
import com.androidperformancestudio.memory.model.NativeHeapCapabilities
import com.androidperformancestudio.memory.model.NativeHeapSample
import com.androidperformancestudio.platform.perfetto.TraceColumn
import com.androidperformancestudio.platform.perfetto.TraceQuery
import com.androidperformancestudio.platform.perfetto.TraceQueryResult
import com.androidperformancestudio.platform.perfetto.TraceQuerySchema

data class NativeHeapTraceProcessorResult(
    val analysis: NativeHeapAnalysis,
    val availableCapabilities: Set<CapabilityId>,
)

class NativeHeapTraceProcessorAdapter {
    private val allocationCallsite = TraceColumn.long("callsite_id")
    private val allocatedBytes = TraceColumn.long("allocated_bytes")
    private val freedBytes = TraceColumn.long("freed_bytes")
    private val allocationCount = TraceColumn.long("alloc_count")
    private val freeCount = TraceColumn.long("free_count")
    private val stackCallsite = TraceColumn.long("callsite_id")
    private val parentCallsite = TraceColumn.long("parent_id")
    private val frameName = TraceColumn.string("frame_name")
    private val symbolized = TraceColumn.long("symbolized")

    val allocationQuery: TraceQuery<AllocationRow> =
        TraceQuery(
            sql =
                """
                SELECT callsite_id,
                       SUM(CASE WHEN size > 0 THEN size ELSE 0 END) AS allocated_bytes,
                       SUM(CASE WHEN size < 0 THEN -size ELSE 0 END) AS freed_bytes,
                       SUM(CASE WHEN count > 0 THEN count ELSE 0 END) AS alloc_count,
                       SUM(CASE WHEN count < 0 THEN -count ELSE 0 END) AS free_count
                FROM heap_profile_allocation
                GROUP BY callsite_id
                ORDER BY allocated_bytes DESC
                """.trimIndent(),
            schema = TraceQuerySchema.v57_2(allocationCallsite, allocatedBytes, freedBytes, allocationCount, freeCount),
        ) { row ->
            AllocationRow(
                callsiteId = requireNotNull(row[allocationCallsite]),
                allocatedBytes = row[allocatedBytes] ?: 0,
                freedBytes = row[freedBytes] ?: 0,
                allocCount = row[allocationCount] ?: 0,
                freeCount = row[freeCount] ?: 0,
            )
        }

    val callStackQuery: TraceQuery<CallStackRow> =
        TraceQuery(
            sql =
                """
                SELECT callsite.id AS callsite_id,
                       callsite.parent_id AS parent_id,
                       COALESCE(NULLIF(frame.name, ''), mapping.name || '+0x' || printf('%x', frame.rel_pc)) AS frame_name,
                       CASE WHEN frame.name IS NOT NULL AND frame.name != '' THEN 1 ELSE 0 END AS symbolized
                FROM stack_profile_callsite AS callsite
                JOIN stack_profile_frame AS frame ON frame.id = callsite.frame_id
                LEFT JOIN stack_profile_mapping AS mapping ON mapping.id = frame.mapping
                ORDER BY callsite.id
                """.trimIndent(),
            schema = TraceQuerySchema.v57_2(stackCallsite, parentCallsite, frameName, symbolized),
        ) { row ->
            CallStackRow(
                callsiteId = requireNotNull(row[stackCallsite]),
                parentId = row[parentCallsite],
                frameName = row[frameName] ?: "unknown",
                symbolized = row[symbolized] == 1L,
            )
        }

    fun mapFixture(
        allocationCsv: String,
        callStackCsv: String,
    ): NativeHeapTraceProcessorResult =
        map(
            allocationQuery.map(TraceQueryResult.parse(allocationCsv)),
            callStackQuery.map(TraceQueryResult.parse(callStackCsv)),
        )

    fun map(
        allocations: List<AllocationRow>,
        callStacks: List<CallStackRow>,
    ): NativeHeapTraceProcessorResult {
        val frames = callStacks.associateBy(CallStackRow::callsiteId)
        val samples =
            allocations.map { allocation ->
                val stack = callStack(allocation.callsiteId, frames)
                NativeHeapSample(
                    functionName = stack.lastOrNull() ?: "unknown",
                    allocatedBytes = allocation.allocatedBytes,
                    freedBytes = allocation.freedBytes,
                    allocCount = allocation.allocCount,
                    freeCount = allocation.freeCount,
                    callStack = stack.ifEmpty { listOf("unknown") },
                )
            }
        val capabilities =
            buildSet {
                add(NativeHeapCapabilities.ALLOCATIONS)
                add(NativeHeapCapabilities.DEALLOCATIONS)
                add(NativeHeapCapabilities.COUNTS)
                add(NativeHeapCapabilities.CALL_STACKS)
                if (callStacks.any(CallStackRow::symbolized)) add(NativeHeapCapabilities.SYMBOLS)
            }
        return NativeHeapTraceProcessorResult(
            analysis =
                NativeHeapAnalysis(
                    totalAllocatedBytes = samples.sumOf(NativeHeapSample::allocatedBytes),
                    totalFreedBytes = samples.sumOf(NativeHeapSample::freedBytes),
                    sampleCount =
                        samples
                            .sumOf(NativeHeapSample::allocCount)
                            .coerceAtMost(Int.MAX_VALUE.toLong())
                            .toInt(),
                    topAllocations = samples.sortedByDescending(NativeHeapSample::allocatedBytes),
                ),
            availableCapabilities = capabilities,
        )
    }

    private fun callStack(
        leaf: Long,
        frames: Map<Long, CallStackRow>,
    ): List<String> {
        val result = mutableListOf<String>()
        val visited = mutableSetOf<Long>()
        var current: Long? = leaf
        while (current != null && visited.add(current)) {
            val frame = frames[current] ?: break
            result += frame.frameName
            current = frame.parentId
        }
        return result.asReversed()
    }
}

data class AllocationRow(
    val callsiteId: Long,
    val allocatedBytes: Long,
    val freedBytes: Long,
    val allocCount: Long,
    val freeCount: Long,
)

data class CallStackRow(
    val callsiteId: Long,
    val parentId: Long?,
    val frameName: String,
    val symbolized: Boolean,
)
