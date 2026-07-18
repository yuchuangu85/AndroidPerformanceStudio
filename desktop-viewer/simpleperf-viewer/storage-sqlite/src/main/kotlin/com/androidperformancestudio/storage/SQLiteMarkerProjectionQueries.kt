@file:Suppress("MagicNumber", "TooManyFunctions")

package com.androidperformancestudio.storage

import java.sql.Connection
import java.sql.ResultSet
import java.util.Collections

internal object SQLiteMarkerProjectionQueries {
    fun load(
        connection: Connection,
        query: ProfileQuery,
        markerSearch: String,
    ): MarkerProjectionSnapshot {
        if (connection.isLegacySchema()) return notCollectedSnapshot()

        val frozenQuery = query.freeze()
        val timeFilter = frozenQuery.toMarkerTimeFilter()
        val filtered = timeFilter.withThreadSelection(frozenQuery.threadIds).withSearch(markerSearch)
        val markers = connection.queryMarkers(filtered)
        val emptyReason =
            if (markers.isNotEmpty()) {
                null
            } else {
                connection.emptyReason(timeFilter)
            }
        return MarkerProjectionSnapshot(
            availability = MarkerAvailability.AVAILABLE,
            emptyReason = emptyReason,
            markers = markers.immutableCopy(),
            lanes = markers.toLanes().immutableCopy(),
        )
    }

    private fun notCollectedSnapshot(): MarkerProjectionSnapshot =
        MarkerProjectionSnapshot(
            availability = MarkerAvailability.NOT_COLLECTED,
            emptyReason = null,
            markers = emptyList<MarkerProjectionRow>().immutableCopy(),
            lanes = emptyList<MarkerLane>().immutableCopy(),
        )

    private fun Connection.queryMarkers(filter: MarkerFilter): List<MarkerProjectionRow> =
        prepareStatement(MARKER_ROWS_SQL.replace("/*FILTER*/", filter.whereClause)).use { statement ->
            statement.bind(filter.parameters)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.markerRow())
                }
            }
        }

    private fun Connection.emptyReason(timeFilter: MarkerFilter): MarkerEmptyReason =
        when {
            !hasMarker(MarkerFilter("", emptyList())) -> MarkerEmptyReason.PROFILE_EMPTY
            !hasMarker(timeFilter) -> MarkerEmptyReason.RANGE_EMPTY
            else -> MarkerEmptyReason.FILTERED_EMPTY
        }

    private fun Connection.hasMarker(filter: MarkerFilter): Boolean =
        prepareStatement(MARKER_EXISTS_SQL.replace("/*FILTER*/", filter.whereClause)).use { statement ->
            statement.bind(filter.parameters)
            statement.executeQuery().use(ResultSet::next)
        }

    private fun ProfileQuery.toMarkerTimeFilter(): MarkerFilter {
        val predicates = mutableListOf<String>()
        val parameters = mutableListOf<Any>()
        startNanosInclusive?.let { rangeStart ->
            predicates +=
                "((m.end_nanos IS NULL AND m.start_nanos >= ?) OR " +
                "(m.end_nanos IS NOT NULL AND m.end_nanos > ?))"
            parameters += rangeStart
            parameters += rangeStart
        }
        endNanosExclusive?.let { rangeEnd ->
            predicates += "m.start_nanos < ?"
            parameters += rangeEnd
        }
        return MarkerFilter(predicates.toWhereClause(), parameters)
    }

    private fun MarkerFilter.withThreadSelection(threadIds: Set<Int>): MarkerFilter {
        if (threadIds.isEmpty()) return this
        val sortedThreadIds = threadIds.sorted()
        return withPredicate(
            predicate = "pt.thread_id IN (${sortedThreadIds.joinToString { "?" }})",
            predicateParameters = sortedThreadIds,
        )
    }

    private fun MarkerFilter.withSearch(markerSearch: String): MarkerFilter {
        if (markerSearch.isBlank()) return this
        val pattern = "%${markerSearch.escapeLike()}%"
        val searchPredicate =
            "(LOWER(m.name) LIKE LOWER(?) ESCAPE '\\' OR " +
                "LOWER(m.schema_name) LIKE LOWER(?) ESCAPE '\\' OR " +
                "LOWER(m.payload_json) LIKE LOWER(?) ESCAPE '\\')"
        return withPredicate(
            predicate = searchPredicate,
            predicateParameters = listOf(pattern, pattern, pattern),
        )
    }

    private fun MarkerFilter.withPredicate(
        predicate: String,
        predicateParameters: List<Any>,
    ): MarkerFilter {
        val predicates =
            if (whereClause.isEmpty()) {
                listOf(predicate)
            } else {
                listOf(whereClause.removePrefix("WHERE "), predicate)
            }
        return MarkerFilter(
            whereClause = predicates.toWhereClause(),
            parameters = parameters + predicateParameters,
        )
    }

    private fun ResultSet.markerRow(): MarkerProjectionRow {
        val interval = getObject(7) != null
        val startNanos = getLong(6)
        return MarkerProjectionRow(
            id = ProfileMarkerId(getLong(1)),
            sourceId = getString(2),
            processId = getNullableInt(3),
            threadId = getNullableInt(4),
            threadName = getString(5),
            startNanos = startNanos,
            endNanosExclusive = if (interval) getLong(7) else startNanos.exclusiveEnd(),
            interval = interval,
            schema = getString(8),
            name = getString(9),
            payloadJson = getString(10),
        )
    }

    private fun List<MarkerProjectionRow>.toLanes(): List<MarkerLane> =
        groupBy { marker ->
            MarkerLaneIdentity(
                sourceId = marker.sourceId,
                processId = marker.processId,
                threadId = marker.threadId,
            )
        }.entries
            .sortedWith(
                compareBy(
                    { entry -> entry.key.sourceId },
                    { entry -> if (entry.key.processId == null) 0 else 1 },
                    { entry -> entry.key.processId ?: 0 },
                    { entry -> if (entry.key.threadId == null) 0 else 1 },
                    { entry -> entry.key.threadId ?: 0 },
                ),
            ).map { (identity, markers) ->
                MarkerLane(
                    key = identity.key(),
                    label = identity.label(markers.first().threadName),
                    markerIds = markers.map(MarkerProjectionRow::id).immutableCopy(),
                )
            }

    private fun MarkerLaneIdentity.key(): String =
        if (threadId == null) {
            "markers:$sourceId:global"
        } else {
            "markers:$sourceId:$processId:$threadId"
        }

    private fun MarkerLaneIdentity.label(threadName: String?): String =
        if (threadId == null) {
            "$sourceId: Global"
        } else {
            "$sourceId: ${threadName ?: "Thread $threadId"} ($processId:$threadId)"
        }

    private fun List<String>.toWhereClause(): String = if (isEmpty()) "" else "WHERE ${joinToString(" AND ")}"

    private fun String.escapeLike(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun ResultSet.getNullableInt(index: Int): Int? {
        val value = getInt(index)
        return if (wasNull()) null else value
    }

    private fun Long.exclusiveEnd(): Long = if (this == Long.MAX_VALUE) this else this + 1

    private fun ProfileQuery.freeze(): ProfileQuery =
        copy(
            threadIds = threadIds.toSet(),
            eventTypes = eventTypes.toSet(),
        )

    private fun <T> List<T>.immutableCopy(): List<T> = Collections.unmodifiableList(ArrayList(this))

    private data class MarkerFilter(
        val whereClause: String,
        val parameters: List<Any>,
    )

    private data class MarkerLaneIdentity(
        val sourceId: String,
        val processId: Int?,
        val threadId: Int?,
    )

    private const val MARKER_ROWS_SQL =
        "SELECT m.marker_id, m.source_id, pp.process_id, pt.thread_id, pt.name, m.start_nanos, " +
            "m.end_nanos, m.schema_name, m.name, m.payload_json FROM profile_marker m " +
            "LEFT JOIN profile_thread pt ON pt.thread_row_id=m.thread_row_id " +
            "LEFT JOIN profile_process pp ON pp.process_row_id=pt.process_row_id " +
            "/*FILTER*/ ORDER BY m.start_nanos, m.marker_id"

    private const val MARKER_EXISTS_SQL =
        "SELECT 1 FROM profile_marker m " +
            "LEFT JOIN profile_thread pt ON pt.thread_row_id=m.thread_row_id /*FILTER*/ LIMIT 1"
}
