package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.platform.perfetto.TraceColumn
import com.androidperformancestudio.platform.perfetto.TraceQuery
import com.androidperformancestudio.platform.perfetto.TraceQueryResult
import com.androidperformancestudio.platform.perfetto.TraceQuerySchema

/**
 * Typed Perfetto boundary for sampled Java/Kotlin allocations from `android.heapprofd`.
 * `heap_profile_allocation` reports sampled allocations, not every allocation, object
 * class identity, object deletion, or GC events. Stack frames identify code paths only.
 */
class ArtSampledAllocationTraceProcessorAdapter {
    private val callsiteId = TraceColumn.long("callsite_id")
    private val sampleRows = TraceColumn.long("sample_rows")
    private val sampledCount = TraceColumn.long("sampled_count")
    private val sampledBytes = TraceColumn.long("sampled_bytes")
    private val parentId = TraceColumn.long("parent_id")
    private val frameName = TraceColumn.string("frame_name")
    private val symbolized = TraceColumn.long("symbolized")

    val allocationQuery: TraceQuery<ArtSampledAllocationRow> =
        TraceQuery(
            sql =
                """
                SELECT callsite_id,
                       COUNT(*) AS sample_rows,
                       SUM(count) AS sampled_count,
                       SUM(size) AS sampled_bytes
                FROM heap_profile_allocation
                WHERE heap_name = 'com.android.art' AND count > 0 AND size > 0
                GROUP BY callsite_id
                ORDER BY sampled_bytes DESC, callsite_id
                """.trimIndent(),
            schema = TraceQuerySchema.v57_2(callsiteId, sampleRows, sampledCount, sampledBytes),
        ) { row ->
            ArtSampledAllocationRow(
                callsiteId = requireNotNull(row[callsiteId]),
                sampleRows = row[sampleRows] ?: 0L,
                sampledCount = row[sampledCount] ?: 0L,
                sampledBytes = row[sampledBytes] ?: 0L,
            )
        }

    val callStackQuery: TraceQuery<ArtSampledCallStackRow> =
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
            schema = TraceQuerySchema.v57_2(callsiteId, parentId, frameName, symbolized),
        ) { row ->
            ArtSampledCallStackRow(
                callsiteId = requireNotNull(row[callsiteId]),
                parentId = row[parentId],
                frameName = row[frameName],
                symbolized = row[symbolized] == 1L,
            )
        }

    fun mapFixture(
        allocationsCsv: String,
        callStacksCsv: String,
    ): ArtSampledAllocationAnalysis =
        map(
            allocationQuery.map(TraceQueryResult.parse(allocationsCsv)),
            callStackQuery.map(TraceQueryResult.parse(callStacksCsv)),
        )

    fun map(
        allocations: List<ArtSampledAllocationRow>,
        callStacks: List<ArtSampledCallStackRow>,
    ): ArtSampledAllocationAnalysis {
        val frames = callStacks.associateBy(ArtSampledCallStackRow::callsiteId)
        val callSites =
            allocations
                .filter { it.sampleRows > 0 && it.sampledCount > 0 && it.sampledBytes > 0 }
                .map { allocation ->
                    val stack = resolveStack(allocation.callsiteId, frames)
                    ArtSampledCallSite(
                        callsiteId = allocation.callsiteId,
                        sampleRows = allocation.sampleRows,
                        sampledObjectCount = allocation.sampledCount,
                        sampledBytes = allocation.sampledBytes,
                        callStack = stack.mapNotNull(ArtSampledCallStackRow::frameName),
                        hasSymbolizedFrame = stack.any(ArtSampledCallStackRow::symbolized),
                    )
                }.sortedWith(compareByDescending<ArtSampledCallSite> { it.sampledBytes }.thenBy { it.callsiteId })
        return ArtSampledAllocationAnalysis(
            sampleRows = callSites.sumOf(ArtSampledCallSite::sampleRows),
            sampledObjectCount = callSites.sumOf(ArtSampledCallSite::sampledObjectCount),
            sampledBytes = callSites.sumOf(ArtSampledCallSite::sampledBytes),
            callSites = callSites,
        )
    }

    private fun resolveStack(
        leafId: Long,
        frames: Map<Long, ArtSampledCallStackRow>,
    ): List<ArtSampledCallStackRow> {
        val reversed = mutableListOf<ArtSampledCallStackRow>()
        val visited = hashSetOf<Long>()
        var current: Long? = leafId
        while (current != null && visited.add(current)) {
            val frame = frames[current] ?: break
            reversed += frame
            current = frame.parentId
        }
        return reversed.asReversed()
    }
}

data class ArtSampledAllocationRow(
    val callsiteId: Long,
    val sampleRows: Long,
    val sampledCount: Long,
    val sampledBytes: Long,
)

data class ArtSampledCallStackRow(
    val callsiteId: Long,
    val parentId: Long?,
    val frameName: String?,
    val symbolized: Boolean,
)

data class ArtSampledCallSite(
    val callsiteId: Long,
    val sampleRows: Long,
    val sampledObjectCount: Long,
    val sampledBytes: Long,
    val callStack: List<String>,
    val hasSymbolizedFrame: Boolean,
)

/** Positive allocation samples only; these numbers are never a Full or deallocation count. */
data class ArtSampledAllocationAnalysis(
    val sampleRows: Long,
    val sampledObjectCount: Long,
    val sampledBytes: Long,
    val callSites: List<ArtSampledCallSite>,
) {
    val source: String = "perfetto/android.heapprofd/com.android.art"
    val hasDeallocationEvents: Boolean = false
    val hasGcEvents: Boolean = false
    val hasObjectClassNames: Boolean = false
    val hasFullAllocationCoverage: Boolean = false
}
