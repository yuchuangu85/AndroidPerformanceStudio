package com.androidperformancestudio.storage

import java.sql.Connection
import java.sql.PreparedStatement

@Suppress("MagicNumber")
internal object SQLiteProfileQueries {
    fun sampleCount(
        connection: Connection,
        query: ProfileQuery,
    ): Long {
        val filter = query.toSqlFilter("s", "e")
        return connection
            .prepareStatement(
                "SELECT COUNT(*) FROM sample s JOIN event e ON e.event_id=s.event_id ${filter.whereClause}",
            ).use { statement ->
                statement.bind(filter.parameters)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getLong(1)
                }
            }
    }

    fun threads(
        connection: Connection,
        query: ProfileQuery,
    ): List<ThreadSummary> {
        val filter = query.toSqlFilter("s", "e")
        if (connection.isLegacySchema()) {
            val sql =
                "SELECT t.process_id, t.thread_id, t.name, COUNT(*), SUM(s.event_count) FROM sample s " +
                    "JOIN thread t ON t.thread_id=s.thread_id JOIN event e ON e.event_id=s.event_id " +
                    "${filter.whereClause} GROUP BY t.process_id, t.thread_id, t.name " +
                    "ORDER BY SUM(s.event_count) DESC, t.thread_id"
            return connection.prepareStatement(sql).use { statement ->
                statement.bind(filter.parameters)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                ThreadSummary(
                                    result.getInt(1),
                                    result.getInt(2),
                                    result.getString(3),
                                    result.getLong(4),
                                    result.getLong(5),
                                ),
                            )
                        }
                    }
                }
            }
        }
        val threadKey =
            "CASE WHEN pt.thread_row_id IS NULL THEN 'legacy:' || s.thread_id " +
                "ELSE 'canonical:' || pt.thread_row_id END"
        val sql =
            "SELECT COALESCE(pp.process_id, t.process_id), COALESCE(pt.thread_id, t.thread_id), " +
                "COALESCE(pt.name, t.name), COUNT(*), SUM(s.event_count) FROM sample s " +
                "LEFT JOIN profile_thread pt ON pt.thread_row_id=s.thread_row_id " +
                "LEFT JOIN profile_process pp ON pp.process_row_id=pt.process_row_id " +
                "LEFT JOIN thread t ON t.thread_id=s.thread_id " +
                "JOIN event e ON e.event_id=s.event_id ${filter.whereClause} " +
                "GROUP BY $threadKey, COALESCE(pp.process_id, t.process_id), " +
                "COALESCE(pt.thread_id, t.thread_id), COALESCE(pt.name, t.name) " +
                "ORDER BY SUM(s.event_count) DESC, COALESCE(pt.thread_id, t.thread_id), $threadKey"
        return connection.prepareStatement(sql).use { statement ->
            statement.bind(filter.parameters)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            ThreadSummary(
                                result.getInt(1),
                                result.getInt(2),
                                result.getString(3),
                                result.getLong(4),
                                result.getLong(5),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun topFunctions(
        connection: Connection,
        query: ProfileQuery,
        options: TopFunctionOptions,
    ): List<TopFunction> {
        require(options.limit > 0) { "limit must be positive" }
        val filter = query.toSqlFilter("s", "e")
        val searchClause =
            if (options.search.isBlank()) {
                ""
            } else {
                "WHERE sy.name LIKE ? ESCAPE '\\' OR fi.path LIKE ? ESCAPE '\\'"
            }
        val direction = if (options.descending) "DESC" else "ASC"
        val order = options.sort.sqlColumn()
        val sql =
            (if (connection.isLegacySchema()) LEGACY_TOP_FUNCTIONS_SQL else TOP_FUNCTIONS_SQL)
                .replace("/*FILTER*/", filter.whereClause) +
                " $searchClause ORDER BY $order $direction, sy.name ASC, fi.path ASC, " +
                "i.weight DESC, COALESCE(x.weight, 0) DESC, i.samples DESC, i.threads DESC LIMIT ?"
        val parameters = filter.parameters.toMutableList()
        if (options.search.isNotBlank()) {
            val pattern = "%${options.search.escapeLike()}%"
            parameters += pattern
            parameters += pattern
        }
        parameters += options.limit
        return connection.prepareStatement(sql).use { statement ->
            statement.bind(parameters)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            TopFunction(
                                symbolName = result.getString(1),
                                filePath = result.getString(2),
                                inclusiveWeight = result.getLong(3),
                                exclusiveWeight = result.getLong(4),
                                sampleCount = result.getLong(5),
                                threadCount = result.getLong(6),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun overview(
        connection: Connection,
        query: ProfileQuery,
    ): ProfileOverview {
        val filter = query.toSqlFilter("s", "e")
        if (connection.isLegacySchema()) {
            val sql =
                "SELECT MIN(s.timestamp_nanos), MAX(s.timestamp_nanos), COUNT(*), " +
                    "COALESCE(SUM(s.event_count), 0), COUNT(DISTINCT s.process_id), COUNT(DISTINCT s.thread_id) " +
                    "FROM sample s JOIN event e ON e.event_id=s.event_id ${filter.whereClause}"
            return connection.prepareStatement(sql).use { statement ->
                statement.bind(filter.parameters)
                statement.executeQuery().use { result ->
                    check(result.next())
                    ProfileOverview(
                        startNanos = result.getNullableLong(1),
                        endNanosInclusive = result.getNullableLong(2),
                        sampleCount = result.getLong(3),
                        totalEventWeight = result.getLong(4),
                        processCount = result.getLong(5),
                        threadCount = result.getLong(6),
                        eventTypes = eventTypes(connection, query),
                    )
                }
            }
        }
        val sql =
            "SELECT MIN(s.timestamp_nanos), MAX(s.timestamp_nanos), COUNT(*), " +
                "COALESCE(SUM(s.event_count), 0), " +
                "COUNT(DISTINCT CASE WHEN s.process_row_id IS NULL THEN 'legacy:' || s.process_id " +
                "ELSE 'canonical:' || s.process_row_id END), " +
                "COUNT(DISTINCT CASE WHEN s.thread_row_id IS NULL THEN 'legacy:' || s.thread_id " +
                "ELSE 'canonical:' || s.thread_row_id END) FROM sample s " +
                "JOIN event e ON e.event_id=s.event_id ${filter.whereClause}"
        return connection.prepareStatement(sql).use { statement ->
            statement.bind(filter.parameters)
            statement.executeQuery().use { result ->
                check(result.next())
                ProfileOverview(
                    startNanos = result.getNullableLong(1),
                    endNanosInclusive = result.getNullableLong(2),
                    sampleCount = result.getLong(3),
                    totalEventWeight = result.getLong(4),
                    processCount = result.getLong(5),
                    threadCount = result.getLong(6),
                    eventTypes = eventTypes(connection, query),
                )
            }
        }
    }

    fun timelineBuckets(
        connection: Connection,
        query: ProfileQuery,
        bucketCount: Int,
    ): List<TimelineBucket> {
        require(bucketCount > 0) { "bucketCount must be positive" }
        val bounds = query.timelineBounds(connection) ?: return emptyList()
        val duration = bounds.second - bounds.first
        val boundedQuery =
            query.copy(startNanosInclusive = bounds.first, endNanosExclusive = bounds.second)
        val filter = boundedQuery.toSqlFilter("s", "e")
        val values = Array(bucketCount) { 0L to 0L }
        val sql =
            "SELECT MIN(((s.timestamp_nanos - ?) * ? / ?), ?), COUNT(*), SUM(s.event_count) " +
                "FROM sample s JOIN event e ON e.event_id=s.event_id ${filter.whereClause} GROUP BY 1"
        connection.prepareStatement(sql).use { statement ->
            statement.bind(listOf(bounds.first, bucketCount, duration, bucketCount - 1) + filter.parameters)
            statement.executeQuery().use { result ->
                while (result.next()) values[result.getInt(1)] = result.getLong(2) to result.getLong(3)
            }
        }
        return List(bucketCount) { index ->
            val start = bounds.first + duration * index / bucketCount
            val end = bounds.first + duration * (index + 1) / bucketCount
            TimelineBucket(start, end, values[index].first, values[index].second)
        }
    }

    fun dataQuality(connection: Connection): DataQualitySummary {
        val sample =
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT COUNT(*), COALESCE(SUM(unwind_error_code IS NOT NULL), 0), " +
                            "COALESCE(SUM(has_unknown_symbol), 0), COALESCE(SUM(empty_stack), 0) FROM sample",
                    ).use { result ->
                        check(result.next())
                        listOf(result.getLong(1), result.getLong(2), result.getLong(3), result.getLong(4))
                    }
            }
        val lost =
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT COALESCE(SUM(sample_count), 0), COALESCE(SUM(lost_count), 0) FROM lost_situation",
                    ).use { result ->
                        check(result.next())
                        result.getLong(1) to result.getLong(2)
                    }
            }
        return DataQualitySummary(
            sampleCount = sample[0],
            reportedSampleCount = lost.first,
            lostSampleCount = lost.second,
            unwindErrorSamples = sample[1],
            unknownSymbolSamples = sample[2],
            emptyStackSamples = sample[3],
            unknownRecords = connection.singleLong("SELECT COUNT(*) FROM unknown_record"),
            unwindErrors = unwindErrors(connection),
        )
    }

    private fun unwindErrors(connection: Connection): List<UnwindErrorSummary> =
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    "SELECT unwind_error_code, unwind_raw_code, unwind_address, COUNT(*) FROM sample " +
                        "WHERE unwind_error_code IS NOT NULL " +
                        "GROUP BY unwind_error_code, unwind_raw_code, unwind_address ORDER BY COUNT(*) DESC",
                ).use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                UnwindErrorSummary(
                                    result.getString(1),
                                    result.getInt(2),
                                    result.getLong(3),
                                    result.getLong(4),
                                ),
                            )
                        }
                    }
                }
        }

    private const val TOP_FUNCTIONS_SQL =
        "WITH RECURSIVE filtered_samples(sample_id, event_count, thread_key, callsite_id) AS (" +
            "SELECT s.sample_id, s.event_count, " +
            "CASE WHEN s.thread_row_id IS NULL THEN 'legacy:' || s.thread_id " +
            "ELSE 'canonical:' || s.thread_row_id END, s.leaf_callsite_id " +
            "FROM sample s JOIN event e ON e.event_id=s.event_id /*FILTER*/" +
            "), stack(sample_id, event_count, thread_key, callsite_id) AS (" +
            "SELECT sample_id, event_count, thread_key, callsite_id FROM filtered_samples " +
            "WHERE callsite_id IS NOT NULL UNION ALL " +
            "SELECT st.sample_id, st.event_count, st.thread_key, c.parent_id FROM stack st " +
            "JOIN callsite c ON c.callsite_id=st.callsite_id WHERE c.parent_id IS NOT NULL" +
            "), inclusive AS (" +
            "SELECT c.frame_id, SUM(st.event_count) weight, COUNT(DISTINCT st.sample_id) samples, " +
            "COUNT(DISTINCT st.thread_key) threads FROM stack st " +
            "JOIN callsite c ON c.callsite_id=st.callsite_id GROUP BY c.frame_id" +
            "), exclusive AS (" +
            "SELECT c.frame_id, SUM(fs.event_count) weight FROM filtered_samples fs " +
            "JOIN callsite c ON c.callsite_id=fs.callsite_id GROUP BY c.frame_id" +
            ") SELECT sy.name, fi.path, i.weight, COALESCE(x.weight, 0), i.samples, i.threads " +
            "FROM inclusive i JOIN frame f ON f.frame_id=i.frame_id " +
            "JOIN symbol sy ON sy.symbol_id=f.symbol_id JOIN file fi ON fi.file_id=f.file_id " +
            "LEFT JOIN exclusive x ON x.frame_id=i.frame_id"

    private const val LEGACY_TOP_FUNCTIONS_SQL =
        "WITH RECURSIVE filtered_samples(sample_id, event_count, thread_key, callsite_id) AS (" +
            "SELECT s.sample_id, s.event_count, 'legacy:' || s.thread_id, s.leaf_callsite_id " +
            "FROM sample s JOIN event e ON e.event_id=s.event_id /*FILTER*/" +
            "), stack(sample_id, event_count, thread_key, callsite_id) AS (" +
            "SELECT sample_id, event_count, thread_key, callsite_id FROM filtered_samples " +
            "WHERE callsite_id IS NOT NULL UNION ALL " +
            "SELECT st.sample_id, st.event_count, st.thread_key, c.parent_id FROM stack st " +
            "JOIN callsite c ON c.callsite_id=st.callsite_id WHERE c.parent_id IS NOT NULL" +
            "), inclusive AS (" +
            "SELECT c.frame_id, SUM(st.event_count) weight, COUNT(DISTINCT st.sample_id) samples, " +
            "COUNT(DISTINCT st.thread_key) threads FROM stack st " +
            "JOIN callsite c ON c.callsite_id=st.callsite_id GROUP BY c.frame_id" +
            "), exclusive AS (" +
            "SELECT c.frame_id, SUM(fs.event_count) weight FROM filtered_samples fs " +
            "JOIN callsite c ON c.callsite_id=fs.callsite_id GROUP BY c.frame_id" +
            ") SELECT sy.name, fi.path, i.weight, COALESCE(x.weight, 0), i.samples, i.threads " +
            "FROM inclusive i JOIN frame f ON f.frame_id=i.frame_id " +
            "JOIN symbol sy ON sy.symbol_id=f.symbol_id JOIN file fi ON fi.file_id=f.file_id " +
            "LEFT JOIN exclusive x ON x.frame_id=i.frame_id"
}

internal fun Connection.isLegacySchema(): Boolean = singleInt("PRAGMA user_version") < 2

internal data class SqlFilter(
    val whereClause: String,
    val parameters: List<Any>,
)

internal fun ProfileQuery.toSqlFilter(
    sampleAlias: String,
    eventAlias: String,
): SqlFilter {
    val predicates = mutableListOf<String>()
    val parameters = mutableListOf<Any>()
    startNanosInclusive?.let {
        predicates += "$sampleAlias.timestamp_nanos >= ?"
        parameters += it
    }
    endNanosExclusive?.let {
        predicates += "$sampleAlias.timestamp_nanos < ?"
        parameters += it
    }
    if (threadIds.isNotEmpty()) {
        predicates += "$sampleAlias.thread_id IN (${threadIds.joinToString { "?" }})"
        parameters.addAll(threadIds.sorted())
    }
    if (eventTypes.isNotEmpty()) {
        predicates += "$eventAlias.name IN (${eventTypes.joinToString { "?" }})"
        parameters.addAll(eventTypes.sorted())
    }
    return SqlFilter(
        whereClause = if (predicates.isEmpty()) "" else "WHERE ${predicates.joinToString(" AND ")}",
        parameters = parameters,
    )
}

internal fun PreparedStatement.bind(parameters: List<Any>) {
    parameters.forEachIndexed { index, value -> setObject(index + 1, value) }
}

private fun TopFunctionSort.sqlColumn(): String =
    when (this) {
        TopFunctionSort.INCLUSIVE_WEIGHT -> "i.weight"
        TopFunctionSort.EXCLUSIVE_WEIGHT -> "COALESCE(x.weight, 0)"
        TopFunctionSort.SAMPLE_COUNT -> "i.samples"
        TopFunctionSort.THREAD_COUNT -> "i.threads"
        TopFunctionSort.SYMBOL_NAME -> "sy.name"
        TopFunctionSort.FILE_PATH -> "fi.path"
    }

private fun String.escapeLike(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

private fun java.sql.ResultSet.getNullableLong(index: Int): Long? {
    val value = getLong(index)
    return if (wasNull()) null else value
}

private fun eventTypes(
    connection: Connection,
    query: ProfileQuery,
): List<String> {
    val filter = query.toSqlFilter("s", "e")
    return connection
        .prepareStatement(
            "SELECT DISTINCT e.name FROM sample s JOIN event e ON e.event_id=s.event_id " +
                "${filter.whereClause} ORDER BY e.name",
        ).use { statement ->
            statement.bind(filter.parameters)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.getString(1))
                }
            }
        }
}

private fun ProfileQuery.timelineBounds(connection: Connection): Pair<Long, Long>? {
    val overview = SQLiteProfileQueries.overview(connection, this)
    val start = startNanosInclusive ?: overview.startNanos
    val end = endNanosExclusive ?: overview.endNanosInclusive?.safeIncrement()
    return if (start == null || end == null) {
        null
    } else if (end > start) {
        start to end
    } else {
        start to start.safeIncrement()
    }
}

private fun Long.safeIncrement(): Long = if (this == Long.MAX_VALUE) this else this + 1
