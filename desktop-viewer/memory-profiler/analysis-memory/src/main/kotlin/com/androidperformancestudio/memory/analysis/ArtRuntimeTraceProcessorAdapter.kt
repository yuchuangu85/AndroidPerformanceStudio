@file:Suppress("MagicNumber", "MaxLineLength")

package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.platform.perfetto.TraceColumn
import com.androidperformancestudio.platform.perfetto.TraceQuery
import com.androidperformancestudio.platform.perfetto.TraceQueryResult
import com.androidperformancestudio.platform.perfetto.TraceQuerySchema

data class ArtGcSummary(
    val processName: String?,
    val gcType: String,
    val count: Long,
    val totalDurationNs: Long,
    val maximumDurationNs: Long,
    val reclaimedMb: Double,
)

data class ArtAllocationChurn(
    val processName: String?,
    val allocatedMb: Double,
    val allocationRateMbPerSecond: Double,
    val heapSizeMb: Double,
    val heapUtilizationPercent: Double,
    val gcCpuRatePercent: Double,
)

data class ArtRuntimeOperation(
    val processName: String?,
    val threadName: String?,
    val name: String,
    val count: Long,
    val totalDurationNs: Long,
    val maximumDurationNs: Long,
)

data class ArtRuntimeAnalysis(
    val garbageCollections: List<ArtGcSummary>,
    val allocationChurn: List<ArtAllocationChurn>,
    val compilation: List<ArtRuntimeOperation>,
    val classLoadingAndInitialization: List<ArtRuntimeOperation>,
)

class ArtRuntimeTraceProcessorAdapter {
    private val processName = TraceColumn.string("process_name")
    private val gcType = TraceColumn.string("gc_type")
    private val count = TraceColumn.long("count")
    private val totalDurationNs = TraceColumn.long("total_duration_ns")
    private val maximumDurationNs = TraceColumn.long("maximum_duration_ns")
    private val reclaimedMb = TraceColumn.double("reclaimed_mb")
    private val allocatedMb = TraceColumn.double("allocated_mb")
    private val allocationRate = TraceColumn.double("allocation_rate_mb_s")
    private val heapSizeMb = TraceColumn.double("heap_size_mb")
    private val heapUtilization = TraceColumn.double("heap_utilization_percent")
    private val gcCpuRate = TraceColumn.double("gc_cpu_rate_percent")
    private val threadName = TraceColumn.string("thread_name")
    private val operationName = TraceColumn.string("operation")

    val gcQuery: TraceQuery<ArtGcSummary> =
        TraceQuery(
            """
            INCLUDE PERFETTO MODULE android.garbage_collection;

            SELECT process_name,
                   gc_type,
                   COUNT(*) AS count,
                   CAST(SUM(gc_dur) AS INTEGER) AS total_duration_ns,
                   CAST(MAX(gc_dur) AS INTEGER) AS maximum_duration_ns,
                   SUM(reclaimed_mb) AS reclaimed_mb
            FROM android_garbage_collection_events
            GROUP BY upid, process_name, gc_type
            ORDER BY total_duration_ns DESC
            """.trimIndent(),
            TraceQuerySchema.v57_2(processName, gcType, count, totalDurationNs, maximumDurationNs, reclaimedMb),
        ) { row ->
            ArtGcSummary(
                processName = row[processName],
                gcType = row[gcType].orEmpty(),
                count = row[count] ?: 0,
                totalDurationNs = row[totalDurationNs] ?: 0,
                maximumDurationNs = row[maximumDurationNs] ?: 0,
                reclaimedMb = row[reclaimedMb] ?: 0.0,
            )
        }

    val allocationChurnQuery: TraceQuery<ArtAllocationChurn> =
        TraceQuery(
            """
            INCLUDE PERFETTO MODULE android.garbage_collection;

            SELECT process.name AS process_name,
                   stats.heap_allocated_mb AS allocated_mb,
                   stats.heap_allocation_rate AS allocation_rate_mb_s,
                   stats.heap_size_mb AS heap_size_mb,
                   stats.heap_utilization * 100 AS heap_utilization_percent,
                   stats.gc_running_rate * 100 AS gc_cpu_rate_percent
            FROM _android_garbage_collection_process_stats AS stats
            LEFT JOIN process USING (upid)
            ORDER BY allocation_rate_mb_s DESC
            """.trimIndent(),
            TraceQuerySchema.v57_2(processName, allocatedMb, allocationRate, heapSizeMb, heapUtilization, gcCpuRate),
        ) { row ->
            ArtAllocationChurn(
                processName = row[processName],
                allocatedMb = row[allocatedMb] ?: 0.0,
                allocationRateMbPerSecond = row[allocationRate] ?: 0.0,
                heapSizeMb = row[heapSizeMb] ?: 0.0,
                heapUtilizationPercent = row[heapUtilization] ?: 0.0,
                gcCpuRatePercent = row[gcCpuRate] ?: 0.0,
            )
        }

    val compilationQuery: TraceQuery<ArtRuntimeOperation> =
        operationQuery("LOWER(slice.name) GLOB '*jit*compil*' OR LOWER(slice.name) GLOB '*dex2oat*'")

    val classLoadingQuery: TraceQuery<ArtRuntimeOperation> =
        operationQuery(
            "slice.name GLOB 'L*;' OR LOWER(slice.name) GLOB '*verifyclass*' " +
                "OR LOWER(slice.name) GLOB '*classlinker*' OR LOWER(slice.name) GLOB '*initializeclass*' " +
                "OR LOWER(slice.name) GLOB '*<clinit>*'",
        )

    fun mapFixture(
        gcCsv: String,
        allocationCsv: String,
        compilationCsv: String,
        classLoadingCsv: String,
    ): ArtRuntimeAnalysis =
        map(
            gcQuery.map(TraceQueryResult.parse(gcCsv)),
            allocationChurnQuery.map(TraceQueryResult.parse(allocationCsv)),
            compilationQuery.map(TraceQueryResult.parse(compilationCsv)),
            classLoadingQuery.map(TraceQueryResult.parse(classLoadingCsv)),
        )

    fun map(
        garbageCollections: List<ArtGcSummary>,
        allocationChurn: List<ArtAllocationChurn>,
        compilation: List<ArtRuntimeOperation>,
        classLoadingAndInitialization: List<ArtRuntimeOperation>,
    ): ArtRuntimeAnalysis =
        ArtRuntimeAnalysis(
            garbageCollections,
            allocationChurn,
            compilation,
            classLoadingAndInitialization,
        )

    private fun operationQuery(predicate: String): TraceQuery<ArtRuntimeOperation> =
        TraceQuery(
            """
            SELECT process.name AS process_name,
                   thread.name AS thread_name,
                   slice.name AS operation,
                   COUNT(*) AS count,
                   CAST(SUM(IIF(slice.dur = -1, trace_end() - slice.ts, slice.dur)) AS INTEGER) AS total_duration_ns,
                   CAST(MAX(IIF(slice.dur = -1, trace_end() - slice.ts, slice.dur)) AS INTEGER) AS maximum_duration_ns
            FROM slice
            JOIN thread_track ON thread_track.id = slice.track_id
            JOIN thread ON thread.utid = thread_track.utid
            LEFT JOIN process ON process.upid = thread.upid
            WHERE $predicate
            GROUP BY process.upid, thread.utid, slice.name
            ORDER BY total_duration_ns DESC
            """.trimIndent(),
            TraceQuerySchema.v57_2(processName, threadName, operationName, count, totalDurationNs, maximumDurationNs),
        ) { row ->
            ArtRuntimeOperation(
                processName = row[processName],
                threadName = row[threadName],
                name = row[operationName].orEmpty(),
                count = row[count] ?: 0,
                totalDurationNs = row[totalDurationNs] ?: 0,
                maximumDurationNs = row[maximumDurationNs] ?: 0,
            )
        }
}
