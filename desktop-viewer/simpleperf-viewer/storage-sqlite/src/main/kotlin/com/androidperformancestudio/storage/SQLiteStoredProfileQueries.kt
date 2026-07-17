package com.androidperformancestudio.storage

import com.androidperformancestudio.model.ProfileExecutionType
import com.androidperformancestudio.model.ProfileFrame
import java.sql.Connection
import java.sql.ResultSet

internal object SQLiteStoredProfileQueries {
    fun forEachSample(
        connection: Connection,
        action: (StoredProfileSample) -> Unit,
    ) {
        val sql = if (connection.isLegacySchema()) LEGACY_SAMPLE_ROWS_SQL else SAMPLE_ROWS_SQL
        val builder = StoredSampleBuilder(action)
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                while (result.next()) builder.add(result.storedSampleRow())
            }
        }
        builder.finish()
    }

    private const val SAMPLE_ROWS_SQL =
        "WITH RECURSIVE samples(" +
            "sample_id, timestamp_nanos, process_id, thread_id, thread_name, thread_key, callsite_id" +
            ") AS (" +
            "SELECT s.sample_id, s.timestamp_nanos, COALESCE(pp.process_id, t.process_id), " +
            "COALESCE(pt.thread_id, t.thread_id), COALESCE(pt.name, t.name), " +
            "CASE WHEN s.thread_row_id IS NULL THEN 'legacy:' || s.thread_id " +
            "ELSE 'canonical:' || s.thread_row_id END, s.leaf_callsite_id FROM sample s " +
            "LEFT JOIN profile_thread pt ON pt.thread_row_id=s.thread_row_id " +
            "LEFT JOIN profile_process pp ON pp.process_row_id=pt.process_row_id " +
            "LEFT JOIN thread t ON t.thread_id=s.thread_id" +
            "), stack(" +
            "sample_id, timestamp_nanos, process_id, thread_id, thread_name, thread_key, " +
            "callsite_id, depth, visited_callsite_ids" +
            ") AS (" +
            "SELECT sample_id, timestamp_nanos, process_id, thread_id, thread_name, thread_key, " +
            "callsite_id, 0, CASE WHEN callsite_id IS NULL THEN ',' ELSE ',' || callsite_id || ',' END " +
            "FROM samples UNION ALL " +
            "SELECT st.sample_id, st.timestamp_nanos, st.process_id, st.thread_id, st.thread_name, " +
            "st.thread_key, c.parent_id, st.depth + 1, st.visited_callsite_ids || c.parent_id || ',' " +
            "FROM stack st JOIN callsite c ON c.callsite_id=st.callsite_id " +
            "WHERE c.parent_id IS NOT NULL " +
            "AND instr(st.visited_callsite_ids, ',' || c.parent_id || ',') = 0" +
            ") SELECT st.sample_id, st.timestamp_nanos, st.process_id, st.thread_id, st.thread_name, " +
            "st.thread_key, f.virtual_address, fi.file_id, sy.source_symbol_id, fi.path, sy.name, " +
            "f.execution_type FROM stack st LEFT JOIN callsite c ON c.callsite_id=st.callsite_id " +
            "LEFT JOIN frame f ON f.frame_id=c.frame_id LEFT JOIN symbol sy ON sy.symbol_id=f.symbol_id " +
            "LEFT JOIN file fi ON fi.file_id=f.file_id " +
            "ORDER BY st.thread_key, st.timestamp_nanos, st.sample_id, st.depth DESC"

    private const val LEGACY_SAMPLE_ROWS_SQL =
        "WITH RECURSIVE samples(" +
            "sample_id, timestamp_nanos, process_id, thread_id, thread_name, thread_key, callsite_id" +
            ") AS (" +
            "SELECT s.sample_id, s.timestamp_nanos, t.process_id, t.thread_id, t.name, " +
            "'legacy:' || s.thread_id, s.leaf_callsite_id FROM sample s " +
            "JOIN thread t ON t.thread_id=s.thread_id" +
            "), stack(" +
            "sample_id, timestamp_nanos, process_id, thread_id, thread_name, thread_key, " +
            "callsite_id, depth, visited_callsite_ids" +
            ") AS (" +
            "SELECT sample_id, timestamp_nanos, process_id, thread_id, thread_name, thread_key, " +
            "callsite_id, 0, CASE WHEN callsite_id IS NULL THEN ',' ELSE ',' || callsite_id || ',' END " +
            "FROM samples UNION ALL " +
            "SELECT st.sample_id, st.timestamp_nanos, st.process_id, st.thread_id, st.thread_name, " +
            "st.thread_key, c.parent_id, st.depth + 1, st.visited_callsite_ids || c.parent_id || ',' " +
            "FROM stack st JOIN callsite c ON c.callsite_id=st.callsite_id " +
            "WHERE c.parent_id IS NOT NULL " +
            "AND instr(st.visited_callsite_ids, ',' || c.parent_id || ',') = 0" +
            ") SELECT st.sample_id, st.timestamp_nanos, st.process_id, st.thread_id, st.thread_name, " +
            "st.thread_key, f.virtual_address, fi.file_id, sy.source_symbol_id, fi.path, sy.name, " +
            "f.execution_type FROM stack st LEFT JOIN callsite c ON c.callsite_id=st.callsite_id " +
            "LEFT JOIN frame f ON f.frame_id=c.frame_id LEFT JOIN symbol sy ON sy.symbol_id=f.symbol_id " +
            "LEFT JOIN file fi ON fi.file_id=f.file_id " +
            "ORDER BY st.thread_key, st.timestamp_nanos, st.sample_id, st.depth DESC"
}

private data class StoredSampleRow(
    val sampleId: Long,
    val timestampNanos: Long,
    val thread: StoredProfileThread,
    val frame: ProfileFrame?,
)

private class StoredSampleBuilder(
    private val action: (StoredProfileSample) -> Unit,
) {
    private var sampleId: Long? = null
    private var timestampNanos = 0L
    private var thread: StoredProfileThread? = null
    private val frames = mutableListOf<ProfileFrame>()

    fun add(row: StoredSampleRow) {
        if (sampleId != null && sampleId != row.sampleId) flush()
        if (sampleId == null) {
            sampleId = row.sampleId
            timestampNanos = row.timestampNanos
            thread = row.thread
        }
        row.frame?.let(frames::add)
    }

    fun finish() = flush()

    private fun flush() {
        if (sampleId == null) return
        action(
            StoredProfileSample(
                thread = checkNotNull(thread),
                timestampNanos = timestampNanos,
                framesRootToLeaf = frames.toList(),
            ),
        )
        sampleId = null
        thread = null
        frames.clear()
    }
}

@Suppress("MagicNumber")
private fun ResultSet.storedSampleRow(): StoredSampleRow =
    StoredSampleRow(
        sampleId = getLong(1),
        timestampNanos = getLong(2),
        thread =
            StoredProfileThread(
                processId = getInt(3),
                threadId = getInt(4),
                name = getString(5),
                key = getString(6),
            ),
        frame = storedFrameOrNull(),
    )

@Suppress("MagicNumber")
private fun ResultSet.storedFrameOrNull(): ProfileFrame? {
    val virtualAddress = getLong(7)
    if (wasNull()) return null
    return ProfileFrame(
        virtualAddress = virtualAddress,
        fileId = getInt(8),
        symbolId = getInt(9),
        filePath = getString(10),
        symbolName = getString(11),
        executionType = getString(12).toExecutionType(),
    )
}

private fun String?.toExecutionType(): ProfileExecutionType =
    runCatching { ProfileExecutionType.valueOf(orEmpty()) }.getOrDefault(ProfileExecutionType.UNKNOWN)
