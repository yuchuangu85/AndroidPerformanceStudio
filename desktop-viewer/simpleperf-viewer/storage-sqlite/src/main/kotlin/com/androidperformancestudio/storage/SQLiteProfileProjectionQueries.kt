@file:Suppress("MagicNumber", "TooManyFunctions")

package com.androidperformancestudio.storage

import java.sql.Connection
import java.sql.ResultSet

internal object SQLiteProfileProjectionQueries {
    fun project(
        store: SQLiteSampleStore,
        query: ProfileQuery,
    ): ProfileProjectionSnapshot = project(store, ProfileProjectionRequest(query = query))

    fun project(
        store: SQLiteSampleStore,
        request: ProfileProjectionRequest,
    ): ProfileProjectionSnapshot {
        val frozenQuery = request.query.freeze()
        return store.readTransaction {
            val overview = overview(frozenQuery)
            val sessionOverview = overview()
            val quality = dataQuality()
            val forwardCallTree = callTree(frozenQuery, CallTreeDirection.FORWARD).sortedCallTree()
            ProfileProjectionSnapshot(
                query = frozenQuery,
                overview = overview.copy(eventTypes = overview.eventTypes.sorted()),
                quality = quality.sorted(),
                tracks = coreTracks(connection, frozenQuery),
                threads = threads(frozenQuery).sortedThreads(),
                timeline = timelineBuckets(frozenQuery, request.timelineBucketCount).sortedTimeline(),
                topFunctions =
                    topFunctions(
                        query = frozenQuery,
                        limit = request.topFunctionLimit,
                        search = request.topSearch,
                        sort = request.topSort,
                        descending = request.topDescending,
                    ),
                forwardCallTree = forwardCallTree,
                sessionOverview = sessionOverview.copy(eventTypes = sessionOverview.eventTypes.sorted()),
                sessionThreads = threads().sortedThreads(),
                callTree =
                    if (request.callTreeDirection == CallTreeDirection.FORWARD) {
                        forwardCallTree
                    } else {
                        callTree(frozenQuery, CallTreeDirection.REVERSE).sortedCallTree()
                    },
            )
        }
    }

    fun coreTracks(
        connection: Connection,
        query: ProfileQuery,
    ): List<ProfileTrackSnapshot> =
        buildList {
            val cpuTracks = cpuTracks(connection, query)
            addAll(cpuTracks.map(CpuTrack::snapshot))
            addAll(contextSwitchTracks(connection, query, cpuTracks))
            addAll(threadFactTracks(connection, query, ThreadFactKind.MARKER))
            addAll(globalFactTracks(connection, query, GlobalFactKind.COUNTER))
            addAll(threadFactTracks(connection, query, ThreadFactKind.SLICE))
            addAll(globalFactTracks(connection, query, GlobalFactKind.SCREENSHOT))
        }.sortedWith(compareBy<ProfileTrackSnapshot>({ it.kind.ordinal }, ProfileTrackSnapshot::id))

    private fun cpuTracks(
        connection: Connection,
        query: ProfileQuery,
    ): List<CpuTrack> {
        val baseFilter = threadFilter(query, "s.thread_id")
        val base =
            connection.prepareStatement(CPU_TRACKS_SQL.replace("/*FILTER*/", baseFilter.whereClause)).use { statement ->
                statement.bind(baseFilter.parameters)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(result.cpuTrack())
                    }
                }
            }
        val filtered = sampleBounds(connection, query)
        return base.map { track ->
            val bounds = filtered[track.key]
            track.copy(
                availability =
                    if (bounds == null) ProfileDataAvailability.EMPTY else ProfileDataAvailability.AVAILABLE,
                startNanos = bounds?.first,
                endNanosExclusive = bounds?.second,
            )
        }
    }

    private fun sampleBounds(
        connection: Connection,
        query: ProfileQuery,
    ): Map<String, Pair<Long, Long>> {
        val filter = query.toSqlFilter("s", "e")
        return connection
            .prepareStatement(CPU_BOUNDS_SQL.replace("/*FILTER*/", filter.whereClause))
            .use { statement ->
                statement.bind(filter.parameters)
                statement.executeQuery().use { result ->
                    buildMap {
                        while (result.next()) {
                            put(result.getString(1), result.getLong(2) to result.getLong(3).exclusiveEnd())
                        }
                    }
                }
            }
    }

    private fun contextSwitchTracks(
        connection: Connection,
        query: ProfileQuery,
        cpuTracks: List<CpuTrack>,
    ): List<ProfileTrackSnapshot> {
        val legacyTracks = cpuTracks.filter { it.sourceId == null }
        if (legacyTracks.isEmpty()) return emptyList()
        val collectionRequested =
            connection.singleLong(
                "SELECT COALESCE(MAX(trace_off_cpu), 0) FROM profile_metadata",
            ) != 0L
        val collectedThreadIds = contextSwitchThreadIds(connection)
        val bounds = contextSwitchBounds(connection, query)
        return legacyTracks.map { track ->
            val trackBounds = bounds[track.threadId]
            val collected = collectionRequested || track.threadId in collectedThreadIds
            ProfileTrackSnapshot(
                id = "context-switches:legacy:${track.processId}:${track.threadId}",
                kind = ProfileTrackKind.CONTEXT_SWITCHES,
                processId = track.processId,
                threadId = track.threadId,
                availability =
                    when {
                        !collected -> ProfileDataAvailability.NOT_COLLECTED
                        trackBounds == null -> ProfileDataAvailability.EMPTY
                        else -> ProfileDataAvailability.AVAILABLE
                    },
                startNanos = trackBounds?.first,
                endNanosExclusive = trackBounds?.second,
            )
        }
    }

    private fun contextSwitchThreadIds(connection: Connection): Set<Int> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT DISTINCT thread_id FROM context_switch").use { result ->
                buildSet {
                    while (result.next()) add(result.getInt(1))
                }
            }
        }

    private fun contextSwitchBounds(
        connection: Connection,
        query: ProfileQuery,
    ): Map<Int, Pair<Long, Long>> {
        val filter = timestampFilter(query, "cs.timestamp_nanos", "cs.thread_id")
        val sql =
            "SELECT cs.thread_id, MIN(cs.timestamp_nanos), MAX(cs.timestamp_nanos) FROM context_switch cs " +
                "${filter.whereClause} GROUP BY cs.thread_id"
        return connection.prepareStatement(sql).use { statement ->
            statement.bind(filter.parameters)
            statement.executeQuery().use { result ->
                buildMap {
                    while (result.next()) {
                        put(result.getInt(1), result.getLong(2) to result.getLong(3).exclusiveEnd())
                    }
                }
            }
        }
    }

    private fun threadFactTracks(
        connection: Connection,
        query: ProfileQuery,
        kind: ThreadFactKind,
    ): List<ProfileTrackSnapshot> {
        val baseFilter = threadFilter(query, "pt.thread_id")
        val baseSql =
            "SELECT f.source_id, pp.process_id, pt.thread_id, f.thread_row_id FROM ${kind.table} f " +
                "LEFT JOIN profile_thread pt ON pt.thread_row_id=f.thread_row_id " +
                "LEFT JOIN profile_process pp ON pp.process_row_id=pt.process_row_id " +
                "${baseFilter.whereClause} GROUP BY f.source_id, pp.process_id, pt.thread_id, f.thread_row_id"
        val base =
            connection.prepareStatement(baseSql).use { statement ->
                statement.bind(baseFilter.parameters)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                FactTrack(
                                    key = factKey(result.getString(1), result.getNullableLong(4)),
                                    sourceId = result.getString(1),
                                    processId = result.getNullableInt(2),
                                    threadId = result.getNullableInt(3),
                                ),
                            )
                        }
                    }
                }
            }
        val bounds = threadFactBounds(connection, query, kind)
        return base.map { track ->
            val trackBounds = bounds[track.key]
            ProfileTrackSnapshot(
                id = track.id(kind.idPrefix),
                kind = kind.trackKind,
                processId = track.processId,
                threadId = track.threadId,
                availability = trackBounds.availability(),
                startNanos = trackBounds?.first,
                endNanosExclusive = trackBounds?.second,
            )
        }
    }

    private fun threadFactBounds(
        connection: Connection,
        query: ProfileQuery,
        kind: ThreadFactKind,
    ): Map<String, Pair<Long, Long>> {
        val filter = factFilter(query, kind)
        val sql =
            "SELECT f.source_id, f.thread_row_id, MIN(f.start_nanos), " +
                "MAX(${kind.exclusiveEndSql}) FROM ${kind.table} f " +
                "LEFT JOIN profile_thread pt ON pt.thread_row_id=f.thread_row_id " +
                "${filter.whereClause} GROUP BY f.source_id, f.thread_row_id"
        return connection.prepareStatement(sql).use { statement ->
            statement.bind(filter.parameters)
            statement.executeQuery().use { result ->
                buildMap {
                    while (result.next()) {
                        val start = result.getLong(3)
                        val end = result.getLong(4)
                        put(factKey(result.getString(1), result.getNullableLong(2)), start to end)
                    }
                }
            }
        }
    }

    private fun globalFactTracks(
        connection: Connection,
        query: ProfileQuery,
        kind: GlobalFactKind,
    ): List<ProfileTrackSnapshot> {
        val sources =
            connection.createStatement().use { statement ->
                statement
                    .executeQuery("SELECT DISTINCT source_id FROM ${kind.table} ORDER BY source_id")
                    .use { result ->
                        buildList {
                            while (result.next()) add(result.getString(1))
                        }
                    }
            }
        val filter = timestampFilter(query, "timestamp_nanos", null)
        val boundsSql =
            "SELECT source_id, MIN(timestamp_nanos), MAX(timestamp_nanos) FROM ${kind.table} " +
                "${filter.whereClause} GROUP BY source_id"
        val bounds =
            connection.prepareStatement(boundsSql).use { statement ->
                statement.bind(filter.parameters)
                statement.executeQuery().use { result ->
                    buildMap {
                        while (result.next()) {
                            put(result.getString(1), result.getLong(2) to result.getLong(3).exclusiveEnd())
                        }
                    }
                }
            }
        return sources.map { sourceId ->
            val trackBounds = bounds[sourceId]
            ProfileTrackSnapshot(
                id = "${kind.idPrefix}:$sourceId",
                kind = kind.trackKind,
                processId = null,
                threadId = null,
                availability = trackBounds.availability(),
                startNanos = trackBounds?.first,
                endNanosExclusive = trackBounds?.second,
            )
        }
    }

    private fun threadFilter(
        query: ProfileQuery,
        column: String,
    ): SqlFilter {
        if (query.threadIds.isEmpty()) return SqlFilter("", emptyList())
        return SqlFilter(
            "WHERE $column IN (${query.threadIds.joinToString { "?" }})",
            query.threadIds.sorted(),
        )
    }

    private fun timestampFilter(
        query: ProfileQuery,
        timestampColumn: String,
        threadColumn: String?,
    ): SqlFilter {
        val predicates = mutableListOf<String>()
        val parameters = mutableListOf<Any>()
        query.startNanosInclusive?.let {
            predicates += "$timestampColumn >= ?"
            parameters += it
        }
        query.endNanosExclusive?.let {
            predicates += "$timestampColumn < ?"
            parameters += it
        }
        if (threadColumn != null && query.threadIds.isNotEmpty()) {
            predicates += "$threadColumn IN (${query.threadIds.joinToString { "?" }})"
            parameters.addAll(query.threadIds.sorted())
        }
        return SqlFilter(
            if (predicates.isEmpty()) "" else "WHERE ${predicates.joinToString(" AND ")}",
            parameters,
        )
    }

    private fun factFilter(
        query: ProfileQuery,
        kind: ThreadFactKind,
    ): SqlFilter {
        val predicates = mutableListOf<String>()
        val parameters = mutableListOf<Any>()
        query.startNanosInclusive?.let { start ->
            predicates += kind.lowerBoundSql
            repeat(kind.lowerBoundParameterCount) { parameters += start }
        }
        query.endNanosExclusive?.let { end ->
            predicates += "f.start_nanos < ?"
            parameters += end
        }
        if (query.threadIds.isNotEmpty()) {
            predicates += "pt.thread_id IN (${query.threadIds.joinToString { "?" }})"
            parameters.addAll(query.threadIds.sorted())
        }
        return SqlFilter(
            if (predicates.isEmpty()) "" else "WHERE ${predicates.joinToString(" AND ")}",
            parameters,
        )
    }

    private const val CPU_TRACK_KEY =
        "CASE WHEN s.thread_row_id IS NULL THEN 'legacy:' || s.thread_id " +
            "ELSE 'canonical:' || s.thread_row_id END"

    private const val CPU_TRACKS_SQL =
        "SELECT $CPU_TRACK_KEY, s.source_id, COALESCE(pp.process_id, t.process_id), " +
            "COALESCE(pt.thread_id, t.thread_id) FROM sample s " +
            "LEFT JOIN profile_thread pt ON pt.thread_row_id=s.thread_row_id " +
            "LEFT JOIN profile_process pp ON pp.process_row_id=s.process_row_id " +
            "LEFT JOIN thread t ON t.thread_id=s.thread_id /*FILTER*/ " +
            "GROUP BY $CPU_TRACK_KEY, s.source_id, COALESCE(pp.process_id, t.process_id), " +
            "COALESCE(pt.thread_id, t.thread_id)"

    private const val CPU_BOUNDS_SQL =
        "SELECT $CPU_TRACK_KEY, MIN(s.timestamp_nanos), MAX(s.timestamp_nanos) FROM sample s " +
            "JOIN event e ON e.event_id=s.event_id /*FILTER*/ GROUP BY $CPU_TRACK_KEY"
}

private inline fun <T> SQLiteSampleStore.readTransaction(block: SQLiteSampleStore.() -> T): T {
    check(connection.autoCommit) { "A record import is active" }
    connection.autoCommit = false
    try {
        return block()
    } finally {
        try {
            connection.rollback()
        } finally {
            connection.autoCommit = true
        }
    }
}

private fun DataQualitySummary.sorted(): DataQualitySummary =
    copy(
        unwindErrors =
            unwindErrors.sortedWith(
                compareByDescending<UnwindErrorSummary> { it.sampleCount }
                    .thenBy(UnwindErrorSummary::code)
                    .thenBy(UnwindErrorSummary::rawCode)
                    .thenBy(UnwindErrorSummary::address),
            ),
    )

private fun List<ThreadSummary>.sortedThreads(): List<ThreadSummary> =
    sortedWith(
        compareByDescending<ThreadSummary> { it.totalEventCount }
            .thenBy(ThreadSummary::threadId)
            .thenBy(ThreadSummary::processId)
            .thenBy(ThreadSummary::name)
            .thenByDescending(ThreadSummary::sampleCount),
    )

private fun List<TimelineBucket>.sortedTimeline(): List<TimelineBucket> =
    sortedWith(
        compareBy(
            TimelineBucket::startNanos,
            TimelineBucket::endNanosExclusive,
            TimelineBucket::sampleCount,
            TimelineBucket::eventWeight,
        ),
    )

private fun List<CallTreeNode>.sortedCallTree(): List<CallTreeNode> =
    sortedWith(
        compareBy(
            CallTreeNode::id,
            { it.parentId ?: Long.MIN_VALUE },
            CallTreeNode::depth,
            CallTreeNode::symbolName,
            CallTreeNode::filePath,
        ),
    )

private data class CpuTrack(
    val key: String,
    val sourceId: String?,
    val processId: Int,
    val threadId: Int,
    val availability: ProfileDataAvailability = ProfileDataAvailability.EMPTY,
    val startNanos: Long? = null,
    val endNanosExclusive: Long? = null,
) {
    fun snapshot(): ProfileTrackSnapshot =
        ProfileTrackSnapshot(
            id =
                if (sourceId == null) {
                    "cpu-samples:legacy:$processId:$threadId"
                } else {
                    "cpu-samples:canonical:$sourceId:$processId:$threadId"
                },
            kind = ProfileTrackKind.CPU_SAMPLES,
            processId = processId,
            threadId = threadId,
            availability = availability,
            startNanos = startNanos,
            endNanosExclusive = endNanosExclusive,
        )
}

private data class FactTrack(
    val key: String,
    val sourceId: String,
    val processId: Int?,
    val threadId: Int?,
) {
    fun id(prefix: String): String =
        if (threadId == null) {
            "$prefix:$sourceId:global"
        } else {
            "$prefix:$sourceId:$processId:$threadId"
        }
}

private enum class ThreadFactKind(
    val table: String,
    val idPrefix: String,
    val trackKind: ProfileTrackKind,
    val exclusiveEndSql: String,
    val lowerBoundSql: String,
    val lowerBoundParameterCount: Int,
) {
    MARKER(
        table = "profile_marker",
        idPrefix = "markers",
        trackKind = ProfileTrackKind.MARKERS,
        exclusiveEndSql =
            "CASE WHEN f.end_nanos IS NOT NULL THEN f.end_nanos " +
                "WHEN f.start_nanos = 9223372036854775807 THEN f.start_nanos ELSE f.start_nanos + 1 END",
        lowerBoundSql =
            "((f.end_nanos IS NULL AND f.start_nanos >= ?) OR " +
                "(f.end_nanos IS NOT NULL AND f.end_nanos > ?))",
        lowerBoundParameterCount = 2,
    ),
    SLICE(
        table = "profile_slice",
        idPrefix = "slices",
        trackKind = ProfileTrackKind.SLICES,
        exclusiveEndSql = "f.end_nanos",
        lowerBoundSql = "f.end_nanos > ?",
        lowerBoundParameterCount = 1,
    ),
}

private enum class GlobalFactKind(
    val table: String,
    val idPrefix: String,
    val trackKind: ProfileTrackKind,
) {
    COUNTER("profile_counter", "counters", ProfileTrackKind.COUNTERS),
    SCREENSHOT("profile_screenshot", "screenshots", ProfileTrackKind.SCREENSHOTS),
}

private fun ResultSet.cpuTrack(): CpuTrack =
    CpuTrack(
        key = getString(1),
        sourceId = getString(2),
        processId = getInt(3),
        threadId = getInt(4),
    )

private fun ResultSet.getNullableInt(index: Int): Int? {
    val value = getInt(index)
    return if (wasNull()) null else value
}

private fun ResultSet.getNullableLong(index: Int): Long? {
    val value = getLong(index)
    return if (wasNull()) null else value
}

private fun factKey(
    sourceId: String,
    threadRowId: Long?,
): String = "$sourceId:${threadRowId ?: "global"}"

private fun Pair<Long, Long>?.availability(): ProfileDataAvailability =
    if (this == null) ProfileDataAvailability.EMPTY else ProfileDataAvailability.AVAILABLE

private fun Long.exclusiveEnd(): Long = if (this == Long.MAX_VALUE) this else this + 1

private fun ProfileQuery.freeze(): ProfileQuery =
    copy(
        threadIds = threadIds.toSet(),
        eventTypes = eventTypes.toSet(),
    )
