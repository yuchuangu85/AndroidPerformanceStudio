package com.androidperformancestudio.storage

import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.CallStackTable
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FrameImplementation
import com.androidperformancestudio.profileanalysis.WeightedCallStack
import java.sql.Connection
import java.sql.ResultSet

internal object SQLiteFlameGraphStackQueries {
    fun load(
        connection: Connection,
        query: ProfileQuery,
    ): CallStackTable {
        val filter = query.toSqlFilter("s", "e")
        val sql =
            (if (connection.isLegacySchema()) LEGACY_STACK_ROWS_SQL else STACK_ROWS_SQL)
                .replace("/*FILTER*/", filter.whereClause) +
                " ORDER BY st.sample_id, st.depth DESC"
        val builder = CallStackTableBuilder()
        connection.prepareStatement(sql).use { statement ->
            statement.bind(filter.parameters)
            statement.executeQuery().use { result ->
                while (result.next()) builder.add(result.stackRow())
            }
        }
        return builder.build()
    }

    private const val STACK_ROWS_SQL =
        "WITH RECURSIVE filtered_samples(" +
            "sample_id, timestamp_nanos, event_count, thread_key, category_name, subcategory_name, callsite_id" +
            ") AS (" +
            "SELECT s.sample_id, s.timestamp_nanos, s.event_count, " +
            "CASE WHEN s.thread_row_id IS NULL THEN 'legacy:' || s.thread_id " +
            "ELSE 'canonical:' || s.thread_row_id END, s.category_name, s.subcategory_name, s.leaf_callsite_id " +
            "FROM sample s JOIN event e ON e.event_id=s.event_id /*FILTER*/" +
            "), stack(" +
            "sample_id, timestamp_nanos, event_count, thread_key, category_name, subcategory_name, " +
            "callsite_id, depth" +
            ") AS (" +
            "SELECT sample_id, timestamp_nanos, event_count, thread_key, category_name, subcategory_name, " +
            "callsite_id, 0 FROM filtered_samples WHERE callsite_id IS NOT NULL UNION ALL " +
            "SELECT st.sample_id, st.timestamp_nanos, st.event_count, st.thread_key, st.category_name, " +
            "st.subcategory_name, c.parent_id, st.depth + 1 FROM stack st " +
            "JOIN callsite c ON c.callsite_id=st.callsite_id WHERE c.parent_id IS NOT NULL" +
            ") SELECT st.sample_id, st.timestamp_nanos, st.event_count, st.thread_key, st.category_name, " +
            "st.subcategory_name, st.callsite_id, c.frame_id, f.symbol_id, sy.name, fi.path, " +
            "f.virtual_address, f.execution_type, st.depth FROM stack st " +
            "JOIN callsite c ON c.callsite_id=st.callsite_id " +
            "JOIN frame f ON f.frame_id=c.frame_id JOIN symbol sy ON sy.symbol_id=f.symbol_id " +
            "JOIN file fi ON fi.file_id=f.file_id"

    private const val LEGACY_STACK_ROWS_SQL =
        "WITH RECURSIVE filtered_samples(" +
            "sample_id, timestamp_nanos, event_count, thread_key, category_name, subcategory_name, callsite_id" +
            ") AS (" +
            "SELECT s.sample_id, s.timestamp_nanos, s.event_count, 'legacy:' || s.thread_id, NULL, NULL, " +
            "s.leaf_callsite_id FROM sample s JOIN event e ON e.event_id=s.event_id /*FILTER*/" +
            "), stack(" +
            "sample_id, timestamp_nanos, event_count, thread_key, category_name, subcategory_name, " +
            "callsite_id, depth" +
            ") AS (" +
            "SELECT sample_id, timestamp_nanos, event_count, thread_key, category_name, subcategory_name, " +
            "callsite_id, 0 FROM filtered_samples WHERE callsite_id IS NOT NULL UNION ALL " +
            "SELECT st.sample_id, st.timestamp_nanos, st.event_count, st.thread_key, st.category_name, " +
            "st.subcategory_name, c.parent_id, st.depth + 1 FROM stack st " +
            "JOIN callsite c ON c.callsite_id=st.callsite_id WHERE c.parent_id IS NOT NULL" +
            ") SELECT st.sample_id, st.timestamp_nanos, st.event_count, st.thread_key, st.category_name, " +
            "st.subcategory_name, st.callsite_id, c.frame_id, f.symbol_id, sy.name, fi.path, " +
            "f.virtual_address, f.execution_type, st.depth FROM stack st " +
            "JOIN callsite c ON c.callsite_id=st.callsite_id " +
            "JOIN frame f ON f.frame_id=c.frame_id JOIN symbol sy ON sy.symbol_id=f.symbol_id " +
            "JOIN file fi ON fi.file_id=f.file_id"
}

@Suppress("LongParameterList")
private data class FlameStackRow(
    val sampleId: Long,
    val timestampNanos: Long,
    val weight: Long,
    val threadKey: String,
    val category: String?,
    val subcategory: String?,
    val callsiteId: Long,
    val frame: CallStackFrame,
    val depth: Int,
)

private class CallStackTableBuilder {
    private val framesById = linkedMapOf<Long, CallStackFrame>()
    private val stacks = mutableListOf<WeightedCallStack>()
    private var currentSampleId: Long? = null
    private var currentTimestampNanos = 0L
    private var currentWeight = 0L
    private var currentThreadKey = ""
    private var currentCategory: String? = null
    private var currentSubcategory: String? = null
    private val currentFrameIds = mutableListOf<Long>()

    fun add(row: FlameStackRow) {
        if (currentSampleId != null && row.sampleId != currentSampleId) flush()
        if (currentSampleId == null) start(row)
        framesById.putIfAbsent(row.frame.frameId, row.frame)
        currentFrameIds += row.frame.frameId
    }

    fun build(): CallStackTable {
        flush()
        return CallStackTable(framesById, stacks)
    }

    private fun start(row: FlameStackRow) {
        currentSampleId = row.sampleId
        currentTimestampNanos = row.timestampNanos
        currentWeight = row.weight
        currentThreadKey = row.threadKey
        currentCategory = row.category
        currentSubcategory = row.subcategory
    }

    private fun flush() {
        val sampleId = currentSampleId ?: return
        stacks +=
            WeightedCallStack(
                sampleId = sampleId,
                timestampNanos = currentTimestampNanos,
                weight = currentWeight,
                threadKey = currentThreadKey,
                category = currentCategory,
                subcategory = currentSubcategory,
                frameIdsRootToLeaf = currentFrameIds,
            )
        currentSampleId = null
        currentFrameIds.clear()
    }
}

@Suppress("MagicNumber")
private fun ResultSet.stackRow(): FlameStackRow =
    FlameStackRow(
        sampleId = getLong(1),
        timestampNanos = getLong(2),
        weight = getLong(3),
        threadKey = getString(4),
        category = getString(5),
        subcategory = getString(6),
        callsiteId = getLong(7),
        frame =
            CallStackFrame(
                frameId = getLong(8),
                functionId = FlameFunctionId(getLong(9)),
                symbolName = getString(10),
                resource = getString(11),
                virtualAddress = getLong(12),
                implementation = getString(13).toFrameImplementation(),
            ),
        depth = getInt(14),
    )

private fun String.toFrameImplementation(): FrameImplementation =
    when (this) {
        "NATIVE" -> FrameImplementation.NATIVE
        "INTERPRETED_JVM", "JIT_JVM", "ART" -> FrameImplementation.MANAGED
        "KERNEL" -> FrameImplementation.KERNEL
        else -> FrameImplementation.UNKNOWN
    }
