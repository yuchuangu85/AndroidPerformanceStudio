package com.androidperformancestudio.storage

import java.sql.Connection

@Suppress("MagicNumber")
internal object SQLiteCallTreeQueries {
    fun aggregate(
        connection: Connection,
        query: ProfileQuery,
        direction: CallTreeDirection,
    ): List<CallTreeNode> {
        val filter = query.toSqlFilter("s", "e")
        val ordering = if (direction == CallTreeDirection.FORWARD) "DESC" else "ASC"
        val sql =
            STACK_ROWS_SQL.replace("/*FILTER*/", filter.whereClause) +
                " ORDER BY st.sample_id, st.depth $ordering"
        val builder = CallTreeBuilder()
        connection.prepareStatement(sql).use { statement ->
            statement.bind(filter.parameters)
            statement.executeQuery().use { result ->
                while (result.next()) {
                    builder.add(
                        StackRow(
                            sampleId = result.getLong(1),
                            eventWeight = result.getLong(2),
                            threadKey = result.getString(3),
                            originalDepth = result.getInt(4),
                            frameId = result.getLong(5),
                            symbolName = result.getString(6),
                            filePath = result.getString(7),
                        ),
                    )
                }
            }
        }
        return builder.build()
    }

    private const val STACK_ROWS_SQL =
        "WITH RECURSIVE filtered_samples(sample_id, event_count, thread_key, callsite_id) AS (" +
            "SELECT s.sample_id, s.event_count, " +
            "CASE WHEN s.thread_row_id IS NULL THEN 'legacy:' || s.thread_id " +
            "ELSE 'canonical:' || s.thread_row_id END, s.leaf_callsite_id " +
            "FROM sample s JOIN event e ON e.event_id=s.event_id /*FILTER*/" +
            "), stack(sample_id, event_count, thread_key, callsite_id, depth) AS (" +
            "SELECT sample_id, event_count, thread_key, callsite_id, 0 FROM filtered_samples " +
            "WHERE callsite_id IS NOT NULL UNION ALL " +
            "SELECT st.sample_id, st.event_count, st.thread_key, c.parent_id, st.depth + 1 FROM stack st " +
            "JOIN callsite c ON c.callsite_id=st.callsite_id WHERE c.parent_id IS NOT NULL" +
            ") SELECT st.sample_id, st.event_count, st.thread_key, st.depth, c.frame_id, sy.name, fi.path " +
            "FROM stack st JOIN callsite c ON c.callsite_id=st.callsite_id " +
            "JOIN frame f ON f.frame_id=c.frame_id JOIN symbol sy ON sy.symbol_id=f.symbol_id " +
            "JOIN file fi ON fi.file_id=f.file_id"
}

private data class StackRow(
    val sampleId: Long,
    val eventWeight: Long,
    val threadKey: String,
    val originalDepth: Int,
    val frameId: Long,
    val symbolName: String,
    val filePath: String,
)

private data class TreeKey(
    val parentId: Long?,
    val frameId: Long,
)

private class MutableCallTreeNode(
    val id: Long,
    val parentId: Long?,
    val depth: Int,
    val symbolName: String,
    val filePath: String,
) {
    var inclusiveWeight: Long = 0
    var exclusiveWeight: Long = 0
    var sampleCount: Long = 0
    val threadKeys = mutableSetOf<String>()

    fun freeze(): CallTreeNode =
        CallTreeNode(
            id,
            parentId,
            depth,
            symbolName,
            filePath,
            inclusiveWeight,
            exclusiveWeight,
            sampleCount,
            threadKeys.size.toLong(),
        )
}

private class CallTreeBuilder {
    private val nodesByKey = linkedMapOf<TreeKey, MutableCallTreeNode>()
    private var nextId = 1L
    private var currentSampleId = Long.MIN_VALUE
    private var currentParentId: Long? = null

    fun add(row: StackRow) {
        if (row.sampleId != currentSampleId) {
            currentSampleId = row.sampleId
            currentParentId = null
        }
        val key = TreeKey(currentParentId, row.frameId)
        val node =
            nodesByKey.getOrPut(key) {
                MutableCallTreeNode(
                    id = nextId++,
                    parentId = currentParentId,
                    depth = currentParentId?.let(::parentDepth)?.plus(1) ?: 0,
                    symbolName = row.symbolName,
                    filePath = row.filePath,
                )
            }
        node.inclusiveWeight += row.eventWeight
        if (row.originalDepth == 0) node.exclusiveWeight += row.eventWeight
        node.sampleCount++
        node.threadKeys += row.threadKey
        currentParentId = node.id
    }

    fun build(): List<CallTreeNode> = nodesByKey.values.map(MutableCallTreeNode::freeze)

    private fun parentDepth(parentId: Long): Int = nodesByKey.values.first { it.id == parentId }.depth
}
